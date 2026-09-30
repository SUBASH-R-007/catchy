package io.cachelab.internal;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The background expiry sweeper: one daemon thread named {@code cachelab-sweeper-<name>} that runs
 * a sweep task every sweep interval. A failing sweep is logged and never stops later sweeps.
 *
 * <p>Thread-safety: safe to close from any thread; closing twice has no further effect.
 */
final class Sweeper implements AutoCloseable {

  private static final System.Logger LOG = System.getLogger(Sweeper.class.getName());

  private final ScheduledExecutorService executor;

  Sweeper(String cacheName, long intervalNanos, Runnable sweep) {
    String threadName = "cachelab-sweeper-" + cacheName;
    executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, threadName);
              t.setDaemon(true);
              return t;
            });
    executor.scheduleWithFixedDelay(
        () -> {
          try {
            sweep.run();
          } catch (RuntimeException | Error e) {
            LOG.log(System.Logger.Level.WARNING, "Expiry sweep failed in " + threadName, e);
          }
        },
        intervalNanos,
        intervalNanos,
        TimeUnit.NANOSECONDS);
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}
