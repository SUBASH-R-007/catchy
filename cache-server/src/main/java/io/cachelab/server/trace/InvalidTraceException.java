package io.cachelab.server.trace;

import java.io.Serial;

/** An uploaded trace is empty or malformed; rendered as a 400 problem detail. */
public class InvalidTraceException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what is wrong, for a human reader
   */
  public InvalidTraceException(String message) {
    super(message);
  }
}
