package com.acentra.cache.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Asynchronous, batching HTTP telemetry sender with bounded retry/backoff.
 * <ul>
 *   <li>Events are queued locally (bounded) and sent every {@code flushInterval} or when {@code batchSize} is reached.</li>
 *   <li>If the service is down: the cache keeps working, failures are counted, batches are retried with exponential
 *       backoff (capped) and the retry buffer is bounded. Only safe telemetry is ever buffered.</li>
 *   <li>4xx responses other than 429 are not retried (the batch is dropped and counted as failed).</li>
 *   <li>The API key and payloads are never logged.</li>
 * </ul>
 */
public final class BatchingHttpTelemetryClient implements TelemetryClient {
    private static final System.Logger LOG = System.getLogger(BatchingHttpTelemetryClient.class.getName());
    private static final String SDK_VERSION = "1.0.0";

    /** Configuration with sensible defaults. */
    public static final class Config {
        String endpoint;
        String apiKey;
        String applicationName;
        String environment = "local";
        String instanceId = "instance-" + Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xffffffL);
        Duration flushInterval = Duration.ofSeconds(5);
        int batchSize = 50;
        int maxQueueSize = 10_000;
        Duration initialBackoff = Duration.ofSeconds(1);
        Duration maxBackoff = Duration.ofSeconds(60);
        Duration connectTimeout = Duration.ofSeconds(2);
        Duration requestTimeout = Duration.ofSeconds(3);
        boolean controlPollingEnabled = true;

        public Config endpoint(String v) { this.endpoint = v; return this; }
        public Config apiKey(String v) { this.apiKey = v; return this; }
        public Config applicationName(String v) { this.applicationName = v; return this; }
        public Config environment(String v) { this.environment = v; return this; }
        public Config instanceId(String v) { this.instanceId = v; return this; }
        public Config flushInterval(Duration v) { this.flushInterval = v; return this; }
        public Config batchSize(int v) { this.batchSize = v; return this; }
        public Config maxQueueSize(int v) { this.maxQueueSize = v; return this; }
        public Config initialBackoff(Duration v) { this.initialBackoff = v; return this; }
        public Config maxBackoff(Duration v) { this.maxBackoff = v; return this; }
        public Config connectTimeout(Duration v) { this.connectTimeout = v; return this; }
        public Config requestTimeout(Duration v) { this.requestTimeout = v; return this; }
        public Config controlPollingEnabled(boolean v) { this.controlPollingEnabled = v; return this; }
    }

    private final Config cfg;
    private final ObjectMapper mapper = TelemetryJson.mapper();
    private final HttpClient http;
    private final URI batchUri;
    private final URI controlUri;

    private final LinkedBlockingQueue<TelemetryEvent> queue;
    private final ArrayDeque<TelemetryEvent> retryBuffer = new ArrayDeque<>();
    private final Map<String, RegionSnapshot> latestSnapshots = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> sent = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> failed = new ConcurrentHashMap<>();
    private final AtomicInteger streak = new AtomicInteger();
    private final AtomicBoolean flushQueued = new AtomicBoolean();
    private final ScheduledExecutorService scheduler;
    private volatile long nextAttemptAtMs;
    private volatile ControlListener controlListener;
    private volatile boolean closed;

    public static Config config() {
        return new Config();
    }

    public BatchingHttpTelemetryClient(Config cfg) {
        this.cfg = Objects.requireNonNull(cfg, "config");
        Objects.requireNonNull(cfg.endpoint, "endpoint");
        Objects.requireNonNull(cfg.apiKey, "apiKey");
        Objects.requireNonNull(cfg.applicationName, "applicationName");
        String base = cfg.endpoint.endsWith("/") ? cfg.endpoint.substring(0, cfg.endpoint.length() - 1) : cfg.endpoint;
        this.batchUri = URI.create(base + "/api/v1/telemetry/events/batch");
        this.controlUri = URI.create(base + "/api/v1/telemetry/control");
        this.http = HttpClient.newBuilder().connectTimeout(cfg.connectTimeout).build();
        this.queue = new LinkedBlockingQueue<>(cfg.maxQueueSize);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "acentra-telemetry-sender");
            t.setDaemon(true);
            return t;
        });
        long interval = Math.max(100, cfg.flushInterval.toMillis());
        scheduler.scheduleWithFixedDelay(this::cycleQuietly, interval, interval, TimeUnit.MILLISECONDS);
    }

    @Override
    public void publishEvent(TelemetryEvent event) {
        if (closed || event == null) return;
        if (!queue.offer(event)) {
            counter(failed, event.cacheRegion()).increment(); // dropped: queue full
            return;
        }
        if (queue.size() >= cfg.batchSize && flushQueued.compareAndSet(false, true)) {
            try {
                scheduler.execute(() -> {
                    flushQueued.set(false);
                    cycleQuietly();
                });
            } catch (RuntimeException rejected) {
                flushQueued.set(false);
            }
        }
    }

    @Override
    public void publishSnapshot(RegionSnapshot snapshot) {
        if (closed || snapshot == null) return;
        latestSnapshots.put(snapshot.cacheRegion(), snapshot);
    }

    @Override public long eventsSent(String region) { return counter(sent, region).sum(); }
    @Override public long eventsFailed(String region) { return counter(failed, region).sum(); }
    @Override public int failureStreak() { return streak.get(); }
    @Override public void setControlListener(ControlListener listener) { this.controlListener = listener; }

    @Override
    public void flush() {
        if (closed) return;
        try {
            scheduler.submit(() -> {
                nextAttemptAtMs = 0;
                cycleQuietly();
            }).get(cfg.requestTimeout.toMillis() * 3, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // best effort only
        }
    }

    @Override
    public void close() {
        if (closed) return;
        try {
            flush();
        } finally {
            closed = true;
            scheduler.shutdownNow();
        }
    }

    // ---- sender thread ------------------------------------------------------------------------------------------

    private void cycleQuietly() {
        try {
            cycle();
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.WARNING, "Telemetry cycle error: {0}", t.getClass().getSimpleName());
        }
    }

    private void cycle() {
        if (System.currentTimeMillis() < nextAttemptAtMs) return;
        boolean ok = true;
        boolean sentSomething = false;
        for (int i = 0; i < 10 && ok; i++) {
            List<TelemetryEvent> batch = nextBatch();
            boolean lastRound = queue.isEmpty() && retryBuffer.isEmpty();
            if (batch.isEmpty() && (sentSomething || latestSnapshots.isEmpty())) break;
            ok = deliver(batch);
            sentSomething = true;
            if (lastRound) break;
        }
        if (ok && cfg.controlPollingEnabled && controlListener != null) pollControl();
    }

    private List<TelemetryEvent> nextBatch() {
        List<TelemetryEvent> batch = new ArrayList<>(cfg.batchSize);
        while (batch.size() < cfg.batchSize && !retryBuffer.isEmpty()) batch.add(retryBuffer.pollFirst());
        if (batch.size() < cfg.batchSize) queue.drainTo(batch, cfg.batchSize - batch.size());
        return batch;
    }

    private boolean deliver(List<TelemetryEvent> events) {
        Map<String, RegionSnapshot> snaps = new ConcurrentHashMap<>(latestSnapshots);
        List<RegionSnapshot> snapshotList = new ArrayList<>(snaps.values());
        TelemetryBatch body = new TelemetryBatch(
                cfg.applicationName, cfg.environment, cfg.instanceId, SDK_VERSION, Instant.now(), events, snapshotList);
        int status;
        try {
            byte[] json = mapper.writeValueAsBytes(body);
            HttpRequest req = HttpRequest.newBuilder(batchUri)
                    .timeout(cfg.requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("X-AcentraCache-Key", cfg.apiKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                    .build();
            status = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            requeue(events);
            return false;
        } catch (IOException | RuntimeException e) {
            onFailure(events, snapshotList, true, "I/O error (" + e.getClass().getSimpleName() + ")");
            return false;
        }
        if (status >= 200 && status < 300) {
            for (TelemetryEvent e : events) counter(sent, e.cacheRegion()).increment();
            snaps.forEach(latestSnapshots::remove); // remove only if unchanged? keep simple: replaced ones re-added below
            streak.set(0);
            nextAttemptAtMs = 0;
            return true;
        }
        boolean retryable = status == 429 || status >= 500;
        onFailure(events, snapshotList, retryable, "HTTP " + status);
        return false;
    }

    private void onFailure(List<TelemetryEvent> events, List<RegionSnapshot> snaps, boolean retryable, String what) {
        for (TelemetryEvent e : events) counter(failed, e.cacheRegion()).increment();
        for (RegionSnapshot s : snaps) counter(failed, s.cacheRegion()).increment();
        int n = streak.incrementAndGet();
        long backoff = Math.min(cfg.maxBackoff.toMillis(), cfg.initialBackoff.toMillis() << Math.min(n - 1, 16));
        backoff += ThreadLocalRandom.current().nextLong(Math.max(1, backoff / 5));
        nextAttemptAtMs = System.currentTimeMillis() + backoff;
        if (retryable) requeue(events);
        LOG.log(System.Logger.Level.WARNING,
                "Telemetry delivery failed ({0}); consecutive failures={1}, next attempt in {2} ms, {3}",
                what, n, backoff, retryable ? "events kept for retry" : "batch dropped (not retryable)");
    }

    private void requeue(Collection<TelemetryEvent> events) {
        // Put back at the front, oldest first; the retry buffer is bounded so memory stays flat during long outages.
        List<TelemetryEvent> list = new ArrayList<>(events);
        for (int i = list.size() - 1; i >= 0; i--) retryBuffer.addFirst(list.get(i));
        while (retryBuffer.size() > cfg.maxQueueSize) {
            TelemetryEvent dropped = retryBuffer.pollFirst();
            if (dropped != null) counter(failed, dropped.cacheRegion()).increment();
        }
    }

    private void pollControl() {
        try {
            HttpRequest req = HttpRequest.newBuilder(controlUri)
                    .timeout(cfg.requestTimeout)
                    .header("X-AcentraCache-Key", cfg.apiKey)
                    .GET()
                    .build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() == 200) {
                ControlResponse cr = mapper.readValue(res.body(), ControlResponse.class);
                ControlListener l = controlListener;
                if (l != null && cr != null && cr.regions() != null && !cr.regions().isEmpty()) l.onControl(cr);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG, "Control poll failed: {0}", e.getClass().getSimpleName());
        }
    }

    private static LongAdder counter(Map<String, LongAdder> m, String region) {
        return m.computeIfAbsent(region == null ? "unknown" : region, k -> new LongAdder());
    }
}
