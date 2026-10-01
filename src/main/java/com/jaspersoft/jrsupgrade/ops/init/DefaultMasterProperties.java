package com.jaspersoft.jrsupgrade.ops.init;

import com.jaspersoft.jrsupgrade.core.config.Config;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The subset of buildomatic's {@code default_master.properties} that {@code init} prefills the
 * configuration from (spec §12.0). Invariants: no key whose name contains {@code pass} (any case)
 * is ever read, so a password can neither be held here nor written to {@code config.yaml}; the file
 * is streamed line by line; a missing file yields an empty instance rather than an error.
 */
public record DefaultMasterProperties(Map<String, String> values) {

  private static final String FORBIDDEN = "pass";

  public DefaultMasterProperties {
    Objects.requireNonNull(values, "values");
    values = Map.copyOf(values);
    for (String key : values.keySet()) {
      if (key.toLowerCase(Locale.ROOT).contains(FORBIDDEN)) {
        throw new IllegalArgumentException("refusing to hold property " + key);
      }
    }
  }

  public static DefaultMasterProperties empty() {
    return new DefaultMasterProperties(Map.of());
  }

  /** Parses {@code file}; keys containing {@code pass} are dropped while reading. */
  public static DefaultMasterProperties parse(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return empty();
    }
    Map<String, String> values = new LinkedHashMap<>();
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
      String line;
      while ((line = reader.readLine()) != null) {
        String stripped = line.strip();
        if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("!")) {
          continue;
        }
        int sep = separator(stripped);
        if (sep <= 0) {
          continue;
        }
        String key = stripped.substring(0, sep).strip();
        String value = stripped.substring(sep + 1).strip();
        if (key.isEmpty() || key.toLowerCase(Locale.ROOT).contains(FORBIDDEN)) {
          continue;
        }
        values.putIfAbsent(key, value);
      }
    }
    return new DefaultMasterProperties(values);
  }

  private static int separator(String line) {
    int best = -1;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '=' || c == ':') {
        return i;
      }
      if (Character.isWhitespace(c) && best < 0) {
        best = i;
      }
    }
    return best;
  }

  public Optional<String> value(String key) {
    return Optional.ofNullable(values.get(key)).filter(v -> !v.isEmpty());
  }

  public Optional<String> dbType() {
    return value("dbType");
  }

  public Optional<String> dbHost() {
    return value("dbHost");
  }

  public Optional<String> dbPort() {
    return value("dbPort");
  }

  public Optional<String> dbName() {
    return value("js.dbName");
  }

  public Optional<String> dbUsername() {
    return value("dbUsername");
  }

  /** {@code dbType} mapped to the configuration enumeration; {@code sqlserver} becomes MSSQL. */
  public Optional<Config.DatabaseType> databaseType() {
    return dbType()
        .map(t -> t.toLowerCase(Locale.ROOT))
        .flatMap(
            t ->
                switch (t) {
                  case "postgresql" -> Optional.of(Config.DatabaseType.POSTGRESQL);
                  case "mysql" -> Optional.of(Config.DatabaseType.MYSQL);
                  case "oracle" -> Optional.of(Config.DatabaseType.ORACLE);
                  case "sqlserver", "mssql" -> Optional.of(Config.DatabaseType.MSSQL);
                  case "db2" -> Optional.of(Config.DatabaseType.DB2);
                  default -> Optional.empty();
                });
  }

  /** The JDBC URL buildomatic would use, when type, host and database name are all known. */
  public Optional<String> jdbcUrl() {
    Optional<Config.DatabaseType> type = databaseType();
    Optional<String> host = dbHost();
    // buildomatic's own default when js.dbName is not set is "jasperserver".
    String name = dbName().orElse("jasperserver");
    if (type.isEmpty() || host.isEmpty()) {
      return Optional.empty();
    }
    String port = dbPort().orElse(defaultPort(type.get()));
    return Optional.of(jdbcUrl(type.get(), host.get(), port, name));
  }

  static String jdbcUrl(Config.DatabaseType type, String host, String port, String name) {
    return switch (type) {
      case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + name;
      case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + name;
      case ORACLE -> "jdbc:oracle:thin:@" + host + ":" + port + ":" + name;
      case MSSQL -> "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + name;
      case DB2 -> "jdbc:db2://" + host + ":" + port + "/" + name;
    };
  }

  static String defaultPort(Config.DatabaseType type) {
    return switch (type) {
      case POSTGRESQL -> "5432";
      case MYSQL -> "3306";
      case ORACLE -> "1521";
      case MSSQL -> "1433";
      case DB2 -> "50000";
    };
  }
}
