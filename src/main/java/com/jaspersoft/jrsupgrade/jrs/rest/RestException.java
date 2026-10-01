package com.jaspersoft.jrsupgrade.jrs.rest;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * A response the caller could not use: a non-2xx status on a call that needed one, or a body that
 * did not parse. Invariants: the message names the method, the path without its query string and
 * the status, never a header; any body excerpt has passed through the client's {@code Redactor} and
 * is capped at {@link #EXCERPT_LIMIT} characters.
 */
public final class RestException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public static final int EXCERPT_LIMIT = 200;

  private final int status;
  private final String method;
  private final String path;
  private Optional<Duration> retryAfter = Optional.empty();

  public RestException(int status, String method, String path, String message) {
    super(message);
    this.status = status;
    this.method = Objects.requireNonNull(method, "method");
    this.path = Objects.requireNonNull(path, "path");
  }

  public RestException(int status, String method, String path, String message, Throwable cause) {
    super(message, cause);
    this.status = status;
    this.method = Objects.requireNonNull(method, "method");
    this.path = Objects.requireNonNull(path, "path");
  }

  /** As above, with the delay a {@code Retry-After} header asked for. */
  public RestException(
      int status, String method, String path, String message, Optional<Duration> retryAfter) {
    super(message);
    this.status = status;
    this.method = Objects.requireNonNull(method, "method");
    this.path = Objects.requireNonNull(path, "path");
    this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
  }

  /**
   * True for the statuses a step may retry without a human (review finding 2.2): 408 request
   * timeout, 429 throttled, 502/503/504 a proxy or a still-deploying Tomcat in front of the server.
   */
  public boolean transientFailure() {
    return status == 408 || status == 429 || status == 502 || status == 503 || status == 504;
  }

  /** The server's {@code Retry-After}, when it sent one. */
  public Optional<Duration> retryAfter() {
    return retryAfter;
  }

  /** HTTP status, or 0 when the failure was not a status. */
  public int status() {
    return status;
  }

  public String method() {
    return method;
  }

  /** Request path without query string. */
  public String path() {
    return path;
  }

  /** True for 401 and 403. */
  public boolean authenticationFailure() {
    return status == 401 || status == 403;
  }
}
