package io.cachelab.server.metrics;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * SSE lifecycle (SPEC 8.4) over a real socket: connect, receive at least two {@code metrics}
 * events, disconnect, and the emitter count returns to 0.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MetricsStreamLifecycleTest {

  private static final Duration READ_DEADLINE = Duration.ofSeconds(15);
  private static final Pattern EVENT_LINE = Pattern.compile("(?m)^event: ?metrics\\r?$");

  @LocalServerPort private int port;

  @Autowired private MetricsPublisher publisher;

  @Test
  void clientReceivesEventsAndItsEmitterIsRemovedAfterDisconnect() throws IOException {
    assertThat(publisher.emitterCount()).isZero();

    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("localhost", port), 5_000);
      socket.setSoTimeout(1_000);
      sendRequest(socket.getOutputStream());

      StreamReader reader = new StreamReader(socket.getInputStream());
      String head = reader.readUntil("\r\n\r\n");
      assertThat(head).startsWith("HTTP/1.1 200");
      assertThat(head.toLowerCase(Locale.ROOT)).contains("content-type: text/event-stream");

      reader.readUntilEvents(2);
      assertThat(publisher.emitterCount()).isEqualTo(1);
    }

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(publisher.emitterCount()).isZero());
  }

  private void sendRequest(OutputStream out) throws IOException {
    String request =
        "GET /api/metrics/stream HTTP/1.1\r\n"
            + "Host: localhost:"
            + port
            + "\r\n"
            + "Accept: text/event-stream\r\n"
            + "\r\n";
    out.write(request.getBytes(US_ASCII));
    out.flush();
  }

  /** Accumulates the raw response (headers, chunk framing and all) until a condition holds. */
  private static final class StreamReader {
    private final InputStream in;
    private final StringBuilder text = new StringBuilder();
    private final byte[] buffer = new byte[8192];
    private final long deadlineNanos = System.nanoTime() + READ_DEADLINE.toNanos();

    StreamReader(InputStream in) {
      this.in = in;
    }

    /** Reads until {@code marker} arrives and returns everything up to it. */
    String readUntil(String marker) throws IOException {
      while (text.indexOf(marker) < 0) {
        readSome();
      }
      return text.substring(0, text.indexOf(marker));
    }

    /** Reads until at least {@code count} lines {@code event:metrics} have arrived. */
    void readUntilEvents(int count) throws IOException {
      while (countEvents() < count) {
        readSome();
      }
    }

    private int countEvents() {
      Matcher matcher = EVENT_LINE.matcher(text);
      int count = 0;
      while (matcher.find()) {
        count++;
      }
      return count;
    }

    private void readSome() throws IOException {
      while (true) {
        if (System.nanoTime() > deadlineNanos) {
          throw new AssertionError("timed out; received so far:\n" + text);
        }
        try {
          int read = in.read(buffer);
          if (read < 0) {
            throw new AssertionError("server closed the stream; received:\n" + text);
          }
          text.append(new String(buffer, 0, read, ISO_8859_1));
          return;
        } catch (SocketTimeoutException retry) {
          // no data within the socket timeout: check the deadline and keep waiting
        }
      }
    }
  }
}
