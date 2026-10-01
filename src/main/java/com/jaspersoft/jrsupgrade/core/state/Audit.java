package com.jaspersoft.jrsupgrade.core.state;

import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Audit: the append-only audit trail. Invariant: one aggregate of the state store, reached only
 * through {@link StateStore}, which owns the {@link Db} session every method here runs on, so the
 * transaction, the lock and the failure wrapping are the same for every aggregate (roadmap item
 * 16).
 */
final class Audit {

  private final Db db;
  private final Clock clock;

  Audit(Db db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  AuditEntry audit(String actor, String action, String detail) {
    String sanitizedDetail = detail == null ? null : Redactor.global().redact(detail);
    Instant ts = clock.instant();
    long seq =
        db.write(
            c -> {
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "INSERT INTO audit(ts, actor, action, detail) VALUES (?,?,?,?)")) {
                ps.setString(1, Timestamps.encode(ts));
                ps.setString(2, actor);
                ps.setString(3, action);
                ps.setString(4, sanitizedDetail);
                ps.executeUpdate();
              }
              try (Statement s = c.createStatement();
                  ResultSet rs = s.executeQuery("SELECT last_insert_rowid()")) {
                rs.next();
                return rs.getLong(1);
              }
            });
    return new AuditEntry(seq, ts, actor, action, Optional.ofNullable(sanitizedDetail));
  }

  /** Most recent audit rows first. */
  List<AuditEntry> auditRows(int limit) {
    return db.read(
        c -> {
          List<AuditEntry> out = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement("SELECT * FROM audit ORDER BY seq DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                out.add(
                    new AuditEntry(
                        rs.getLong("seq"),
                        Db.instant(rs, "ts"),
                        rs.getString("actor"),
                        rs.getString("action"),
                        Db.optString(rs, "detail")));
              }
            }
          }
          return List.copyOf(out);
        });
  }
}
