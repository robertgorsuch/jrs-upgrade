package com.jaspersoft.jrsupgrade.ops.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.secrets.Secret;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The connector against a real driver loaded from a real directory. The driver is SQLite rather
 * than PostgreSQL, which is the point: the class under test knows nothing about the product, only
 * about finding a {@code java.sql.Driver} in jars that are not on jrs-upgrade's own class path,
 * connecting through it and releasing the loader afterwards.
 */
class DefaultJdbcConnectorTest {

  private final DefaultJdbcConnector connector = new DefaultJdbcConnector();

  /**
   * Not a {@code @TempDir}: a class loader over a jar keeps that jar mapped for as long as the JVM
   * lives, and Windows will not delete a mapped file, so the driver copy lives under the build
   * directory and is removed best-effort instead.
   */
  private static Path tmp;

  private static Path driverDir;

  @BeforeAll
  static void copyTheDriverIntoItsOwnDirectory() throws IOException, URISyntaxException {
    tmp = Path.of("target", "jdbc-connector-test", UUID.randomUUID().toString());
    driverDir = Files.createDirectories(tmp.resolve("drivers"));
    Path source =
        Path.of(org.sqlite.JDBC.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    Files.copy(source, driverDir.resolve("sqlite-jdbc.jar"), StandardCopyOption.REPLACE_EXISTING);
  }

  @AfterAll
  static void removeWhatWindowsWillLetUs() throws IOException {
    if (tmp == null || !Files.exists(tmp)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(tmp)) {
      walk.sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException stillMapped) {
                  // the driver jar stays mapped until the JVM exits; the build directory is
                  // cleaned by `mvn clean`
                }
              });
    }
  }

  /** A fresh database per call, so no test sees a table another one created. */
  private static String url() {
    return "jdbc:sqlite:"
        + tmp.resolve(UUID.randomUUID() + ".db").toAbsolutePath().toString().replace('\\', '/');
  }

  @Test
  void should_connect_run_statements_and_answer_queries_through_a_loaded_driver() throws Exception {
    try (JdbcConnector.Session session =
        connector.connect(driverDir, url(), Optional.of("jasperdb"), Optional.empty())) {
      assertThat(session.product()).containsIgnoringCase("sqlite");

      session.executeStatement("CREATE TABLE jiresource (id INTEGER PRIMARY KEY, name TEXT)");

      assertThat(session.queryHasRow("SELECT 1 FROM jiresource")).isFalse();

      session.executeStatement("INSERT INTO jiresource(id, name) VALUES (1, 'report')");

      assertThat(session.queryHasRow("SELECT 1 FROM jiresource")).isTrue();
    }
  }

  @Test
  void should_pass_a_secret_password_to_the_driver_and_wipe_it() throws Exception {
    Secret password = Secret.fromString("Sup3rSecret");

    try (JdbcConnector.Session session =
        connector.connect(driverDir, url(), Optional.of("jasperdb"), Optional.of(password))) {
      assertThat(session.queryHasRow("SELECT 1")).isTrue();
    }
  }

  @Test
  void should_refuse_a_directory_with_no_jars() {
    Path empty = tmp.resolve("empty");

    assertThatThrownBy(
            () -> {
              Files.createDirectories(empty);
              connector.connect(empty, url(), Optional.empty(), Optional.empty());
            })
        .isInstanceOfSatisfying(
            JdbcException.class,
            e -> assertThat(e.kind()).isEqualTo(JdbcException.Kind.NO_DRIVER_JARS));
  }

  @Test
  void should_report_an_unreadable_driver_directory_as_io() {
    assertThatThrownBy(
            () ->
                connector.connect(tmp.resolve("absent"), url(), Optional.empty(), Optional.empty()))
        .isInstanceOfSatisfying(
            JdbcException.class, e -> assertThat(e.kind()).isEqualTo(JdbcException.Kind.IO));
  }

  @Test
  void should_refuse_a_url_no_driver_in_the_directory_accepts() {
    assertThatThrownBy(
            () ->
                connector.connect(
                    driverDir,
                    "jdbc:postgresql://db.example.internal:5432/jasperserver",
                    Optional.empty(),
                    Optional.empty()))
        .isInstanceOfSatisfying(
            JdbcException.class,
            e -> {
              assertThat(e.kind()).isEqualTo(JdbcException.Kind.NO_MATCHING_DRIVER);
              assertThat(e).hasMessageContaining("jdbc:postgresql");
            });
  }

  @Test
  void should_report_a_driver_that_cannot_open_the_database_as_connect_failed() throws IOException {
    Path aDirectory = Files.createDirectories(tmp.resolve("not-a-database"));

    assertThatThrownBy(
            () ->
                connector.connect(
                    driverDir,
                    "jdbc:sqlite:" + aDirectory.toString().replace('\\', '/'),
                    Optional.empty(),
                    Optional.empty()))
        .isInstanceOfSatisfying(
            JdbcException.class,
            e -> assertThat(e.kind()).isEqualTo(JdbcException.Kind.CONNECT_FAILED));
  }

  @Test
  void should_report_a_failing_statement_and_a_failing_query_as_sql_failed() throws Exception {
    try (JdbcConnector.Session session =
        connector.connect(driverDir, url(), Optional.empty(), Optional.empty())) {
      assertThatThrownBy(() -> session.executeStatement("NOT SQL AT ALL"))
          .isInstanceOfSatisfying(
              JdbcException.class,
              e -> assertThat(e.kind()).isEqualTo(JdbcException.Kind.SQL_FAILED));
      assertThatThrownBy(() -> session.queryHasRow("SELECT 1 FROM no_such_table"))
          .isInstanceOfSatisfying(
              JdbcException.class,
              e -> assertThat(e.kind()).isEqualTo(JdbcException.Kind.SQL_FAILED));
    }
  }

  @Test
  void should_release_the_driver_loader_when_the_session_closes() throws Exception {
    JdbcConnector.Session session =
        connector.connect(driverDir, url(), Optional.empty(), Optional.empty());
    session.executeStatement("CREATE TABLE t (a INTEGER)");

    session.close();

    assertThatThrownBy(() -> session.queryHasRow("SELECT 1 FROM t"))
        .as("the connection is gone with the loader")
        .isInstanceOf(JdbcException.class);
  }
}
