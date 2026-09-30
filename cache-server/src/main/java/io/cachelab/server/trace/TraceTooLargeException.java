package io.cachelab.server.trace;

import java.io.Serial;

/** An uploaded trace exceeds the size or row limit; rendered as a 413 problem detail. */
public class TraceTooLargeException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message which limit was exceeded
   */
  public TraceTooLargeException(String message) {
    super(message);
  }
}
