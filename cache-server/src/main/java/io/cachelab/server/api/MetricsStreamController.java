package io.cachelab.server.api;

import io.cachelab.server.metrics.MetricsPublisher;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Serves the metrics stream: one {@code metrics} event every 500 ms (SPEC 8.3). */
@RestController
@RequestMapping("/api/metrics")
public class MetricsStreamController {

  private final MetricsPublisher publisher;

  /**
   * Creates the controller.
   *
   * @param publisher the publisher that owns the stream
   */
  public MetricsStreamController(MetricsPublisher publisher) {
    this.publisher = publisher;
  }

  /**
   * Opens a stream for this client. The connection stays open until the client leaves or the server
   * stops.
   *
   * @return an emitter that receives every tick as JSON
   */
  @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream() {
    return publisher.register();
  }
}
