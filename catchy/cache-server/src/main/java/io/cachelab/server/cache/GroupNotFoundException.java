package io.cachelab.server.cache;

import java.io.Serial;

/** No cache belongs to the requested group; rendered as a 404 problem detail. */
public class GroupNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param group the unknown group name
   */
  public GroupNotFoundException(String group) {
    super("No group named '" + group + "'");
  }
}
