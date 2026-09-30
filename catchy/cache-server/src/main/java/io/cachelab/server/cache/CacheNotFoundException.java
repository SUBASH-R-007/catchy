package io.cachelab.server.cache;

import java.io.Serial;

/** No cache has the requested name; rendered as a 404 problem detail. */
public class CacheNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param name the unknown cache name
   */
  public CacheNotFoundException(String name) {
    super("No cache named '" + name + "'");
  }
}
