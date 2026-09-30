package io.cachelab.server.api;

import io.cachelab.PolicyType;
import io.cachelab.server.trace.InvalidTraceException;
import io.cachelab.server.trace.TraceParser;
import io.cachelab.server.trace.TraceReplay;
import io.cachelab.server.trace.TraceStore;
import io.cachelab.server.trace.TraceTooLargeException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Trace replay (SPEC 9.5): upload or load a trace, then replay it offline through each policy. */
@RestController
@RequestMapping("/api/traces")
public class TraceController {

  /** Maximum upload size in bytes (20 MB); also enforced by {@code spring.servlet.multipart}. */
  public static final long MAX_BYTES = 20L * 1024 * 1024;

  /** Classpath location of the bundled sample trace. */
  public static final String SAMPLE_RESOURCE = "samples/formulary-trace.csv";

  private final TraceStore store;

  /**
   * Creates the controller.
   *
   * @param store keeps the three newest traces
   */
  public TraceController(TraceStore store) {
    this.store = store;
  }

  /**
   * A stored trace.
   *
   * @param traceId the id to replay it with
   * @param rows its number of rows
   */
  public record TraceInfo(String traceId, int rows) {}

  /**
   * Body of {@code POST /api/traces/{id}/replay}.
   *
   * @param capacity simulated cache capacity, 1–1,000,000
   * @param policies policies to compare; null or empty means all three; duplicates are ignored
   */
  public record ReplayRequest(
      @NotNull @Min(1) @Max(1_000_000) Integer capacity, List<PolicyType> policies) {

    List<PolicyType> effectivePolicies() {
      return policies == null || policies.isEmpty()
          ? List.of(PolicyType.values())
          : policies.stream().distinct().toList();
    }
  }

  /**
   * Uploads a trace: {@code key} or {@code timestamp,key} per line, optional header, at most
   * 1,000,000 rows and 20 MB.
   *
   * @param file the multipart field {@code file}
   * @return the new trace's id and row count
   */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public TraceInfo upload(@RequestPart("file") MultipartFile file) {
    if (file.isEmpty()) {
      throw new InvalidTraceException("The uploaded file is empty");
    }
    if (file.getSize() > MAX_BYTES) {
      throw new TraceTooLargeException("The trace is larger than 20 MB");
    }
    try (InputStream in = file.getInputStream()) {
      return store(TraceParser.parse(in));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Loads the bundled sample: 100,000 {@code FORMULARY} lookups generated with seed 7.
   *
   * @return the new trace's id and row count
   */
  @PostMapping("/sample")
  public TraceInfo sample() {
    try (InputStream in = new ClassPathResource(SAMPLE_RESOURCE).getInputStream()) {
      return store(TraceParser.parse(in));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Replays a trace offline and unthrottled through each policy plus the optimal.
   *
   * @param id the trace id
   * @param request capacity and policies
   * @return per-policy results, the optimal hit rate and the replay's duration
   */
  @PostMapping("/{id}/replay")
  public TraceReplay.Result replay(
      @PathVariable String id, @Valid @RequestBody ReplayRequest request) {
    List<String> keys = store.get(id);
    return TraceReplay.replay(id, keys, request.capacity(), request.effectivePolicies());
  }

  private TraceInfo store(List<String> keys) {
    return new TraceInfo(store.put(keys), keys.size());
  }
}
