package com.jaspersoft.jrsupgrade.ops.db;

import java.util.Objects;

/**
 * Checked failure of a {@link JdbcConnector} operation. Invariants: {@link #kind()} classifies the
 * failure so callers can choose a remediation without parsing the message; the message never
 * carries a password (only the URL and the driver's own text).
 */
public final class JdbcException extends Exception {

  private static final long serialVersionUID = 1L;

  /** What went wrong, from the operator's point of view. */
  public enum Kind {
    /** The driver directory holds no {@code *.jar}. */
    NO_DRIVER_JARS,
    /** No {@code java.sql.Driver} in the jars accepts the URL. */
    NO_MATCHING_DRIVER,
    /** The driver refused the connection. */
    CONNECT_FAILED,
    /** A statement failed. */
    SQL_FAILED,
    /** The driver directory or a script could not be read. */
    IO
  }

  private final Kind kind;

  public JdbcException(Kind kind, String message) {
    super(message);
    this.kind = Objects.requireNonNull(kind, "kind");
  }

  public JdbcException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = Objects.requireNonNull(kind, "kind");
  }

  public Kind kind() {
    return kind;
  }
}
