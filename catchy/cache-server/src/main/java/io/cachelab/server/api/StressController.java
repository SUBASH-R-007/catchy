package io.cachelab.server.api;

import io.cachelab.PolicyType;
import io.cachelab.diagnostics.StampedeResult;
import io.cachelab.diagnostics.StressConfig;
import io.cachelab.diagnostics.StressReport;
import io.cachelab.server.stress.StressBusyException;
import io.cachelab.server.stress.StressService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Live thread-safety proof for the Concurrency Lab (SPEC 5, 8.2): stress runs and the stampede. */
@RestController
@RequestMapping("/api/stress")
public class StressController {

  private final StressService service;

  /**
   * Creates the controller.
   *
   * @param service runs one job at a time
   */
  public StressController(StressService service) {
    this.service = service;
  }

  /**
   * Body of {@code POST /api/stress}; omitted fields take the documented defaults.
   *
   * @param impl engine: SINGLE_LOCK (default) or SEGMENTED
   * @param threads worker threads, 1–64 (default 32)
   * @param durationMs run time, 100–10,000 ms (default 5,000)
   * @param keySpace distinct keys (default 10,000)
   * @param readRatio share of gets, 0–1 (default 0.8)
   * @param capacity cache size (default 1,000)
   * @param policy eviction policy (default LRU)
   * @param seed base seed (default 42)
   */
  public record StressRequest(
      StressConfig.Impl impl,
      @Min(1) @Max(64) Integer threads,
      @Min(100) @Max(10_000) Integer durationMs,
      @Min(1) @Max(1_000_000) Integer keySpace,
      @DecimalMin("0") @DecimalMax("1") Double readRatio,
      @Min(1) @Max(1_000_000) Integer capacity,
      PolicyType policy,
      Long seed) {

    StressConfig toConfig() {
      return new StressConfig(
          impl == null ? StressConfig.Impl.SINGLE_LOCK : impl,
          threads == null ? 32 : threads,
          Duration.ofMillis(durationMs == null ? 5_000 : durationMs),
          keySpace == null ? 10_000 : keySpace,
          readRatio == null ? 0.8 : readRatio,
          capacity == null ? 1_000 : capacity,
          policy == null ? PolicyType.LRU : policy,
          seed == null ? 42 : seed);
    }
  }

  /**
   * Body of {@code POST /api/stress/stampede}.
   *
   * @param threads concurrent callers, 1–500 (default 200)
   * @param loaderDelayMs loader duration, 0–2,000 ms (default 200)
   */
  public record StampedeRequest(
      @Min(1) @Max(500) Integer threads, @Min(0) @Max(2_000) Integer loaderDelayMs) {}

  /**
   * Runs a stress test; blocks for its duration.
   *
   * @param request the run (may be empty)
   * @return the report with the five invariant results
   */
  @PostMapping
  public StressReport stress(@Valid @RequestBody(required = false) StressRequest request) {
    StressRequest r =
        request == null
            ? new StressRequest(null, null, null, null, null, null, null, null)
            : request;
    return service.stress(r.toConfig());
  }

  /**
   * Runs the stampede test.
   *
   * @param request threads and loader delay (may be empty)
   * @return the result
   */
  @PostMapping("/stampede")
  public StampedeResult stampede(@Valid @RequestBody(required = false) StampedeRequest request) {
    int threads = request == null || request.threads() == null ? 200 : request.threads();
    int delayMs =
        request == null || request.loaderDelayMs() == null ? 200 : request.loaderDelayMs();
    return service.stampede(threads, Duration.ofMillis(delayMs));
  }

  /**
   * A job is already running: 409.
   *
   * @param e the exception
   * @return the problem detail
   */
  @ExceptionHandler(StressBusyException.class)
  public ProblemDetail busy(StressBusyException e) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    problem.setTitle("Stress test busy");
    return problem;
  }
}
