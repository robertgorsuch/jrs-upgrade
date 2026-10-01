package com.jaspersoft.jrsupgrade.core.state;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/**
 * Plans: stored plans and their time to live. Invariant: one aggregate of the state store, reached
 * only through {@link StateStore}, which owns the {@link Db} session every method here runs on, so
 * the transaction, the lock and the failure wrapping are the same for every aggregate (roadmap item
 * 16).
 */
final class Plans {

  private final Db db;

  Plans(Db db) {
    this.db = db;
  }

  void savePlan(StoredPlan plan) {
    db.write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO plans(plan_id, operation, args_json, plan_json, fingerprint,"
                      + " created_at, expires_at, consumed_by_run_id) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, plan.planId());
            ps.setString(2, plan.operation());
            ps.setString(3, plan.argsJson());
            ps.setString(4, plan.planJson());
            ps.setString(5, plan.fingerprint());
            ps.setString(6, Timestamps.encode(plan.createdAt()));
            ps.setString(7, Timestamps.encode(plan.expiresAt()));
            ps.setString(8, plan.consumedByRunId().orElse(null));
            ps.executeUpdate();
          }
          return null;
        });
  }

  Optional<StoredPlan> loadPlan(String planId) {
    return db.read(
        c -> {
          try (PreparedStatement ps = c.prepareStatement("SELECT * FROM plans WHERE plan_id=?")) {
            ps.setString(1, planId);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next() ? Optional.of(readPlan(rs)) : Optional.empty();
            }
          }
        });
  }

  /**
   * Claims an unexpired, unconsumed plan for {@code runId}; false when it is unknown, expired or
   * already consumed, so a plan can never execute twice.
   */
  boolean consumePlan(String planId, String runId, Instant now) {
    return db.write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "UPDATE plans SET consumed_by_run_id=? WHERE plan_id=?"
                      + " AND consumed_by_run_id IS NULL AND expires_at > ?")) {
            ps.setString(1, runId);
            ps.setString(2, planId);
            ps.setString(3, Timestamps.encode(now));
            return ps.executeUpdate() == 1;
          }
        });
  }

  /** Deletes unconsumed plans whose TTL elapsed; returns how many were removed. */
  int expirePlans(Instant now) {
    return db.write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "DELETE FROM plans WHERE consumed_by_run_id IS NULL AND expires_at <= ?")) {
            ps.setString(1, Timestamps.encode(now));
            return ps.executeUpdate();
          }
        });
  }

  private static StoredPlan readPlan(ResultSet rs) throws SQLException {
    return new StoredPlan(
        rs.getString("plan_id"),
        rs.getString("operation"),
        rs.getString("args_json"),
        rs.getString("plan_json"),
        rs.getString("fingerprint"),
        Db.instant(rs, "created_at"),
        Db.instant(rs, "expires_at"),
        Db.optString(rs, "consumed_by_run_id"));
  }
}
