package io.cachelab.server.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes the metrics stream to every connected SSE client (SPEC 8.3, 8.4).
 *
 * <p>One daemon thread ({@value #THREAD_NAME}) ticks every {@value #TICK_MS} ms: it asks the {@link
 * MetricsSource} for the next snapshot, serializes it once to JSON and sends that string to every
 * emitter as an event named {@value #EVENT_NAME}. An emitter is removed when it completes, times
 * out or errors, and when a send to it fails; a failure never stops the loop. A new client
 * immediately receives the latest payload.
 *
 * <p>Thread-safe. A tick is O(number of emitters).
 */
@Component
public class MetricsPublisher implements SmartLifecycle {

  /** Name of every SSE event on the stream. */
  public static final String EVENT_NAME = "metrics";

  /** Interval between ticks, in milliseconds. */
  public static final long TICK_MS = 500;

  /** Name of the publisher thread. */
  public static final String THREAD_NAME = "cachelab-metrics";

  private static final Logger log = LoggerFactory.getLogger(MetricsPublisher.class);

  private final MetricsSource source;
  private final ObjectMapper objectMapper;
  private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

  /** Guards {@link #latestJson} and {@link #executor}; makes register vs. tick race-free. */
  private final Object lock = new Object();

  private String latestJson;
  private ScheduledExecutorService executor;

  /**
   * Creates a stopped publisher; Spring starts it with the application context.
   *
   * @param source produces one snapshot per tick; never {@code null}
   * @param objectMapper the application's JSON mapper; never {@code null}
   */
  public MetricsPublisher(MetricsSource source, ObjectMapper objectMapper) {
    this.source = Objects.requireNonNull(source, "source");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  /**
   * Registers a new client. The emitter never times out and already holds the latest payload, if
   * any.
   *
   * @return the emitter to return from the controller; never {@code null}
   */
  public SseEmitter register() {
    return register(new SseEmitter(0L));
  }

  /** Registers {@code emitter}; package-private so tests can supply their own emitter. */
  SseEmitter register(SseEmitter emitter) {
    emitter.onCompletion(() -> emitters.remove(emitter));
    emitter.onTimeout(() -> emitters.remove(emitter));
    emitter.onError(ex -> emitters.remove(emitter));
    synchronized (lock) {
      // Under the lock, a concurrent tick either already set latestJson (sent here) or will
      // include this emitter in its targets (not sent here): never both, never out of order.
      emitters.add(emitter);
      if (latestJson != null) {
        send(emitter, latestJson);
      }
    }
    return emitter;
  }

  /**
   * Returns the number of connected clients.
   *
   * @return current number of registered emitters
   */
  public int emitterCount() {
    return emitters.size();
  }

  /** One tick: build the payload once, then send it to every emitter. Never throws. */
  void tick() {
    try {
      String json = objectMapper.writeValueAsString(source.next());
      List<SseEmitter> targets;
      synchronized (lock) {
        latestJson = json;
        targets = List.copyOf(emitters);
      }
      for (SseEmitter emitter : targets) {
        send(emitter, json);
      }
    } catch (Throwable t) {
      log.warn("Metrics tick failed; the stream continues", t);
    }
  }

  private void send(SseEmitter emitter, String json) {
    try {
      emitter.send(SseEmitter.event().name(EVENT_NAME).data(json, MediaType.APPLICATION_JSON));
    } catch (IOException | RuntimeException ex) {
      emitters.remove(emitter);
      log.debug("Dropping SSE client after a failed send: {}", ex.toString());
      try {
        emitter.completeWithError(ex);
      } catch (RuntimeException ignored) {
        // the emitter is already completed; nothing left to clean up
      }
    }
  }

  /** Starts ticking on the publisher thread. Idempotent. */
  @Override
  public void start() {
    synchronized (lock) {
      if (executor != null) {
        return;
      }
      executor =
          Executors.newSingleThreadScheduledExecutor(
              runnable -> {
                Thread thread = new Thread(runnable, THREAD_NAME);
                thread.setDaemon(true);
                return thread;
              });
      executor.scheduleAtFixedRate(this::tick, 0, TICK_MS, TimeUnit.MILLISECONDS);
    }
  }

  /** Stops ticking and completes every emitter so open connections end cleanly. Idempotent. */
  @Override
  public void stop() {
    ScheduledExecutorService stopping;
    synchronized (lock) {
      stopping = executor;
      executor = null;
    }
    if (stopping != null) {
      stopping.shutdown();
      awaitTermination(stopping);
    }
    for (SseEmitter emitter : emitters) {
      try {
        emitter.complete();
      } catch (RuntimeException ignored) {
        // already completed by the container
      }
    }
    emitters.clear();
  }

  /**
   * Reports whether the publisher is ticking.
   *
   * @return {@code true} between {@link #start()} and {@link #stop()}
   */
  @Override
  public boolean isRunning() {
    synchronized (lock) {
      return executor != null;
    }
  }

  private static void awaitTermination(ScheduledExecutorService stopping) {
    try {
      if (!stopping.awaitTermination(2, TimeUnit.SECONDS)) {
        stopping.shutdownNow();
      }
    } catch (InterruptedException e) {
      stopping.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
