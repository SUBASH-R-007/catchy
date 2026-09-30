package io.cachelab.server.stress;

import java.io.Serial;

/** A diagnostics job is already running; rendered as a 409 problem detail. */
public class StressBusyException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public StressBusyException() {
    super("A stress or stampede test is already running; try again when it finishes");
  }
}
