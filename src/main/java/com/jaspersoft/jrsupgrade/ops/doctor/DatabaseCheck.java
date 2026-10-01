package com.jaspersoft.jrsupgrade.ops.doctor;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.secrets.SecretException;
import com.jaspersoft.jrsupgrade.ops.BuildomaticDefaults;
import com.jaspersoft.jrsupgrade.ops.ReportItem;
import com.jaspersoft.jrsupgrade.ops.Services;
import com.jaspersoft.jrsupgrade.ops.db.DefaultJdbcConnector;
import com.jaspersoft.jrsupgrade.ops.db.JdbcConnector;
import com.jaspersoft.jrsupgrade.ops.db.JdbcException;
import com.jaspersoft.jrsupgrade.ops.db.JdbcSettings;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code doctor} database connectivity check (spec §12.1). Invariants: the JDBC driver is
 * loaded from the configured {@code database.driverDir} or buildomatic's bundled driver directory
 * through a {@link JdbcConnector} (throw-away class loader, nothing registered with {@code
 * DriverManager}); the password is resolved into the connection properties and cleared right after
 * the attempt; the only statement run is a constant {@code SELECT 1}.
 */
final class DatabaseCheck {

  static final String NAME = "database";

  private DatabaseCheck() {}

  static ReportItem check(Services s) {
    return check(s, new DefaultJdbcConnector());
  }

  static ReportItem check(Services s, JdbcConnector connector) {
    ReportItem item = connect(s, connector);
    // #73: a value config.yaml sets overrides buildomatic's; say so when the two disagree
    Map<String, String> differ = BuildomaticDefaults.disagreements(s.config(), s.platform());
    if (differ.isEmpty() || item.status() == ReportItem.Status.SKIP) {
      return item;
    }
    String detail =
        item.detail()
            + "; config.yaml differs from default_master.properties ("
            + String.join(
                "; ", differ.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList())
            + ")";
    String remediation =
        "remove "
            + String.join(", ", differ.keySet())
            + " from config.yaml to use the values"
            + " buildomatic has, or make them agree";
    return item.status() == ReportItem.Status.PASS
        ? ReportItem.warn(NAME, detail, remediation)
        : new ReportItem(NAME, item.status(), detail, item.remediation() + "; " + remediation);
  }

  private static ReportItem connect(Services s, JdbcConnector connector) {
    Config.Database db = s.config().database();
    if (db.type().isEmpty()) {
      return ReportItem.skip(
          NAME,
          "not configured; needed for the upgrade's repository-cache clear, the customer-table guard of a newdb upgrade and --custom-ddl",
          "set database.type, database.url, database.username and database.passwordRef before"
              + " an upgrade that needs them");
    }
    if (db.passwordRef().isEmpty()) {
      // #73: settings read from default_master.properties are not a request to check the database
      return ReportItem.skip(
          NAME,
          db.type().get().yamlValue()
              + " repository database found, but database.passwordRef is not set, so the"
              + " connection is not checked; needed for the upgrade's repository-cache clear, the customer-table guard of a newdb upgrade and --custom-ddl",
          "set database.passwordRef (jrs-upgrade config set database.passwordRef) to check the"
              + " connection");
    }
    if (!s.secrets().availableWithoutPrompt(db.passwordRef().get())) {
      // field test 2, D1: a password not supplied yet is a SKIP saying so, never a prompt and
      // not a failure; a reference that is present but wrong still fails below
      return ReportItem.skip(
          NAME,
          "database password not available ("
              + db.passwordRef().get().render()
              + "), so the connection is not checked; needed for the upgrade's repository-cache clear, the customer-table guard of a newdb upgrade and --custom-ddl",
          "set the variable, create the file, or unlock secrets.enc with --passphrase-file or"
              + " JRS_UPGRADE_PASSPHRASE, then run doctor again");
    }
    Config.DatabaseType type = db.type().get();
    Optional<JdbcSettings> settings = JdbcSettings.from(s.config(), s.platform());
    if (settings.isEmpty()) {
      return ReportItem.fail(NAME, "database.url is not configured", "set database.url");
    }
    if (settings.get().driverDir().isEmpty()) {
      return ReportItem.fail(
          NAME,
          "no JDBC driver directory (database.driverDir, or conf_source/db/"
              + JdbcSettings.buildomaticDir(type)
              + "/jdbc in the buildomatic directory)",
          "set database.driverDir to a directory holding the " + type.yamlValue() + " JDBC jar");
    }
    try (JdbcConnector.Session session = settings.get().open(connector, s.secrets())) {
      String product = session.product();
      if (!session.queryHasRow(settings.get().probeSql())) {
        return ReportItem.fail(
            NAME, "probe query returned no row from " + product, "check the database");
      }
      return ReportItem.pass(
          NAME, "connected to " + product + db.username().map(u -> " as " + u).orElse(""));
    } catch (SecretException e) {
      return ReportItem.fail(NAME, e.getMessage(), "fix " + db.passwordRef().get().render());
    } catch (JdbcException e) {
      return ReportItem.fail(
          NAME, e.getMessage(), remediation(e, type, settings.get().driverDir().get()));
    }
  }

  private static String remediation(
      JdbcException e, Config.DatabaseType type, java.nio.file.Path driverDir) {
    return switch (e.kind()) {
      case NO_DRIVER_JARS -> "copy the " + type.yamlValue() + " JDBC driver jar into " + driverDir;
      case NO_MATCHING_DRIVER -> "check database.url and that the driver jar matches database.type";
      case CONNECT_FAILED ->
          "check database.url, database.username and the password behind database.passwordRef";
      case SQL_FAILED -> "check the database";
      case IO -> "check the driver directory " + driverDir;
    };
  }
}
