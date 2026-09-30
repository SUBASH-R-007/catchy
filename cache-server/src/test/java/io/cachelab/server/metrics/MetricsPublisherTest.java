package io.cachelab.server.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Unit tests of {@link MetricsPublisher} without a servlet container. */
class MetricsPublisherTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicReference<Thread> tickThread = new AtomicReference<>();
  private final MetricsSource source =
      () -> {
        tickThread.set(Thread.currentThread());
        long ts = 1_000L + calls.incrementAndGet();
        return new MetricsSnapshot(ts, List.of(), List.of(), null, List.of());
      };
  private final MetricsPublisher publisher = new MetricsPublisher(source, objectMapper);

  @AfterEach
  void stopPublisher() {
    publisher.stop();
  }

  @Test
  void tickBuildsThePayloadOnceAndSendsItToEveryEmitter() throws IOException {
    RecordingEmitter first = new RecordingEmitter();
    RecordingEmitter second = new RecordingEmitter();
    publisher.register(first);
    publisher.register(second);

    publisher.tick();

    assertThat(calls).hasValue(1);
    String expected = objectMapper.writeValueAsString(snapshotWithTs(1_001L));
    assertThat(first.events).containsExactly("event:metrics\ndata:" + expected + "\n\n");
    assertThat(second.events).containsExactly(first.events.get(0));
  }

  @Test
  void registerSendsTheLatestPayloadImmediately() {
    RecordingEmitter early = new RecordingEmitter();
    publisher.register(early);
    assertThat(early.events).as("nothing to send before the first tick").isEmpty();

    publisher.tick();
    RecordingEmitter late = new RecordingEmitter();
    publisher.register(late);

    assertThat(late.events).hasSize(1).isEqualTo(early.events);
    assertThat(publisher.emitterCount()).isEqualTo(2);
  }

  @Test
  void failedSendRemovesOnlyThatEmitter() {
    RecordingEmitter healthy = new RecordingEmitter();
    publisher.register(new FailingEmitter());
    publisher.register(healthy);
    assertThat(publisher.emitterCount()).isEqualTo(2);

    publisher.tick();
    publisher.tick();

    assertThat(publisher.emitterCount()).isEqualTo(1);
    assertThat(healthy.events).hasSize(2);
  }

  @Test
  void failingSourceDoesNotStopTheStream() {
    AtomicInteger attempts = new AtomicInteger();
    MetricsSource flaky =
        () -> {
          if (attempts.incrementAndGet() == 1) {
            throw new IllegalStateException("boom");
          }
          return snapshotWithTs(attempts.get());
        };
    MetricsPublisher flakyPublisher = new MetricsPublisher(flaky, objectMapper);
    RecordingEmitter emitter = new RecordingEmitter();
    flakyPublisher.register(emitter);

    flakyPublisher.tick();
    flakyPublisher.tick();

    assertThat(emitter.events).hasSize(1);
  }

  @Test
  void startTicksOnOneDaemonThreadAndStopCompletesEmitters() {
    RecordingEmitter emitter = new RecordingEmitter();
    publisher.register(emitter);

    publisher.start();
    assertThat(publisher.isRunning()).isTrue();
    await().atMost(Duration.ofSeconds(5)).until(() -> emitter.events.size() >= 2);
    assertThat(tickThread.get().getName()).isEqualTo(MetricsPublisher.THREAD_NAME);
    assertThat(tickThread.get().isDaemon()).isTrue();

    publisher.stop();
    assertThat(publisher.isRunning()).isFalse();
    assertThat(publisher.emitterCount()).isZero();
  }

  private static MetricsSnapshot snapshotWithTs(long ts) {
    return new MetricsSnapshot(ts, List.of(), List.of(), null, List.of());
  }

  /** Records each event as the exact text Spring would write for it. */
  private static final class RecordingEmitter extends SseEmitter {
    private final List<String> events = new CopyOnWriteArrayList<>();

    RecordingEmitter() {
      super(0L);
    }

    @Override
    public void send(SseEventBuilder builder) {
      StringBuilder text = new StringBuilder();
      for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
        text.append(part.getData());
      }
      events.add(text.toString());
    }
  }

  /** Simulates a client that went away: every send fails. */
  private static final class FailingEmitter extends SseEmitter {
    FailingEmitter() {
      super(0L);
    }

    @Override
    public void send(SseEventBuilder builder) throws IOException {
      throw new IOException("client disconnected");
    }
  }
}
