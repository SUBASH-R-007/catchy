package io.cachelab.server.stress;

import io.cachelab.diagnostics.StampedeResult;
import io.cachelab.diagnostics.StampedeTest;
import io.cachelab.diagnostics.StressConfig;
import io.cachelab.diagnostics.StressHarness;
import io.cachelab.diagnostics.StressReport;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Runs diagnostics jobs (stress runs and stampede tests) one at a time (SPEC 8.1): a second request
 * while one is running is rejected with {@link StressBusyException} (HTTP 409), so two CPU-heavy
 * runs never distort each other's numbers.
 *
 * <p>Thread-safe. Each call blocks for the job's duration.
 */
@Service
public class StressService {

  private final AtomicBoolean busy = new AtomicBoolean();

  /**
   * Runs one stress test.
   *
   * @param config the run
   * @return the report
   * @throws StressBusyException if another job is running
   */
  public StressReport stress(StressConfig config) {
    return exclusively(() -> StressHarness.run(config));
  }

  /**
   * Runs one stampede test.
   *
   * @param threads concurrent callers
   * @param loaderDelay how long the loader takes
   * @return the result
   * @throws StressBusyException if another job is running
   */
  public StampedeResult stampede(int threads, Duration loaderDelay) {
    return exclusively(() -> StampedeTest.run(threads, loaderDelay));
  }

  /**
   * Reports whether a job is running.
   *
   * @return true while a job runs
   */
  public boolean isBusy() {
    return busy.get();
  }

  private <T> T exclusively(Supplier<T> job) {
    if (!busy.compareAndSet(false, true)) {
      throw new StressBusyException();
    }
    try {
      return job.get();
    } finally {
      busy.set(false);
    }
  }
}
