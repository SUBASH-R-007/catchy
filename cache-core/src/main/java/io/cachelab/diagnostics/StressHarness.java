package io.cachelab.diagnostics;

import io.cachelab.Cache;
import io.cachelab.CacheBuilder;
import io.cachelab.diagnostics.StressReport.InvariantResult;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Hammers a cache from many threads at once and checks the five invariants afterwards (SPEC 5).
 *
 * <p>All workers start together behind a latch and run until the deadline, mixing gets and puts
 * over a shared key space. Put values are {@code "k=<key>;t=<thread>;s=<seq>"} so a returned value
 * can be traced to its key. A sampler records {@code size()} every 10 ms. After the join, {@code
 * ThreadMXBean.findDeadlockedThreads()} is consulted.
 *
 * <p>Thread-safety: each call creates and closes its own cache and threads; concurrent calls are
 * safe but compete for CPU. Blocks for the configured duration.
 */
public final class StressHarness {

  private static final long SAMPLE_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
  private static final long JOIN_GRACE_MS = 10_000;

  private StressHarness() {}

  /**
   * Runs one stress test.
   *
   * @param config what to run; must not be null
   * @return the report, never null
   */
  public static StressReport run(StressConfig config) {
    try (Cache<String, String> cache = build(config)) {
      return new Run(config, cache).execute();
    }
  }

  static Cache<String, String> build(StressConfig config) {
    return CacheBuilder.<String, String>newBuilder()
        .name("stress-" + config.impl().name().toLowerCase(Locale.ROOT))
        .maximumSize(config.capacity())
        .evictionPolicy(config.policy())
        .concurrencyLevel(config.impl() == StressConfig.Impl.SEGMENTED ? StressConfig.SEGMENTS : 1)
        .build();
  }

  /** The state of one run. */
  private static final class Run {
    private final StressConfig config;
    private final Cache<String, String> cache;
    private final CountDownLatch go = new CountDownLatch(1);
    private final ConcurrentLinkedQueue<String> exceptions = new ConcurrentLinkedQueue<>();
    private final AtomicInteger exceptionCount = new AtomicInteger();
    private volatile long deadline;
    private volatile boolean sampling = true;
    private int maxSampled;
    private int samples;

    Run(StressConfig config, Cache<String, String> cache) {
      this.config = config;
      this.cache = cache;
    }

    StressReport execute() {
      List<Worker> workers = new ArrayList<>(config.threads());
      List<Thread> threads = new ArrayList<>(config.threads());
      for (int t = 0; t < config.threads(); t++) {
        Worker w = new Worker(t, new SplittableRandom(config.seed() + t));
        workers.add(w);
        threads.add(daemon("cachelab-stress-" + t, w));
      }
      Thread sampler = daemon("cachelab-stress-sampler", this::sample);
      threads.forEach(Thread::start);
      long start = System.nanoTime();
      deadline = start + config.duration().toNanos();
      sampler.start();
      go.countDown();
      boolean finished = joinAll(threads, config.duration().toMillis() + JOIN_GRACE_MS);
      long elapsedNanos = System.nanoTime() - start;
      boolean deadlockFree = ManagementFactory.getThreadMXBean().findDeadlockedThreads() == null;
      if (!finished) {
        record(new IllegalStateException("a worker did not finish within the grace period"));
        threads.forEach(Thread::interrupt);
      }
      sampling = false;
      joinAll(List.of(sampler), 1_000);
      return report(workers, elapsedNanos, deadlockFree);
    }

    private StressReport report(List<Worker> workers, long elapsedNanos, boolean deadlockFree) {
      long gets = 0;
      long puts = 0;
      long phantoms = 0;
      long checked = 0;
      String example = null;
      for (Worker w : workers) {
        gets += w.gets;
        puts += w.puts;
        phantoms += w.phantoms;
        checked += w.checked;
        example = example == null ? w.phantomExample : example;
      }
      List<InvariantResult> invariants =
          List.of(
              InvariantChecker.sizeBound(maxSampled, samples, cache.size(), config.capacity()),
              InvariantChecker.accounting(cache.stats(), gets),
              InvariantChecker.noPhantoms(phantoms, checked, example),
              InvariantChecker.structure(cache),
              InvariantChecker.noExceptions(exceptionCount.get(), exceptions.peek()));
      long totalOps = gets + puts;
      double seconds = Math.max(1e-9, elapsedNanos / 1e9);
      return new StressReport(
          config.impl(),
          config.threads(),
          TimeUnit.NANOSECONDS.toMillis(elapsedNanos),
          totalOps,
          totalOps / seconds,
          invariants,
          exceptions.stream().limit(10).toList(),
          deadlockFree);
    }

    private void sample() {
      while (sampling) {
        int size = cache.size();
        maxSampled = Math.max(maxSampled, size);
        samples++;
        LockSupport.parkNanos(SAMPLE_NANOS);
      }
    }

    private void record(Throwable t) {
      if (exceptionCount.incrementAndGet() <= 10) {
        exceptions.add(t.getClass().getSimpleName() + ": " + t.getMessage());
      }
    }

    /** One worker thread's loop and its private counters. */
    private final class Worker implements Runnable {
      private final int index;
      private final SplittableRandom rnd;
      long gets;
      long puts;
      long phantoms;
      long checked;
      String phantomExample;
      private long sequence;

      Worker(int index, SplittableRandom rnd) {
        this.index = index;
        this.rnd = rnd;
      }

      @Override
      public void run() {
        try {
          go.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        while (System.nanoTime() - deadline < 0) {
          for (int i = 0; i < 64; i++) {
            step();
          }
        }
      }

      private void step() {
        String key = "key:" + rnd.nextInt(config.keySpace());
        try {
          if (rnd.nextDouble() < config.readRatio()) {
            Optional<String> value = cache.get(key);
            gets++;
            value.ifPresent(v -> verify(key, v));
          } else {
            cache.put(key, "k=" + key + ";t=" + index + ";s=" + sequence++);
            puts++;
          }
        } catch (RuntimeException | Error e) {
          record(e);
        }
      }

      private void verify(String key, String value) {
        checked++;
        if (!value.startsWith("k=" + key + ";")) {
          phantoms++;
          phantomExample = phantomExample == null ? key + " -> " + value : phantomExample;
        }
      }
    }
  }

  private static Thread daemon(String name, Runnable task) {
    Thread t = new Thread(task, name);
    t.setDaemon(true);
    return t;
  }

  private static boolean joinAll(List<Thread> threads, long timeoutMs) {
    long until = System.currentTimeMillis() + timeoutMs;
    for (Thread t : threads) {
      try {
        t.join(Math.max(1, until - System.currentTimeMillis()));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
      if (t.isAlive()) {
        return false;
      }
    }
    return true;
  }
}
