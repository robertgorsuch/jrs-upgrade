package com.jaspersoft.jrsupgrade.ops.db;

import com.jaspersoft.jrsupgrade.core.secrets.Secret;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * {@link JdbcConnector} backed by a {@link URLClassLoader} over the driver directory and {@link
 * ServiceLoader} discovery of {@code java.sql.Driver}. Invariants: the loader's parent is the
 * platform loader, so the driver never sees jrs-upgrade's own classes; the loader is closed
 * together with the connection; auto-commit stays at the driver default because the hotfix contract
 * makes no transactional promise (spec §8.1).
 */
public final class DefaultJdbcConnector implements JdbcConnector {

  @Override
  public Session connect(
      Path driverDir, String url, Optional<String> username, Optional<Secret> password)
      throws JdbcException {
    List<URL> jars;
    try {
      jars = jars(driverDir);
    } catch (IOException e) {
      throw new JdbcException(
          JdbcException.Kind.IO, "cannot list " + driverDir + ": " + e.getMessage(), e);
    }
    if (jars.isEmpty()) {
      throw new JdbcException(JdbcException.Kind.NO_DRIVER_JARS, "no *.jar in " + driverDir);
    }
    URLClassLoader loader =
        new URLClassLoader(jars.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
    try {
      Optional<Driver> driver = driverFor(loader, url);
      if (driver.isEmpty()) {
        closeQuietly(loader);
        throw new JdbcException(
            JdbcException.Kind.NO_MATCHING_DRIVER, "no driver in " + driverDir + " accepts " + url);
      }
      Connection connection = open(driver.get(), url, username, password);
      return new DefaultSession(loader, connection);
    } catch (JdbcException e) {
      closeQuietly(loader);
      throw e;
    }
  }

  private static Connection open(
      Driver driver, String url, Optional<String> username, Optional<Secret> password)
      throws JdbcException {
    Properties props = new Properties();
    username.ifPresent(u -> props.setProperty("user", u));
    try {
      if (password.isPresent()) {
        char[] chars = password.get().chars();
        try {
          props.setProperty("password", new String(chars));
        } finally {
          Arrays.fill(chars, '\0');
        }
      }
      Connection connection = driver.connect(url, props);
      if (connection == null) {
        throw new JdbcException(
            JdbcException.Kind.CONNECT_FAILED, driver.getClass().getName() + " refused " + url);
      }
      return connection;
    } catch (SQLException e) {
      throw new JdbcException(
          JdbcException.Kind.CONNECT_FAILED, "cannot connect to " + url + ": " + e.getMessage(), e);
    } finally {
      props.clear();
    }
  }

  private static List<URL> jars(Path dir) throws IOException {
    List<URL> urls = new ArrayList<>();
    try (DirectoryStream<Path> children = Files.newDirectoryStream(dir, "*.jar")) {
      for (Path jar : children) {
        try {
          urls.add(jar.toUri().toURL());
        } catch (MalformedURLException e) {
          throw new IOException("cannot address " + jar, e);
        }
      }
    }
    urls.sort((a, b) -> a.toString().compareTo(b.toString()));
    return urls;
  }

  private static Optional<Driver> driverFor(ClassLoader loader, String url) {
    try {
      for (Driver driver : ServiceLoader.load(Driver.class, loader)) {
        try {
          if (driver.acceptsURL(url)) {
            return Optional.of(driver);
          }
        } catch (SQLException e) {
          // a driver that cannot even parse the URL is not the one we want
        }
      }
    } catch (ServiceConfigurationError e) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private static void closeQuietly(URLClassLoader loader) {
    try {
      loader.close();
    } catch (IOException e) {
      // nothing left to release
    }
  }

  private static final class DefaultSession implements Session {
    private final URLClassLoader loader;
    private final Connection connection;

    DefaultSession(URLClassLoader loader, Connection connection) {
      this.loader = loader;
      this.connection = connection;
    }

    @Override
    public String product() {
      try {
        return connection.getMetaData().getDatabaseProductName()
            + " "
            + connection.getMetaData().getDatabaseProductVersion();
      } catch (SQLException e) {
        return "unknown database";
      }
    }

    @Override
    public boolean queryHasRow(String sql) throws JdbcException {
      try (Statement statement = connection.createStatement();
          ResultSet rs = statement.executeQuery(sql)) {
        return rs.next();
      } catch (SQLException e) {
        throw new JdbcException(
            JdbcException.Kind.SQL_FAILED, "query failed: " + e.getMessage(), e);
      }
    }

    @Override
    public void executeStatement(String statement) throws JdbcException {
      try (Statement s = connection.createStatement()) {
        s.execute(statement);
      } catch (SQLException e) {
        throw new JdbcException(
            JdbcException.Kind.SQL_FAILED, "statement failed: " + e.getMessage(), e);
      }
    }

    @Override
    public List<DbObject> objects() throws JdbcException {
      List<DbObject> out = new ArrayList<>();
      try {
        DatabaseMetaData md = connection.getMetaData();
        try (ResultSet rs =
            md.getTables(
                connection.getCatalog(),
                connection.getSchema(),
                "%",
                new String[] {"TABLE", "SEQUENCE"})) {
          while (rs.next()) {
            String name = rs.getString("TABLE_NAME");
            String type = rs.getString("TABLE_TYPE");
            if (name != null && !name.isBlank()) {
              out.add(
                  new DbObject(
                      "SEQUENCE".equalsIgnoreCase(type)
                          ? DbObject.Kind.SEQUENCE
                          : DbObject.Kind.TABLE,
                      name));
            }
          }
        }
        return out;
      } catch (SQLException e) {
        throw new JdbcException(
            JdbcException.Kind.SQL_FAILED, "cannot list the schema's tables: " + e.getMessage(), e);
      }
    }

    @Override
    public String tableDdl(String table) throws JdbcException {
      try {
        DatabaseMetaData md = connection.getMetaData();
        String catalog = connection.getCatalog();
        String schema = connection.getSchema();
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = md.getColumns(catalog, schema, table, "%")) {
          while (rs.next()) {
            String type = rs.getString("TYPE_NAME");
            int size = rs.getInt("COLUMN_SIZE");
            int digits = rs.getInt("DECIMAL_DIGITS");
            String lower = type == null ? "" : type.toLowerCase(Locale.ROOT);
            String sized =
                lower.contains("char")
                    ? type + "(" + size + ")"
                    : (lower.contains("numeric") || lower.contains("decimal")) && size > 0
                        ? type + "(" + size + "," + digits + ")"
                        : type;
            columns.add(
                "  "
                    + rs.getString("COLUMN_NAME")
                    + " "
                    + sized
                    + ("NO".equalsIgnoreCase(rs.getString("IS_NULLABLE")) ? " NOT NULL" : ""));
          }
        }
        List<String> key = new ArrayList<>();
        try (ResultSet rs = md.getPrimaryKeys(catalog, schema, table)) {
          while (rs.next()) {
            key.add(rs.getString("COLUMN_NAME"));
          }
        }
        if (!key.isEmpty()) {
          columns.add("  PRIMARY KEY (" + String.join(", ", key) + ")");
        }
        return "CREATE TABLE " + table + " (\n" + String.join(",\n", columns) + "\n)";
      } catch (SQLException e) {
        throw new JdbcException(
            JdbcException.Kind.SQL_FAILED,
            "cannot describe table " + table + ": " + e.getMessage(),
            e);
      }
    }

    @Override
    public void close() throws JdbcException {
      try (URLClassLoader unused = loader) {
        connection.close();
      } catch (SQLException e) {
        throw new JdbcException(
            JdbcException.Kind.CONNECT_FAILED, "cannot close connection: " + e.getMessage(), e);
      } catch (IOException e) {
        throw new JdbcException(
            JdbcException.Kind.IO, "cannot release the driver loader: " + e.getMessage(), e);
      }
    }
  }
}
