package com.jaspersoft.jrsupgrade.ops.db;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.secrets.Secret;
import com.jaspersoft.jrsupgrade.core.secrets.SecretRef;
import com.jaspersoft.jrsupgrade.core.secrets.SecretResolver;
import com.jaspersoft.jrsupgrade.jrs.vendor.BuildomaticLocator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * The resolved {@code database} section: type, URL, credentials reference and the JDBC driver
 * directory (the configured {@code database.driverDir}, else {@code conf_source/db/<db>/jdbc} in
 * the buildomatic directory {@link BuildomaticLocator#resolve} settles on, wherever it lives).
 * Invariants: {@link #from} is empty unless type and URL are both set; the password is resolved
 * only inside {@link #open}, into a {@link Secret} that is closed before the method returns.
 */
public record JdbcSettings(
    Config.DatabaseType type,
    String url,
    Optional<String> username,
    Optional<SecretRef> passwordRef,
    Optional<Path> driverDir) {

  public JdbcSettings {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(url, "url");
    Objects.requireNonNull(username, "username");
    Objects.requireNonNull(passwordRef, "passwordRef");
    Objects.requireNonNull(driverDir, "driverDir");
  }

  /**
   * Settings from the config; empty when {@code database.type} or {@code database.url} is unset.
   */
  public static Optional<JdbcSettings> from(Config config, Platform platform) {
    Config.Database db = config.database();
    if (db.type().isEmpty() || db.url().isEmpty()) {
      return Optional.empty();
    }
    Config.DatabaseType type = db.type().get();
    Optional<Path> driverDir =
        db.driverDir()
            .or(
                () ->
                    new BuildomaticLocator(platform)
                        .resolve(config)
                        .located()
                        .map(
                            b ->
                                b.dir()
                                    .resolve("conf_source")
                                    .resolve("db")
                                    .resolve(buildomaticDir(type))
                                    .resolve("jdbc")))
            .filter(Files::isDirectory);
    return Optional.of(
        new JdbcSettings(type, db.url().get(), db.username(), db.passwordRef(), driverDir));
  }

  /** Buildomatic's directory name for a database type. */
  public static String buildomaticDir(Config.DatabaseType type) {
    return switch (type) {
      case MSSQL -> "sqlserver";
      case POSTGRESQL, MYSQL, ORACLE, DB2 -> type.yamlValue();
    };
  }

  /** Constant probe statement that returns one row on a healthy database. */
  public String probeSql() {
    return switch (type) {
      case ORACLE -> "SELECT 1 FROM DUAL";
      case DB2 -> "SELECT 1 FROM SYSIBM.SYSDUMMY1";
      case POSTGRESQL, MYSQL, MSSQL -> "SELECT 1";
    };
  }

  /**
   * Opens a session; the caller closes it. Fails with {@link JdbcException.Kind#IO} when no driver
   * directory exists.
   */
  public JdbcConnector.Session open(JdbcConnector connector, SecretResolver secrets)
      throws JdbcException {
    Path dir =
        driverDir.orElseThrow(
            () ->
                new JdbcException(
                    JdbcException.Kind.IO,
                    "no JDBC driver directory (database.driverDir, or conf_source/db/"
                        + buildomaticDir(type)
                        + "/jdbc in the buildomatic directory)"));
    Optional<Secret> password = passwordRef.map(secrets::resolve);
    try {
      return connector.connect(dir, url, username, password);
    } finally {
      password.ifPresent(Secret::close);
    }
  }
}
