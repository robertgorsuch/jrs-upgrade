package com.jaspersoft.jrsupgrade.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.platform.Platform.OsFamily;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Review §2.5, issue #108: the vendor preconditions, judged on files alone. */
class VendorPreconditionsTest {

  @TempDir Path tmp;

  private Path dir(String name) throws IOException {
    return Files.createDirectories(tmp.resolve(name));
  }

  private Path file(Path path, String content) throws IOException {
    Files.createDirectories(path.getParent());
    return Files.writeString(path, content, StandardCharsets.UTF_8);
  }

  // ------------------------------------------------------------------ licence

  @Test
  void should_pass_the_licence_check_when_the_file_is_in_the_user_home() throws IOException {
    Path home = dir("home");
    file(home.resolve(VendorPreconditions.LICENSE_FILE), "lic");

    CheckResult r =
        VendorPreconditions.license(
            "10.1.0", "jasperserver-pro", home, dir("install"), dir("tomcat"), OsFamily.LINUX);

    assertThat(r).isInstanceOf(CheckResult.Pass.class);
  }

  @Test
  void should_warn_when_the_licence_is_only_where_the_running_server_keeps_it() throws IOException {
    Path home = dir("home");
    Path install = dir("install");
    file(install.resolve(VendorPreconditions.LICENSE_FILE), "lic");

    CheckResult r =
        VendorPreconditions.license(
            "10.0.0", "jasperserver-pro", home, install, dir("tomcat"), OsFamily.LINUX);

    assertThat(r).isInstanceOf(CheckResult.Warn.class);
    assertThat(((CheckResult.Warn) r).message())
        .contains("copy " + install.resolve(VendorPreconditions.LICENSE_FILE))
        .contains(home.toString())
        .contains("upgrade guide 10.1");
  }

  @Test
  void should_read_the_licence_directory_from_the_host_tomcat_setenv() throws IOException {
    Path home = dir("home");
    Path elsewhere = dir("licences");
    file(elsewhere.resolve(VendorPreconditions.LICENSE_FILE), "lic");
    Path tomcat = dir("tomcat");
    // the vendor's bundled installer writes exactly this shape on Windows
    file(
        tomcat.resolve("bin").resolve("setenv.bat"),
        "set \"JAVA_OPTS=\"-Djs.license.directory="
            + elsewhere
            + "\"  -Xms1024m -Xmx4096m %JAVA_OPTS%\"\r\n");

    CheckResult r =
        VendorPreconditions.license(
            "10.0.0", "jasperserver-pro", home, dir("install"), tomcat, OsFamily.WINDOWS);

    assertThat(r).isInstanceOf(CheckResult.Warn.class);
    assertThat(((CheckResult.Warn) r).message())
        .contains(elsewhere.resolve(VendorPreconditions.LICENSE_FILE).toString());
  }

  @Test
  void should_fail_the_licence_check_when_the_file_is_nowhere() throws IOException {
    Path home = dir("home");
    Path install = dir("install");

    CheckResult r =
        VendorPreconditions.license(
            "10.0.0", "jasperserver-pro", home, install, dir("tomcat"), OsFamily.LINUX);

    assertThat(r).isInstanceOf(CheckResult.Fail.class);
    CheckResult.Fail f = (CheckResult.Fail) r;
    assertThat(f.message()).contains(VendorPreconditions.LICENSE_FILE).contains(home.toString());
    assertThat(f.remediation()).contains(home.toString()).contains("pp.43-44");
  }

  @Test
  void should_not_judge_the_licence_for_the_community_edition_or_a_target_below_10()
      throws IOException {
    Path home = dir("home");

    assertThat(
            VendorPreconditions.license(
                "10.1.0", "jasperserver", home, dir("i"), dir("t"), OsFamily.LINUX))
        .isInstanceOf(CheckResult.Pass.class);
    assertThat(
            VendorPreconditions.license(
                "9.0.0", "jasperserver-pro", home, dir("i"), dir("t"), OsFamily.LINUX))
        .isInstanceOf(CheckResult.Pass.class);
  }

  // -------------------------------------------------------------- JDBC driver

  @Test
  void should_pass_the_driver_check_for_a_bundled_database() throws IOException {
    CheckResult r =
        VendorPreconditions.jdbcDriver(
            Map.of("dbType", "postgresql"),
            Map.of(),
            dir("pkg/buildomatic"),
            dir("inst/buildomatic"));

    assertThat(r).isInstanceOf(CheckResult.Pass.class);
  }

  @Test
  void should_pass_the_driver_check_when_the_named_jar_is_in_the_target_buildomatic()
      throws IOException {
    Path target = dir("pkg/buildomatic");
    file(VendorPreconditions.jdbcDir(target, "oracle").resolve("ojdbc11-23.5.0.jar"), "jar");

    CheckResult r =
        VendorPreconditions.jdbcDriver(
            Map.of("dbType", "oracle", "maven.jdbc.artifactId", "ojdbc11"),
            Map.of("maven.jdbc.version", "23.5.0"),
            target,
            dir("inst/buildomatic"));

    assertThat(r).isInstanceOf(CheckResult.Pass.class);
  }

  @Test
  void should_name_the_copy_from_the_installed_buildomatic_when_the_jar_is_only_there()
      throws IOException {
    Path target = dir("pkg/buildomatic");
    Path installed = dir("inst/buildomatic");
    Path jar = VendorPreconditions.jdbcDir(installed, "sqlserver").resolve("mssql-jdbc-12.6.1.jar");
    file(jar, "jar");

    CheckResult r =
        VendorPreconditions.jdbcDriver(
            Map.of(
                "dbType", "sqlserver",
                "maven.jdbc.artifactId", "mssql-jdbc",
                "maven.jdbc.version", "12.6.1"),
            Map.of(),
            target,
            installed);

    assertThat(r).isInstanceOf(CheckResult.Fail.class);
    CheckResult.Fail f = (CheckResult.Fail) r;
    assertThat(f.message())
        .contains("mssql-jdbc-12.6.1.jar")
        .contains(VendorPreconditions.jdbcDir(target, "sqlserver").toString());
    assertThat(f.remediation())
        .isEqualTo("copy " + jar + " to " + VendorPreconditions.jdbcDir(target, "sqlserver"));
  }

  @Test
  void should_ask_for_the_driver_and_the_property_edit_when_nothing_names_or_holds_it()
      throws IOException {
    Path target = dir("pkg/buildomatic");

    CheckResult named =
        VendorPreconditions.jdbcDriver(
            Map.of("dbType", "db2", "maven.jdbc.artifactId", "jcc", "maven.jdbc.version", "11.5"),
            Map.of(),
            target,
            dir("inst/buildomatic"));
    CheckResult unnamed =
        VendorPreconditions.jdbcDriver(
            Map.of("dbType", "oracle"), Map.of(), target, dir("inst/buildomatic"));

    assertThat(named).isInstanceOf(CheckResult.Fail.class);
    assertThat(((CheckResult.Fail) named).remediation())
        .contains("obtain the db2 JDBC driver")
        .contains("release notes 10.1 p.33");
    assertThat(unnamed).isInstanceOf(CheckResult.Fail.class);
    assertThat(((CheckResult.Fail) unnamed).message()).contains("no JDBC driver jar in");
  }

  @Test
  void should_accept_any_jar_when_the_properties_do_not_name_one() throws IOException {
    Path target = dir("pkg/buildomatic");
    file(VendorPreconditions.jdbcDir(target, "oracle").resolve("ojdbc8.jar"), "jar");

    CheckResult r =
        VendorPreconditions.jdbcDriver(
            Map.of("dbType", "oracle"), Map.of(), target, dir("inst/buildomatic"));

    assertThat(r).isInstanceOf(CheckResult.Pass.class);
  }

  // ------------------------------------------------------ password storage

  @Test
  void should_warn_when_the_target_password_storage_settings_differ_or_are_new()
      throws IOException {
    Path old = dir("old-webapp");
    Path target = dir("pkg/jasperserver-pro");
    Path config = target.resolve("WEB-INF").resolve(VendorPreconditions.PASSWORD_STORAGE_CONFIG);
    file(config, "password.strategy=modern\npassword.algorithm=pbkdf2\n");

    Optional<String> fresh =
        VendorPreconditions.passwordStorageWarning(old, Optional.of(target), false);
    file(
        old.resolve("WEB-INF").resolve(VendorPreconditions.PASSWORD_STORAGE_CONFIG),
        "password.strategy=legacy\n");
    Optional<String> changed =
        VendorPreconditions.passwordStorageWarning(old, Optional.of(target), true);

    assertThat(fresh)
        .get()
        .asString()
        .contains("password.strategy=modern")
        .contains("no counterpart")
        .contains("--migrate-passwords");
    assertThat(changed).get().asString().contains("differs from").contains("runs it after");
  }

  @Test
  void should_stay_quiet_when_the_settings_match_or_the_target_is_a_war() throws IOException {
    Path old = dir("old-webapp");
    Path target = dir("pkg/jasperserver-pro");
    file(target.resolve("WEB-INF").resolve(VendorPreconditions.PASSWORD_STORAGE_CONFIG), "a=b\n");
    file(old.resolve("WEB-INF").resolve(VendorPreconditions.PASSWORD_STORAGE_CONFIG), "a=b\n");

    assertThat(VendorPreconditions.passwordStorageWarning(old, Optional.of(target), false))
        .isEmpty();
    assertThat(VendorPreconditions.passwordStorageWarning(old, Optional.empty(), false)).isEmpty();
  }

  // ------------------------------------------------------------ 9.0 JNDI

  @Test
  void should_list_the_analytics_resources_a_context_xml_lacks() throws IOException {
    Path both =
        file(
            tmp.resolve("both.xml"),
            "<Context><Resource name=\"jdbc/jasperserverSystemAnalytics\"/>"
                + "<Resource name=\"jdbc/jasperserverAuditAnalytics\"/></Context>");
    Path one =
        file(
            tmp.resolve("one.xml"),
            "<Context><Resource name=\"jdbc/jasperserverSystemAnalytics\"/></Context>");

    assertThat(VendorPreconditions.missingAnalyticsJndi(both)).isEmpty();
    assertThat(VendorPreconditions.missingAnalyticsJndi(one))
        .containsExactly("jdbc/jasperserverAuditAnalytics");
    assertThat(VendorPreconditions.missingAnalyticsJndi(tmp.resolve("none.xml")))
        .containsExactlyElementsOf(VendorPreconditions.ANALYTICS_JNDI);
  }
}
