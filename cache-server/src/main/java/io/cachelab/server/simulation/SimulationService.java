package io.cachelab.server.simulation;

import io.cachelab.server.cache.CacheRegistry;
import io.cachelab.server.cache.GroupNotFoundException;
import io.cachelab.server.cache.ManagedCache;
import io.cachelab.server.metrics.LatencyRecorders;
import io.cachelab.server.metrics.SimulationStatus;
import io.cachelab.server.workload.KeyStreams;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs workloads against a group's caches (SPEC 9.2), one simulation at a time.
 *
 * <p>{@link #start} stops the running simulation (interrupt + join) and starts the new plan on its
 * own daemon thread {@code cachelab-sim-<id>}. The thread runs the phases in order, each for its
 * {@code durationSec} of wall-clock time, pacing operations to {@code opsPerSec} with {@link
 * LockSupport#parkNanos} in 1 ms batches ({@code opsPerSec = 0} runs unthrottled). Key streams use
 * logical time (see {@link KeyStreams}), so the key sequence does not depend on pacing.
 *
 * <p>{@link #status()} feeds the metrics stream; after a simulation ends its last status is kept
 * with {@code running = false}. The final {@link SimulationSummary} of the most recent simulations
 * is kept for reports.
 *
 * <p>Thread-safe.
 */
@Service
public class SimulationService {

  /** Summaries kept, newest last. */
  static final int SUMMARIES_KEPT = 20;

  private static final Logger log = LoggerFactory.getLogger(SimulationService.class);
  private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("s-(\\d+)");
  private static final int UNTHROTTLED_BATCH = 1_000;
  private static final long BATCH_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
  private static final long JOIN_TIMEOUT_MS = 5_000;

  private final CacheRegistry registry;
  private final LatencyRecorders latencies;
  private final Clock clock;
  private final AtomicLong lastId = new AtomicLong();
  private final Object startStopLock = new Object();
  private final AtomicReference<Running> current = new AtomicReference<>();
  private final AtomicReference<SimulationStatus> status = new AtomicReference<>();
  private final Map<String, SimulationSummary> summaries = new LinkedHashMap<>();

  /**
   * Creates the service.
   *
   * @param registry source of the group's caches
   * @param latencies per-cache {@code get} latency recorders
   * @param clock wall clock for status and summary timestamps
   */
  public SimulationService(CacheRegistry registry, LatencyRecorders latencies, Clock clock) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.latencies = Objects.requireNonNull(latencies, "latencies");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Starts a plan, stopping the running simulation first.
   *
   * @param plan the plan; never {@code null}
   * @return the new simulation's id, {@code s-<n>}
   * @throws GroupNotFoundException if the group has no caches
   * @throws IllegalArgumentException if a phase's parameters are invalid (nothing is stopped)
   */
  public String start(SimulationPlan plan) {
    Objects.requireNonNull(plan, "plan");
    List<ManagedCache> caches = groupCaches(plan.group());
    plan.phases().forEach(p -> KeyStreams.create(p.pattern(), p.params())); // validate first
    synchronized (startStopLock) {
      stopAndJoin(current.get());
      String id = "s-" + lastId.incrementAndGet();
      Running sim = new Running(id, plan, new SimulationRunner(plan, caches, latencies));
      sim.startedTs = clock.millis();
      status.set(statusOf(sim, 0, true, sim.startedTs));
      current.set(sim);
      Thread thread = new Thread(() -> run(sim), "cachelab-sim-" + id);
      thread.setDaemon(true);
      sim.thread = thread;
      thread.start();
      return id;
    }
  }

  /**
   * Stops a simulation; a no-op if it already ended.
   *
   * @param id the id returned by {@link #start}
   * @throws SimulationNotFoundException if no simulation ever had this id
   */
  public void stop(String id) {
    if (!issued(id)) {
      throw new SimulationNotFoundException(id);
    }
    synchronized (startStopLock) {
      Running sim = current.get();
      if (sim != null && sim.id.equals(id)) {
        stopAndJoin(sim);
      }
    }
  }

  /** Stops the running simulation, if any, at shutdown. */
  @PreDestroy
  public void stopAll() {
    synchronized (startStopLock) {
      stopAndJoin(current.get());
    }
  }

  /**
   * Returns the status for the metrics stream.
   *
   * @return the running or last simulation's status, or {@code null} if none ever ran
   */
  public SimulationStatus status() {
    return status.get();
  }

  /**
   * Returns the final summary of a finished simulation.
   *
   * @param id the simulation id
   * @return the summary, or empty if it is still running, unknown or no longer kept
   */
  public Optional<SimulationSummary> summary(String id) {
    synchronized (summaries) {
      return Optional.ofNullable(summaries.get(id));
    }
  }

  /**
   * Returns the summary of the most recently finished simulation.
   *
   * @return the summary, or empty if none finished yet
   */
  public Optional<SimulationSummary> latestSummary() {
    synchronized (summaries) {
      return summaries.values().stream().reduce((a, b) -> b);
    }
  }

  /**
   * Runs {@code n} operations of a plan synchronously on the calling thread, advancing phases by
   * logical time ({@code durationSec x effectiveRate} operations each). For tests; does not touch
   * the running simulation or the status.
   */
  SimulationSummary runOps(SimulationPlan plan, long n) {
    SimulationRunner runner = new SimulationRunner(plan, groupCaches(plan.group()), latencies);
    long startedTs = clock.millis();
    runner.runLogical(n);
    return runner.summary("sync", startedTs, clock.millis(), true);
  }

  private List<ManagedCache> groupCaches(String group) {
    List<ManagedCache> caches = registry.groups().get(group);
    if (caches == null || caches.isEmpty()) {
      throw new GroupNotFoundException(group);
    }
    return caches;
  }

  private boolean issued(String id) {
    Matcher m = ID.matcher(id);
    if (!m.matches() || m.group(1).length() > 18) {
      return false;
    }
    long n = Long.parseLong(m.group(1));
    return n >= 1 && n <= lastId.get();
  }

  private static void stopAndJoin(Running sim) {
    if (sim == null || sim.thread == null) {
      return;
    }
    sim.stopRequested = true;
    sim.thread.interrupt();
    try {
      sim.thread.join(JOIN_TIMEOUT_MS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (sim.thread.isAlive()) {
      log.warn("Simulation {} did not stop within {} ms", sim.id, JOIN_TIMEOUT_MS);
    }
  }

  /** Body of the simulation thread. */
  private void run(Running sim) {
    boolean completed = false;
    try {
      int phases = sim.plan.phases().size();
      for (int p = 0; p < phases && !sim.stopped(); p++) {
        if (p > 0) {
          sim.runner.enterPhase(p);
          publish(sim, statusOf(sim, p, true, clock.millis()));
        }
        runPhase(sim, sim.plan.phases().get(p).durationSec());
      }
      completed = !sim.stopped();
    } catch (RuntimeException e) {
      log.warn("Simulation {} failed; stopping it", sim.id, e);
    } finally {
      finish(sim, completed);
    }
  }

  /** Runs one phase for {@code durationSec} of wall-clock time or until stopped. */
  private void runPhase(Running sim, int durationSec) {
    int rate = sim.plan.opsPerSec();
    long start = System.nanoTime();
    long durationNanos = TimeUnit.SECONDS.toNanos(durationSec);
    long done = 0;
    while (!sim.stopped()) {
      long elapsed = System.nanoTime() - start;
      if (elapsed >= durationNanos) {
        return;
      }
      long due = rate == 0 ? done + UNTHROTTLED_BATCH : elapsed * rate / 1_000_000_000L;
      long batch = Math.min(due - done, UNTHROTTLED_BATCH);
      if (batch <= 0) {
        LockSupport.parkNanos(BATCH_NANOS);
        continue;
      }
      for (long i = 0; i < batch; i++) {
        sim.runner.step();
      }
      done += batch;
    }
  }

  private void finish(Running sim, boolean completed) {
    SimulationSummary summary =
        sim.runner.summary(sim.id, sim.startedTs, clock.millis(), completed);
    synchronized (summaries) {
      summaries.put(sim.id, summary);
      if (summaries.size() > SUMMARIES_KEPT) {
        summaries.remove(summaries.keySet().iterator().next());
      }
    }
    SimulationStatus last = sim.lastStatus;
    publish(
        sim,
        new SimulationStatus(
            last.id(),
            false,
            last.group(),
            last.pattern(),
            last.act(),
            last.phaseIndex(),
            last.phaseCount(),
            last.phaseCaption(),
            last.phaseStartedTs()));
    current.compareAndSet(sim, null);
  }

  /** Publishes a status of {@code sim} unless a newer simulation has already replaced it. */
  private void publish(Running sim, SimulationStatus next) {
    sim.lastStatus = next;
    status.updateAndGet(s -> s == null || s.id().equals(sim.id) ? next : s);
  }

  private SimulationStatus statusOf(Running sim, int phase, boolean running, long phaseStartedTs) {
    SimulationPlan.Phase p = sim.plan.phases().get(phase);
    SimulationStatus s =
        new SimulationStatus(
            sim.id,
            running,
            sim.plan.group(),
            p.pattern(),
            sim.plan.act(),
            phase,
            sim.plan.phases().size(),
            p.caption(),
            phaseStartedTs);
    sim.lastStatus = s;
    return s;
  }

  /** One simulation's thread-shared state. */
  private static final class Running {
    final String id;
    final SimulationPlan plan;
    final SimulationRunner runner;
    volatile boolean stopRequested;
    volatile Thread thread;
    volatile long startedTs;
    volatile SimulationStatus lastStatus;

    Running(String id, SimulationPlan plan, SimulationRunner runner) {
      this.id = id;
      this.plan = plan;
      this.runner = runner;
    }

    boolean stopped() {
      return stopRequested || Thread.currentThread().isInterrupted();
    }
  }
}
