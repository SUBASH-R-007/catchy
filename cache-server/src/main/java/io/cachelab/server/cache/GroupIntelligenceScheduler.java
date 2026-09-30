package io.cachelab.server.cache;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Keeps every group's intelligence fresh (SPEC 6.3, 6.4): every {@value #EVALUATE_MS} ms it
 * re-evaluates each group's {@link io.cachelab.advisor.PolicyAdvisor}, and every {@value
 * #OPTIMAL_MS} ms it recomputes each group's optimal (Bélády) hit rate over the recorded trace.
 *
 * <p>Both jobs run on one background daemon thread ({@value #THREAD_NAME}), never on the metrics
 * (SSE) thread, which only reads the latest results. A failing job is logged and retried at the
 * next period.
 *
 * <p>Thread-safe.
 */
@Component
public class GroupIntelligenceScheduler implements SmartLifecycle {

  /** Advisor evaluation period, in milliseconds. */
  public static final long EVALUATE_MS = 1_000;

  /** Optimal hit rate recomputation period, in milliseconds. */
  public static final long OPTIMAL_MS = 5_000;

  /** Name of the background thread. */
  public static final String THREAD_NAME = "cachelab-advisor";

  private static final Logger log = LoggerFactory.getLogger(GroupIntelligenceScheduler.class);

  private final CacheRegistry registry;
  private ScheduledExecutorService executor;

  /**
   * Creates a stopped scheduler; Spring starts it with the application context.
   *
   * @param registry source of the groups
   */
  public GroupIntelligenceScheduler(CacheRegistry registry) {
    this.registry = registry;
  }

  /** Re-evaluates every group's advisor. Never throws. */
  void evaluateAll() {
    try {
      registry.groupList().forEach(CacheGroup::evaluate);
    } catch (RuntimeException e) {
      log.warn("Advisor evaluation failed; retrying next second", e);
    }
  }

  /** Recomputes every group's optimal hit rate. Never throws. */
  void computeOptimalAll() {
    try {
      registry.groupList().forEach(CacheGroup::computeOptimal);
    } catch (RuntimeException e) {
      log.warn("Optimal hit rate computation failed; retrying later", e);
    }
  }

  /** Starts both periodic jobs. Idempotent. */
  @Override
  public synchronized void start() {
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
    executor.scheduleAtFixedRate(this::evaluateAll, EVALUATE_MS, EVALUATE_MS, TimeUnit.MILLISECONDS);
    executor.scheduleWithFixedDelay(
        this::computeOptimalAll, OPTIMAL_MS, OPTIMAL_MS, TimeUnit.MILLISECONDS);
  }

  /** Stops the jobs. Idempotent. */
  @Override
  public synchronized void stop() {
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }

  /**
   * Reports whether the jobs are scheduled.
   *
   * @return {@code true} between {@link #start()} and {@link #stop()}
   */
  @Override
  public synchronized boolean isRunning() {
    return executor != null;
  }
}
