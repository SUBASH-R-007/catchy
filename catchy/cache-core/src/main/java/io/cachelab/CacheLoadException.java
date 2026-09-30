package io.cachelab;

import java.io.Serial;

/**
 * Thrown by {@link Cache#getOrLoad(Object, java.util.function.Function)} when the loader fails or
 * returns null. Every caller waiting on the same in-flight load receives this exception; the
 * loader's original exception is available through {@link #getCause()}.
 *
 * <p>Thread-safety: instances are effectively immutable once thrown.
 */
public class CacheLoadException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates an exception with a message and the loader's original failure.
   *
   * @param message the detail message; may be null
   * @param cause the loader's failure; may be null when the loader returned null
   */
  public CacheLoadException(String message, Throwable cause) {
    super(message, cause);
  }
}
