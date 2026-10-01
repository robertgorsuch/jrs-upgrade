package com.jaspersoft.jrsupgrade.core.state;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Servers: the server rows `init` and `doctor` remember. Invariant: one aggregate of the state
 * store, reached only through {@link StateStore}, which owns the {@link Db} session every method
 * here runs on, so the transaction, the lock and the failure wrapping are the same for every
 * aggregate (roadmap item 16).
 */
final class Servers {

  private final Db db;

  Servers(Db db) {
    this.db = db;
  }

  void upsertServer(ServerRecord server) {
    db.write(
        c -> {
          try (PreparedStatement ps =
              c.prepareStatement(
                  "INSERT INTO servers(id, base_url, version, edition, tenancy, first_seen,"
                      + " last_seen) VALUES (?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET"
                      + " base_url=excluded.base_url, version=excluded.version,"
                      + " edition=excluded.edition, tenancy=excluded.tenancy,"
                      + " last_seen=excluded.last_seen")) {
            ps.setString(1, server.id());
            ps.setString(2, server.baseUrl());
            ps.setString(3, server.version());
            ps.setString(4, server.edition());
            ps.setString(5, server.tenancy());
            ps.setString(6, Timestamps.encode(server.firstSeen()));
            ps.setString(7, Timestamps.encode(server.lastSeen()));
            ps.executeUpdate();
          }
          return null;
        });
  }

  List<ServerRecord> servers() {
    return db.read(
        c -> {
          List<ServerRecord> out = new ArrayList<>();
          try (Statement s = c.createStatement();
              ResultSet rs = s.executeQuery("SELECT * FROM servers ORDER BY last_seen DESC")) {
            while (rs.next()) {
              out.add(
                  new ServerRecord(
                      rs.getString("id"),
                      rs.getString("base_url"),
                      rs.getString("version"),
                      rs.getString("edition"),
                      rs.getString("tenancy"),
                      Db.instant(rs, "first_seen"),
                      Db.instant(rs, "last_seen")));
            }
          }
          return List.copyOf(out);
        });
  }
}
