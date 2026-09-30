package io.cachelab.server.cache;

import java.io.Serial;

/** A cache with the requested name already exists; rendered as a 409 problem detail. */
public class CacheAlreadyExistsException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param name the duplicate cache name
   */
  public CacheAlreadyExistsException(String name) {
    super("A cache named '" + name + "' already exists");
  }
}
