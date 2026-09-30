package io.cachelab.server.cache;

import java.io.Serial;

/** The group's advisor has no recommendation to apply; rendered as a 409 problem detail. */
public class NoRecommendationException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param group the group name
   */
  public NoRecommendationException(String group) {
    super("The advisor of group '" + group + "' has no recommendation to apply");
  }
}
