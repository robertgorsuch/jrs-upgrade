package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.platform.Platform.OsFamily;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.semver4j.Semver;

/**
 * The vendor preconditions of review §2.5 (issue #108), judged before anything is stopped or backed
 * up: the licence file the 10.0+ upgrade scripts want in the home of the user running them (upgrade
 * guide 10.1 pp.43-44), the JDBC driver jar buildomatic needs in the target package for Oracle, SQL
 * Server and DB2 (release notes 10.1 p.33: the shipped property files name old jars), the password
 * storage settings that differ between the old and the new webapp (installation guide 10.1
 * pp.194-199), and the two analytics JNDI resources a 9.0 webapp's {@code META-INF/context.xml}
 * must declare even when the feature is off (release notes 9.0 p.15). Invariants: pure and
 * read-only; every judgement names the exact file it looked for, and every refusal names the exact
 * copy or edit that satisfies it; a condition that does not apply (community edition, an older
 * target, a bundled database) is a pass, never a guess.
 */
final class VendorPreconditions {

  static final String LICENSE_FILE = "jaspersoft.jrs.license";
  static final String LICENSE_DIRECTORY_PROPERTY = "js.license.directory";
  static final String PASSWORD_STORAGE_CONFIG = "js.password-storage-config.properties";
  static final String PASSWORD_STRATEGY_KEY = "password.strategy";
  static final String COMMERCIAL_WEBAPP = "jasperserver-pro";
  static final String DB_TYPE = "dbType";
  static final String JDBC_ARTIFACT_ID = "maven.jdbc.artifactId";
  static final String JDBC_VERSION = "maven.jdbc.version";
  static final List<String> ANALYTICS_JNDI =
      List.of("jdbc/jasperserverSystemAnalytics", "jdbc/jasperserverAuditAnalytics");

  /** The databases the vendor ships no driver for (upgrade guide 10.1 p.43). */
  static final Set<String> DRIVERLESS_DB_TYPES = Set.of("oracle", "sqlserver", "db2");

  static final Semver LICENSE_FROM = new Semver("10.0.0");
  static final Semver PASSWORD_MIGRATION_FROM = new Semver("10.1.0");
  static final Semver ANALYTICS_JNDI_FROM = new Semver("9.0.0");
  static final Semver ANALYTICS_JNDI_BELOW = new Semver("10.0.0");

  private static final Pattern LICENSE_DIRECTORY =
      // the bundled installer quotes the whole option ("-Djs.license.directory=C:\...\"), so an
      // unquoted value ends at the next quote or blank
      Pattern.compile(
          "-D" + Pattern.quote(LICENSE_DIRECTORY_PROPERTY) + "=(\"([^\"]*)\"|([^\"\\s]+))");

  private VendorPreconditions() {}

  static boolean commercial(String webappName) {
    return COMMERCIAL_WEBAPP.equals(webappName);
  }

  static boolean atLeast(String version, Semver floor) {
    Semver v = Semver.coerce(version);
    return v != null && v.isGreaterThanOrEqualTo(floor);
  }

  static boolean below(String version, Semver ceiling) {
    Semver v = Semver.coerce(version);
    return v != null && v.isLowerThan(ceiling);
  }

  /**
   * Upgrade guide 10.1 pp.43-44: a 10.0+ commercial upgrade wants {@code jaspersoft.jrs.license} in
   * the home of the user running the scripts. Pass when it is there or the check does not apply;
   * WARN when it is only where the running server keeps it ({@code server.installDir}, or the
   * {@code js.license.directory} the host Tomcat's {@code setenv} names), since the vendor script
   * may then fail late; FAIL when it is nowhere.
   */
  static CheckResult license(
      String toVersion,
      String webappName,
      Path userHome,
      Path installDir,
      Path hostTomcatDir,
      OsFamily os) {
    if (!commercial(webappName) || !atLeast(toVersion, LICENSE_FROM)) {
      return CheckResult.pass();
    }
    Path inHome = userHome.resolve(LICENSE_FILE);
    if (Files.isRegularFile(inHome)) {
      return CheckResult.pass();
    }
    List<Path> elsewhere = new ArrayList<>();
    elsewhere.add(installDir.resolve(LICENSE_FILE));
    licenseDirectory(setenv(hostTomcatDir, os))
        .map(dir -> dir.resolve(LICENSE_FILE))
        .filter(p -> !elsewhere.contains(p))
        .ifPresent(elsewhere::add);
    Optional<Path> found = elsewhere.stream().filter(Files::isRegularFile).findFirst();
    if (found.isPresent()) {
      return CheckResult.warn(
          "no "
              + inHome
              + ": the upgrade guide 10.1 (pp.43-44) wants the licence in the home of the user"
              + " running the vendor scripts, and js-upgrade may otherwise fail late with a licence"
              + " error; copy "
              + found.get()
              + " there before the run");
    }
    return CheckResult.fail(
        "no "
            + LICENSE_FILE
            + " in "
            + userHome
            + " or "
            + String.join(
                " or ", elsewhere.stream().map(Path::getParent).map(Path::toString).toList())
            + "; JasperReports Server "
            + toVersion
            + " checks the licence during the upgrade",
        "place the licence file in "
            + userHome
            + " (upgrade guide 10.1 pp.43-44), the home of the user running jrs-upgrade");
  }

  /** The directory a {@code -Djs.license.directory=...} in the file names, if any. */
  static Optional<Path> licenseDirectory(Path setenv) {
    if (!Files.isRegularFile(setenv)) {
      return Optional.empty();
    }
    try (Stream<String> lines = Files.lines(setenv, StandardCharsets.ISO_8859_1)) {
      return lines
          .map(LICENSE_DIRECTORY::matcher)
          .filter(Matcher::find)
          .map(m -> m.group(2) != null ? m.group(2) : m.group(3))
          .map(String::trim)
          .filter(s -> !s.isEmpty())
          .map(Path::of)
          .findFirst();
    } catch (IOException | java.io.UncheckedIOException | java.nio.file.InvalidPathException e) {
      return Optional.empty();
    }
  }

  /**
   * Upgrade guide 10.1 p.43 and release notes 10.1 p.33: for Oracle, SQL Server and DB2 the driver
   * jar must sit in the target buildomatic's {@code conf_source/db/<dbType>/jdbc/} and {@code
   * maven.jdbc.artifactId}/{@code maven.jdbc.version} must name it. The installed master properties
   * (carried over) win over the target package's own file, as they will when staged.
   */
  static CheckResult jdbcDriver(
      Map<String, String> carriedOver,
      Map<String, String> targetMaster,
      Path targetBuildomatic,
      Path installedBuildomatic) {
    Optional<String> dbType = value(DB_TYPE, carriedOver, targetMaster);
    if (dbType.isEmpty() || !DRIVERLESS_DB_TYPES.contains(dbType.get())) {
      return CheckResult.pass();
    }
    Path dir = jdbcDir(targetBuildomatic, dbType.get());
    Optional<String> artifact = value(JDBC_ARTIFACT_ID, carriedOver, targetMaster);
    Optional<String> version = value(JDBC_VERSION, carriedOver, targetMaster);
    if (artifact.isPresent() && version.isPresent()) {
      String jar = artifact.get() + "-" + version.get() + ".jar";
      if (Files.isRegularFile(dir.resolve(jar))) {
        return CheckResult.pass();
      }
      Path installed = jdbcDir(installedBuildomatic, dbType.get()).resolve(jar);
      String remedy =
          Files.isRegularFile(installed)
              ? "copy " + installed + " to " + dir
              : "obtain the "
                  + dbType.get()
                  + " JDBC driver, copy it to "
                  + dir
                  + " and make "
                  + JDBC_ARTIFACT_ID
                  + " and "
                  + JDBC_VERSION
                  + " in default_master.properties name it (release notes 10.1 p.33: the shipped"
                  + " property files name old jars)";
      return CheckResult.fail(
          "no "
              + jar
              + " in "
              + dir
              + " ("
              + JDBC_ARTIFACT_ID
              + "="
              + artifact.get()
              + ", "
              + JDBC_VERSION
              + "="
              + version.get()
              + "); the vendor ships no "
              + dbType.get()
              + " driver and js-upgrade needs it there",
          remedy);
    }
    if (anyJar(dir)) {
      return CheckResult.pass();
    }
    return CheckResult.fail(
        "no JDBC driver jar in "
            + dir
            + "; the vendor ships no "
            + dbType.get()
            + " driver and js-upgrade needs it there",
        "obtain the "
            + dbType.get()
            + " JDBC driver, copy it to "
            + dir
            + " and set "
            + JDBC_ARTIFACT_ID
            + " and "
            + JDBC_VERSION
            + " in default_master.properties to its name and version (upgrade guide 10.1 p.43)");
  }

  /** The {@code setenv} file the host Tomcat reads on this operating system. */
  static Path setenv(Path tomcatDir, OsFamily os) {
    return tomcatDir.resolve("bin").resolve(os == OsFamily.WINDOWS ? "setenv.bat" : "setenv.sh");
  }

  static Path jdbcDir(Path buildomatic, String dbType) {
    return buildomatic
        .resolve("conf_source")
        .resolve("db")
        .resolve(dbType.toLowerCase(Locale.ROOT))
        .resolve("jdbc");
  }

  /**
   * Installation guide 10.1 pp.194-199: the target webapp's password storage settings, when they
   * differ from the running webapp's (or the running webapp has none), mean stored passwords may
   * need the vendor's migration. Empty when the target ships as a WAR or has no such file.
   */
  static Optional<String> passwordStorageWarning(
      Path oldWebappDir, Optional<Path> targetWebappDir, boolean migrationPlanned) {
    Optional<Path> target =
        targetWebappDir.map(d -> d.resolve("WEB-INF").resolve(PASSWORD_STORAGE_CONFIG));
    if (target.isEmpty() || !Files.isRegularFile(target.get())) {
      return Optional.empty();
    }
    Path old = oldWebappDir.resolve("WEB-INF").resolve(PASSWORD_STORAGE_CONFIG);
    boolean same;
    try {
      same = Files.isRegularFile(old) && Files.mismatch(old, target.get()) == -1L;
    } catch (IOException e) {
      same = false;
    }
    if (same) {
      return Optional.empty();
    }
    String strategy =
        property(target.get(), PASSWORD_STRATEGY_KEY)
            .map(s -> " (" + PASSWORD_STRATEGY_KEY + "=" + s + ")")
            .orElse("");
    return Optional.of(
        target.get()
            + strategy
            + (Files.isRegularFile(old)
                ? " differs from " + old
                : " has no counterpart in the running webapp")
            + ": stored passwords may need the vendor's migration to the modern format after the"
            + " upgrade (installation guide 10.1 pp.194-199, js-ant migrate-passwords)"
            + (migrationPlanned
                ? "; --migrate-passwords runs it after the vendor run"
                : "; pass --migrate-passwords on a samedb upgrade to 10.1 or later, or run it by"
                    + " hand"));
  }

  /**
   * The analytics JNDI resources missing from a {@code META-INF/context.xml}; all when unreadable.
   */
  static List<String> missingAnalyticsJndi(Path contextXml) {
    if (!Files.isRegularFile(contextXml)) {
      return ANALYTICS_JNDI;
    }
    String text;
    try {
      text = Files.readString(contextXml, StandardCharsets.ISO_8859_1);
    } catch (IOException e) {
      return ANALYTICS_JNDI;
    }
    return ANALYTICS_JNDI.stream().filter(name -> !text.contains(name)).toList();
  }

  private static Optional<String> value(
      String key, Map<String, String> first, Map<String, String> second) {
    return Optional.ofNullable(first.get(key))
        .or(() -> Optional.ofNullable(second.get(key)))
        .map(String::trim)
        .filter(s -> !s.isEmpty());
  }

  private static boolean anyJar(Path dir) {
    if (!Files.isDirectory(dir)) {
      return false;
    }
    try (DirectoryStream<Path> jars = Files.newDirectoryStream(dir, "*.jar")) {
      for (Path jar : jars) {
        if (Files.isRegularFile(jar)) {
          return true;
        }
      }
    } catch (IOException e) {
      return false;
    }
    return false;
  }

  private static Optional<String> property(Path file, String key) {
    try (Stream<String> lines = Files.lines(file, StandardCharsets.ISO_8859_1)) {
      return lines
          .map(String::trim)
          .filter(l -> l.startsWith(key + "=") || l.startsWith(key + " ="))
          .map(l -> l.substring(l.indexOf('=') + 1).trim())
          .findFirst();
    } catch (IOException | java.io.UncheckedIOException e) {
      return Optional.empty();
    }
  }
}
