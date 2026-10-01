package com.jaspersoft.jrsupgrade.core.state;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Customizations: the files an operator registered as their own. Invariant: one aggregate of the
 * state store, reached only through {@link StateStore}, which owns the {@link Db} session every
 * method here runs on, so the transaction, the lock and the failure wrapping are the same for every
 * aggregate (roadmap item 16).
 */
final class Customizations {

  private final Db db;

  Customizations(Db db) {
    this.db = db;
  }

  void registerCustomization(Customization customization) {
    db.write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("DELETE FROM customizations WHERE path_key=? AND path<>?")) {
            ps.setString(1, PathKeys.key(customization.path()));
            ps.setString(2, customization.path().toString());
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO customizations(path, original_sha256, snapshot_ref, registered_at,"
                      + " path_key) VALUES (?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET"
                      + " original_sha256=excluded.original_sha256,"
                      + " snapshot_ref=excluded.snapshot_ref,"
                      + " registered_at=excluded.registered_at,"
                      + " path_key=excluded.path_key")) {
            ps.setString(1, customization.path().toString());
            ps.setString(2, customization.originalSha256());
            ps.setString(3, customization.snapshotRef().orElse(null));
            ps.setString(4, Timestamps.encode(customization.registeredAt()));
            ps.setString(5, PathKeys.key(customization.path()));
            ps.executeUpdate();
          }
          return null;
        });
  }

  boolean unregisterCustomization(Path path) {
    return db.write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("DELETE FROM customizations WHERE path_key=?")) {
            ps.setString(1, PathKeys.key(path));
            return ps.executeUpdate() > 0;
          }
        });
  }

  List<Customization> customizations() {
    return db.read(
        c -> {
          List<Customization> out = new ArrayList<>();
          try (Statement s = c.createStatement();
              ResultSet rs = s.executeQuery("SELECT * FROM customizations ORDER BY path")) {
            while (rs.next()) {
              out.add(
                  new Customization(
                      Path.of(rs.getString("path")),
                      rs.getString("original_sha256"),
                      Db.optString(rs, "snapshot_ref"),
                      Db.instant(rs, "registered_at")));
            }
          }
          return List.copyOf(out);
        });
  }
}
