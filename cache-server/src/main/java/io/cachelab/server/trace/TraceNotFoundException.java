package io.cachelab.server.trace;

import java.io.Serial;

/** No trace with the requested id is stored (never uploaded, or evicted); a 404 problem detail. */
public class TraceNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param id the unknown trace id
   */
  public TraceNotFoundException(String id) {
    super("No trace with id '" + id + "' (only the " + TraceStore.MAX_TRACES + " newest are kept)");
  }
}
