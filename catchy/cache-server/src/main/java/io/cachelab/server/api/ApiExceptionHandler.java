package io.cachelab.server.api;

import io.cachelab.server.cache.CacheAlreadyExistsException;
import io.cachelab.server.cache.CacheNotFoundException;
import io.cachelab.server.cache.GroupNotFoundException;
import io.cachelab.server.simulation.SimulationNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain exceptions to RFC 7807 problem details (SPEC 8.1). Framework errors (validation,
 * malformed JSON, unknown routes) are already rendered as problem details by Spring.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  /**
   * Unknown cache: 404.
   *
   * @param e the exception
   * @return the problem detail
   */
  @ExceptionHandler(CacheNotFoundException.class)
  public ProblemDetail notFound(CacheNotFoundException e) {
    return problem(HttpStatus.NOT_FOUND, "Cache not found", e.getMessage());
  }

  /**
   * Unknown group: 404.
   *
   * @param e the exception
   * @return the problem detail
   */
  @ExceptionHandler(GroupNotFoundException.class)
  public ProblemDetail groupNotFound(GroupNotFoundException e) {
    return problem(HttpStatus.NOT_FOUND, "Group not found", e.getMessage());
  }

  /**
   * Unknown simulation id: 404.
   *
   * @param e the exception
   * @return the problem detail
   */
  @ExceptionHandler(SimulationNotFoundException.class)
  public ProblemDetail simulationNotFound(SimulationNotFoundException e) {
    return problem(HttpStatus.NOT_FOUND, "Simulation not found", e.getMessage());
  }

  /**
   * Duplicate cache name: 409.
   *
   * @param e the exception
   * @return the problem detail
   */
  @ExceptionHandler(CacheAlreadyExistsException.class)
  public ProblemDetail conflict(CacheAlreadyExistsException e) {
    return problem(HttpStatus.CONFLICT, "Cache already exists", e.getMessage());
  }

  /**
   * The cache library rejected an argument or configuration (for example a concurrency level that
   * is not a power of two): 400, with the library's own message.
   *
   * @param e the exception
   * @return the problem detail
   */
  @ExceptionHandler({
    IllegalArgumentException.class,
    IllegalStateException.class,
    UnsupportedOperationException.class
  })
  public ProblemDetail badRequest(RuntimeException e) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
  }

  private static ProblemDetail problem(HttpStatus status, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    return problem;
  }
}
