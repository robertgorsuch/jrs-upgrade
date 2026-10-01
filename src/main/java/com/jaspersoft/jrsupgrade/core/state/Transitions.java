package com.jaspersoft.jrsupgrade.core.state;

import com.jaspersoft.jrsupgrade.core.engine.Transition;
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
 * Transitions: the append-only step journal. Invariant: one aggregate of the state store, reached
 * only through {@link StateStore}, which owns the {@link Db} session every method here runs on, so
 * the transaction, the lock and the failure wrapping are the same for every aggregate (roadmap item
 * 16).
 */
final class Transitions {

  private final Db db;
  private final Clock clock;

  Transitions(Db db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  /** Appends one journal row in its own transaction and returns it with its sequence number. */
  Transition appendTransition(
      String runId,
      String stepId,
      String phase,
      Optional<String> fromState,
      String toState,
      Optional<String> detail) {
    Optional<String> sanitizedDetail = detail.map(Redactor.global()::redact);
    Instant ts = clock.instant();
    long seq =
        db.write(
            c -> {
              try (PreparedStatement ps =
                  c.prepareStatement(
                      "INSERT INTO step_transitions(ts, run_id, step_id, phase, from_state,"
                          + " to_state, detail) VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, Timestamps.encode(ts));
                ps.setString(2, runId);
                ps.setString(3, stepId);
                ps.setString(4, phase);
                ps.setString(5, fromState.orElse(null));
                ps.setString(6, toState);
                ps.setString(7, sanitizedDetail.orElse(null));
                ps.executeUpdate();
              }
              try (Statement s = c.createStatement();
                  ResultSet rs = s.executeQuery("SELECT last_insert_rowid()")) {
                rs.next();
                return rs.getLong(1);
              }
            });
    return new Transition(seq, ts, runId, stepId, phase, fromState, toState, sanitizedDetail);
  }

  /** The journal of one run in write order. */
  List<Transition> transitions(String runId) {
    return db.read(
        c -> {
          List<Transition> out = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement("SELECT * FROM step_transitions WHERE run_id=? ORDER BY seq")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                out.add(
                    new Transition(
                        rs.getLong("seq"),
                        Db.instant(rs, "ts"),
                        rs.getString("run_id"),
                        rs.getString("step_id"),
                        rs.getString("phase"),
                        Db.optString(rs, "from_state"),
                        rs.getString("to_state"),
                        Db.optString(rs, "detail")));
              }
            }
          }
          return List.copyOf(out);
        });
  }
}
