package com.jaspersoft.jrsupgrade.core.state;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Hotfixes: installed hotfixes and the files each one owns. Invariant: one aggregate of the state
 * store, reached only through {@link StateStore}, which owns the {@link Db} session every method
 * here runs on, so the transaction, the lock and the failure wrapping are the same for every
 * aggregate (roadmap item 16).
 */
final class Hotfixes {

  private final Db db;

  Hotfixes(Db db) {
    this.db = db;
  }

  /** Records an installed hotfix and its files in one transaction. */
  void recordHotfixInstalled(HotfixInstalled hotfix, List<HotfixFile> files) {
    db.write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO hotfixes_installed(id, version, title, installed_run_id,"
                      + " snapshot_ref, state, installed_at, origin) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, hotfix.id());
            ps.setString(2, hotfix.version());
            ps.setString(3, hotfix.title());
            ps.setString(4, hotfix.installedRunId());
            ps.setString(5, hotfix.snapshotRef().orElse(null));
            ps.setString(6, hotfix.state().name());
            ps.setString(7, Timestamps.encode(hotfix.installedAt()));
            ps.setString(8, hotfix.origin().name());
            ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO hotfix_files(hotfix_id, path, action, before_sha256, after_sha256,"
                      + " path_key) VALUES (?,?,?,?,?,?)")) {
            for (HotfixFile f : files) {
              ps.setString(1, hotfix.id());
              ps.setString(2, f.path().toString());
              ps.setString(3, f.action());
              ps.setString(4, f.beforeSha256().orElse(null));
              ps.setString(5, f.afterSha256().orElse(null));
              ps.setString(6, PathKeys.key(f.path()));
              ps.addBatch();
            }
            ps.executeBatch();
          }
          return null;
        });
  }

  void updateHotfixState(String hotfixId, HotfixState state) {
    int rows =
        db.write(
            c -> {
              try (PreparedStatement ps =
                  c.prepareStatement("UPDATE hotfixes_installed SET state=? WHERE id=?")) {
                ps.setString(1, state.name());
                ps.setString(2, hotfixId);
                return ps.executeUpdate();
              }
            });
    if (rows == 0) {
      throw new StateStoreException("unknown hotfix " + hotfixId);
    }
  }

  /** Hotfixes currently in state {@code INSTALLED}, oldest first (the LIFO order for rollback). */
  List<HotfixInstalled> installedHotfixes() {
    return db.read(
        c ->
            queryHotfixes(
                c,
                "SELECT * FROM hotfixes_installed WHERE state='INSTALLED'"
                    + " ORDER BY installed_at, id"));
  }

  /** Every hotfix ever recorded, in any state, oldest first. */
  List<HotfixInstalled> hotfixes() {
    return db.read(
        c -> queryHotfixes(c, "SELECT * FROM hotfixes_installed ORDER BY installed_at, id"));
  }

  Optional<HotfixInstalled> hotfix(String id) {
    return db.read(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement("SELECT * FROM hotfixes_installed WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
              return rs.next() ? Optional.of(readHotfix(rs)) : Optional.empty();
            }
          }
        });
  }

  private static List<HotfixInstalled> queryHotfixes(Connection c, String sql) throws SQLException {
    List<HotfixInstalled> out = new ArrayList<>();
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      while (rs.next()) {
        out.add(readHotfix(rs));
      }
    }
    return List.copyOf(out);
  }

  private static HotfixInstalled readHotfix(ResultSet rs) throws SQLException {
    return new HotfixInstalled(
        rs.getString("id"),
        rs.getString("version"),
        rs.getString("title"),
        rs.getString("installed_run_id"),
        Db.optString(rs, "snapshot_ref"),
        HotfixState.valueOf(rs.getString("state")),
        Db.instant(rs, "installed_at"),
        HotfixInstalled.Origin.valueOf(rs.getString("origin")));
  }

  List<HotfixFile> hotfixFiles(String hotfixId) {
    return db.read(
        c -> {
          List<HotfixFile> out = new ArrayList<>();
          try (PreparedStatement ps =
              c.prepareStatement("SELECT * FROM hotfix_files WHERE hotfix_id=? ORDER BY path")) {
            ps.setString(1, hotfixId);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                out.add(readHotfixFile(rs));
              }
            }
          }
          return List.copyOf(out);
        });
  }

  /**
   * Files among {@code paths} that belong to a hotfix in state {@code INSTALLED}; used for the
   * overlap check (spec §8.4). Paths are compared by their string form.
   */
  List<HotfixFile> filesOwnedBy(Collection<Path> paths) {
    List<String> wanted =
        new ArrayList<>(new LinkedHashSet<>(paths.stream().map(PathKeys::key).toList()));
    if (wanted.isEmpty()) {
      return List.of();
    }
    return db.read(
        c -> {
          List<HotfixFile> out = new ArrayList<>();
          int chunk = 500;
          for (int from = 0; from < wanted.size(); from += chunk) {
            List<String> slice = wanted.subList(from, Math.min(wanted.size(), from + chunk));
            String placeholders =
                String.join(",", java.util.Collections.nCopies(slice.size(), "?"));
            try (PreparedStatement ps =
                c.prepareStatement(
                    "SELECT f.* FROM hotfix_files f JOIN hotfixes_installed h ON h.id=f.hotfix_id"
                        + " WHERE h.state='INSTALLED' AND f.path_key IN ("
                        + placeholders
                        + ") ORDER BY h.installed_at, f.path")) {
              for (int i = 0; i < slice.size(); i++) {
                ps.setString(i + 1, slice.get(i));
              }
              try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                  out.add(readHotfixFile(rs));
                }
              }
            }
          }
          return List.copyOf(out);
        });
  }

  private static HotfixFile readHotfixFile(ResultSet rs) throws SQLException {
    return new HotfixFile(
        rs.getString("hotfix_id"),
        Path.of(rs.getString("path")),
        rs.getString("action"),
        Db.optString(rs, "before_sha256"),
        Db.optString(rs, "after_sha256"));
  }

  /**
   * Removes one installed hotfix and every file row it owns, in one transaction, and answers how
   * many rows went. Typed so no caller has to build a {@code DELETE} out of an id.
   */
  int deleteHotfix(String hotfixId) {
    return db.write(
        c -> {
          int removed = 0;
          try (PreparedStatement ps =
              c.prepareStatement("DELETE FROM hotfix_files WHERE hotfix_id = ?")) {
            ps.setString(1, hotfixId);
            removed += ps.executeUpdate();
          }
          try (PreparedStatement ps =
              c.prepareStatement("DELETE FROM hotfixes_installed WHERE id = ?")) {
            ps.setString(1, hotfixId);
            removed += ps.executeUpdate();
          }
          return removed;
        });
  }
}
