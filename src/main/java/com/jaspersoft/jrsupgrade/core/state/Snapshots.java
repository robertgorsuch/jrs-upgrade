package com.jaspersoft.jrsupgrade.core.state;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Snapshots: the snapshot rows a rollback restores from. Invariant: one aggregate of the state
 * store, reached only through {@link StateStore}, which owns the {@link Db} session every method
 * here runs on, so the transaction, the lock and the failure wrapping are the same for every
 * aggregate (roadmap item 16).
 */
final class Snapshots {

  private final Db db;

  Snapshots(Db db) {
    this.db = db;
  }

  void recordSnapshot(SnapshotRecord snapshot) {
    db.write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO snapshots(id, run_id, step_id, path, manifest_sha256, referenced_by)"
                      + " VALUES (?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET"
                      + " referenced_by=excluded.referenced_by")) {
            ps.setString(1, snapshot.id());
            ps.setString(2, snapshot.runId());
            ps.setString(3, snapshot.stepId());
            ps.setString(4, snapshot.path().toString());
            ps.setString(5, snapshot.manifestSha256());
            ps.setString(6, snapshot.referencedBy().orElse(null));
            ps.executeUpdate();
          }
          return null;
        });
  }

  Optional<SnapshotRecord> snapshot(String id) {
    return db.read(
        c -> {
          try (PreparedStatement ps = c.prepareStatement("SELECT * FROM snapshots WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next() ? Optional.of(readSnapshot(rs)) : Optional.empty();
            }
          }
        });
  }

  List<SnapshotRecord> snapshots(String runId) {
    return db.read(
        c -> {
          List<SnapshotRecord> out = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement("SELECT * FROM snapshots WHERE run_id=? ORDER BY id")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                out.add(readSnapshot(rs));
              }
            }
          }
          return List.copyOf(out);
        });
  }

  /** Every recorded snapshot row, ordered by run id then id. */
  List<SnapshotRecord> snapshots() {
    return db.read(
        c -> {
          List<SnapshotRecord> out = new ArrayList<>();
          try (Statement s = c.createStatement();
              ResultSet rs = s.executeQuery("SELECT * FROM snapshots ORDER BY run_id, id")) {
            while (rs.next()) {
              out.add(readSnapshot(rs));
            }
          }
          return List.copyOf(out);
        });
  }

  /**
   * Removes the {@code snapshots} rows of {@code runId/stepId} after retention pruning deleted the
   * directory (spec §5.6); returns how many rows went. The journal ({@code step_transitions}) and
   * the audit trail are untouched, so the run's history still names the snapshot it once had.
   */
  int deleteSnapshot(String runId, String stepId) {
    return db.write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("DELETE FROM snapshots WHERE run_id=? AND step_id=?")) {
            ps.setString(1, runId);
            ps.setString(2, stepId);
            return ps.executeUpdate();
          }
        });
  }

  private static SnapshotRecord readSnapshot(ResultSet rs) throws SQLException {
    return new SnapshotRecord(
        rs.getString("id"),
        rs.getString("run_id"),
        rs.getString("step_id"),
        Path.of(rs.getString("path")),
        rs.getString("manifest_sha256"),
        Db.optString(rs, "referenced_by"));
  }

  /** Removes every snapshot row of one run; the payload on disk is the caller's business. */
  int deleteSnapshotsOf(String runId) {
    return db.write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("DELETE FROM snapshots WHERE run_id = ?")) {
            ps.setString(1, runId);
            return ps.executeUpdate();
          }
        });
  }
}
