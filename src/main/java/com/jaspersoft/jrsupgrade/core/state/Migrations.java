package com.jaspersoft.jrsupgrade.core.state;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Applies the versioned schema scripts under {@code db/migrations/} (spec §5.4). Invariants:
 * scripts are applied strictly in version order, each inside its own transaction together with its
 * {@code schema_version} row, so a crash mid-migration leaves the previous version intact and
 * re-opening the store is idempotent. Scripts are split on top-level semicolons; {@code BEGIN ...
 * END} trigger bodies are kept whole, so migration SQL must not use {@code CASE ... END}
 * expressions.
 */
final class Migrations {

  /** Ordered list of scripts; add new versions at the end, never edit an applied script. */
  static final List<String> SCRIPTS =
      List.of("V001__init.sql", "V002__timestamps_and_path_keys.sql", "V003__hotfix_origin.sql");

  private static final String RESOURCE_DIR = "db/migrations/";

  private Migrations() {}

  static void apply(Connection conn, Clock clock) throws SQLException {
    apply(conn, clock, SCRIPTS);
  }

  static void apply(Connection conn, Clock clock, List<String> scripts) throws SQLException {
    try (Statement s = conn.createStatement()) {
      s.execute(
          "CREATE TABLE IF NOT EXISTS schema_version ("
              + "version INTEGER PRIMARY KEY, name TEXT NOT NULL, applied_at TEXT NOT NULL)");
    }
    int current = currentVersion(conn);
    int latest = scripts.stream().mapToInt(Migrations::versionOf).max().orElse(0);
    if (current > latest) {
      // A downgraded binary would otherwise read and write a schema it does not know (item E6).
      throw new StateStoreException(
          "state.db is at schema version "
              + current
              + ", newer than this build understands ("
              + latest
              + "); use the jrs-upgrade that created it, or a newer one");
    }
    for (String script : scripts) {
      int version = versionOf(script);
      if (version <= current) {
        continue;
      }
      List<String> statements = split(load(script));
      try (Statement s = conn.createStatement()) {
        s.execute("BEGIN IMMEDIATE");
        try {
          for (String sql : statements) {
            s.execute(sql);
          }
          if (version == 2) {
            fillPathKeys(conn, "hotfix_files");
            fillPathKeys(conn, "customizations");
          }
          try (var ps =
              conn.prepareStatement(
                  "INSERT INTO schema_version(version, name, applied_at) VALUES (?, ?, ?)")) {
            ps.setInt(1, version);
            ps.setString(2, script);
            ps.setString(3, Timestamps.encode(clock.instant()));
            ps.executeUpdate();
          }
          s.execute("COMMIT");
        } catch (SQLException | RuntimeException e) {
          s.execute("ROLLBACK");
          throw e;
        }
      }
      current = version;
    }
  }

  /**
   * V002 companion: the canonical key cannot be built in SQL (it folds case only where the file
   * system does), so rows that predate the column get theirs here, inside the same transaction.
   */
  private static void fillPathKeys(Connection conn, String table) throws SQLException {
    List<Object[]> rows = new ArrayList<>();
    try (Statement s = conn.createStatement();
        ResultSet rs =
            s.executeQuery("SELECT rowid, path FROM " + table + " WHERE path_key IS NULL")) {
      while (rs.next()) {
        rows.add(new Object[] {rs.getLong(1), rs.getString(2)});
      }
    }
    try (var ps = conn.prepareStatement("UPDATE " + table + " SET path_key=? WHERE rowid=?")) {
      for (Object[] row : rows) {
        ps.setString(1, PathKeys.key(Path.of((String) row[1])));
        ps.setLong(2, (Long) row[0]);
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  static int currentVersion(Connection conn) throws SQLException {
    try (Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
      return rs.next() ? rs.getInt(1) : 0;
    }
  }

  static int versionOf(String script) {
    int sep = script.indexOf("__");
    if (!script.startsWith("V") || sep < 2) {
      throw new IllegalArgumentException("migration name must look like VNNN__name.sql: " + script);
    }
    return Integer.parseInt(script.substring(1, sep));
  }

  static String load(String script) {
    String resource = RESOURCE_DIR + script;
    try (InputStream in = Migrations.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new StateStoreException("migration resource missing on classpath: " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new StateStoreException("cannot read migration " + resource, e);
    }
  }

  /**
   * Splits a script into statements on top-level {@code ;}, ignoring {@code --} comments and string
   * literals, and treating everything between {@code BEGIN} and {@code END} as one unit.
   */
  static List<String> split(String script) {
    List<String> out = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int depth = 0;
    int i = 0;
    int n = script.length();
    while (i < n) {
      char c = script.charAt(i);
      if (c == '-' && i + 1 < n && script.charAt(i + 1) == '-') {
        int eol = script.indexOf('\n', i);
        i = eol < 0 ? n : eol + 1;
        current.append('\n');
        continue;
      }
      if (c == '\'') {
        int end = i + 1;
        while (end < n) {
          if (script.charAt(end) == '\'') {
            if (end + 1 < n && script.charAt(end + 1) == '\'') {
              end += 2;
              continue;
            }
            break;
          }
          end++;
        }
        current.append(script, i, Math.min(end + 1, n));
        i = end + 1;
        continue;
      }
      if (Character.isLetter(c)) {
        int end = i;
        while (end < n
            && (Character.isLetterOrDigit(script.charAt(end)) || script.charAt(end) == '_')) {
          end++;
        }
        String word = script.substring(i, end);
        String upper = word.toUpperCase(Locale.ROOT);
        if (upper.equals("BEGIN")) {
          depth++;
        } else if (upper.equals("END")) {
          depth = Math.max(0, depth - 1);
        }
        current.append(word);
        i = end;
        continue;
      }
      if (c == ';' && depth == 0) {
        flush(current, out);
        i++;
        continue;
      }
      current.append(c);
      i++;
    }
    flush(current, out);
    return List.copyOf(out);
  }

  private static void flush(StringBuilder current, List<String> out) {
    String sql = current.toString().strip();
    current.setLength(0);
    if (!sql.isEmpty()) {
      out.add(sql);
    }
  }
}
