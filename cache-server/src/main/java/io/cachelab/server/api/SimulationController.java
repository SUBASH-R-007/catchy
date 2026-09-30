package io.cachelab.server.api;

import io.cachelab.server.cache.CacheConfig;
import io.cachelab.server.simulation.SimulationPlan;
import io.cachelab.server.simulation.SimulationService;
import io.cachelab.server.workload.KeyStreams;
import io.cachelab.server.workload.Pattern;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The workload API (SPEC 8.2, 9.2): start and stop simulations. */
@RestController
@RequestMapping("/api/simulations")
public class SimulationController {

  private final SimulationService simulations;

  /**
   * Creates the controller.
   *
   * @param simulations the simulation runner
   */
  public SimulationController(SimulationService simulations) {
    this.simulations = simulations;
  }

  /**
   * Body of {@code POST /api/simulations}; omitted optional fields get the documented defaults.
   *
   * @param group the group to drive
   * @param pattern the workload pattern
   * @param opsPerSec operations per second, 0–200,000 (0 = unthrottled); default 5,000
   * @param readRatio read probability, 0–1; default 0.9
   * @param durationSec run length, 1–3,600 s; default 60
   * @param seed random seed; default 42
   * @param params pattern parameter overrides (see {@link KeyStreams}), or null
   */
  public record SimulationRequest(
      @NotNull
          @jakarta.validation.constraints.Pattern(
              regexp = CacheConfig.NAME_PATTERN,
              message = "must be 1-40 letters, digits, _ or -")
          String group,
      @NotNull Pattern pattern,
      @Min(0) @Max(200_000) Integer opsPerSec,
      @DecimalMin("0.0") @DecimalMax("1.0") Double readRatio,
      @Min(1) @Max(3600) Integer durationSec,
      Long seed,
      Map<String, Object> params) {

    /** Fills in the documented defaults. */
    public SimulationRequest {
      opsPerSec = opsPerSec == null ? 5_000 : opsPerSec;
      readRatio = readRatio == null ? 0.9 : readRatio;
      durationSec = durationSec == null ? 60 : durationSec;
      seed = seed == null ? 42L : seed;
    }

    SimulationPlan toPlan() {
      return SimulationPlan.single(
          group,
          pattern,
          opsPerSec,
          readRatio,
          durationSec,
          seed,
          params,
          KeyStreams.describe(pattern));
    }
  }

  /**
   * Result of {@code POST /api/simulations}.
   *
   * @param id the new simulation's id
   */
  public record SimulationStarted(String id) {}

  /**
   * Starts a one-phase simulation, stopping any running one.
   *
   * @param request the workload
   * @return the new id
   */
  @PostMapping
  public SimulationStarted start(@Valid @RequestBody SimulationRequest request) {
    return new SimulationStarted(simulations.start(request.toPlan()));
  }

  /**
   * Stops a simulation (a no-op if it already ended).
   *
   * @param id the simulation id
   * @return 204
   */
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> stop(@PathVariable String id) {
    simulations.stop(id);
    return ResponseEntity.noContent().build();
  }
}
