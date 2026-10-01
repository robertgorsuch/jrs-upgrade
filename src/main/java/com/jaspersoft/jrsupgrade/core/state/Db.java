package com.jaspersoft.jrsupgrade.core.state;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one SQLite session every aggregate of the state store runs on. Invariants: a single
 * connection guarded by one monitor, so the aggregates are thread-safe without each knowing it;
 * every write happens inside a {@code BEGIN IMMEDIATE ... COMMIT} and rolls back on any failure;
 * {@code SQLException} never escapes, it becomes a {@link StateStoreException} naming whether a
 * read or a write failed; {@link #close()} never throws, because it runs after a run's outcome is
 * journaled and a failure to close must not turn a finished run into an error. This class holds no
 * domain knowledge at all: it is the transaction, the lock and the error contract, and nothing else
 * (roadmap item 16).
 */
final class Db {

  private static final Logger LOG = LoggerFactory.getLogger(Db.class);

  private final Connection conn;
  private final Path file;
  private final Object mutex = new Object();

  Db(Connection conn, Path file) {
    this.conn = conn;
    this.file = file;
  }

  Path file() {
    return file;
  }

  /** One unit of SQL against the open connection. */
  @FunctionalInterface
  interface SqlWork<T> {
    T apply(Connection c) throws SQLException;
  }

  <T> T read(SqlWork<T> work) {
    synchronized (mutex) {
      try {
        return work.apply(conn);
      } catch (SQLException e) {
        throw new StateStoreException("state store read failed: " + e.getMessage(), e);
      }
    }
  }

  <T> T write(SqlWork<T> work) {
    synchronized (mutex) {
      try {
        try (Statement s = conn.createStatement()) {
          s.execute("BEGIN IMMEDIATE");
        }
        T result;
        try {
          result = work.apply(conn);
          try (Statement s = conn.createStatement()) {
            s.execute("COMMIT");
          }
        } catch (SQLException | RuntimeException e) {
          try (Statement s = conn.createStatement()) {
            s.execute("ROLLBACK");
          } catch (SQLException rollbackFailure) {
            e.addSuppressed(rollbackFailure);
          }
          throw e;
        }
        return result;
      } catch (SQLException e) {
        throw new StateStoreException("state store write failed: " + e.getMessage(), e);
      }
    }
  }

  void close() {
    synchronized (mutex) {
      try {
        conn.close();
      } catch (SQLException | RuntimeException e) {
        LOG.warn("state store {} did not close cleanly: {}", file, e.getMessage());
      }
    }
  }

  static Optional<String> optString(ResultSet rs, String column) throws SQLException {
    return Optional.ofNullable(rs.getString(column));
  }

  static Instant instant(ResultSet rs, String column) throws SQLException {
    return Timestamps.decode(rs.getString(column));
  }
}
