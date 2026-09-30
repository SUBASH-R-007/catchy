package io.cachelab.server.simulation;

import java.io.Serial;

/** No simulation with the requested id was ever started; rendered as a 404 problem detail. */
public class SimulationNotFoundException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param id the unknown simulation id
   */
  public SimulationNotFoundException(String id) {
    super("No simulation with id '" + id + "'");
  }
}
