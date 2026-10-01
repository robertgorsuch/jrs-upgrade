package com.jaspersoft.jrsupgrade.jrs.vendor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.jrs.FakePlatform;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BuildomaticLocatorTest {

  @TempDir Path install;
  Path buildomatic;

  @BeforeEach
  void layout() throws IOException {
    buildomatic = install.resolve("buildomatic");
    Files.createDirectories(buildomatic.resolve("conf_source/db/postgresql/jdbc"));
    Files.createDirectories(buildomatic.resolve("conf_source/db/oracle/jdbc"));
    for (String name : Buildomatic.SCRIPT_NAMES) {
      Files.writeString(buildomatic.resolve(name + ".bat"), "@echo off\r\n");
      Files.writeString(buildomatic.resolve(name + ".sh"), "#!/bin/sh\n");
    }
    Files.writeString(
        buildomatic.resolve("conf_source/db/postgresql/jdbc/postgresql-42.7.3.jar"), "jar");
    Files.writeString(buildomatic.resolve("conf_source/db/postgresql/jdbc/README.txt"), "x");
    Files.writeString(
        buildomatic.resolve("default_master.properties"),
        "appServerType=tomcat\n"
            + "appServerDir=C:\\\\jrs\\\\tomcat\n"
            + "dbType=postgresql\n"
            + "dbHost=localhost\n"
            + "dbUsername=jasperdb\n"
            + "dbPassword=hunter2\n"
            + "encrypt.done=true\n"
            + "keystorePass=ksSecret\n"
            + "KEYSTORE_PASSWD_SEED=seed\n"
            + "sysPassword=oracleSecret\n",
        StandardCharsets.ISO_8859_1);
  }

  @ParameterizedTest
  @EnumSource(Platform.OsFamily.class)
  void should_pick_platform_script_extension_when_both_exist(Platform.OsFamily os) {
    BuildomaticLocator locator = new BuildomaticLocator(new FakePlatform(os));

    Optional<Buildomatic> found = locator.locate(install);

    assertThat(found).isPresent();
    Buildomatic b = found.get();
    String ext = os == Platform.OsFamily.WINDOWS ? ".bat" : ".sh";
    assertThat(b.dir()).isEqualTo(buildomatic);
    assertThat(b.scriptFor("js-export")).contains(buildomatic.resolve("js-export" + ext));
    assertThat(b.scriptFor("js-import")).contains(buildomatic.resolve("js-import" + ext));
    assertThat(b.scriptFor("js-ant")).contains(buildomatic.resolve("js-ant" + ext));
    assertThat(b.scriptFor("js-nope")).isEmpty();
    assertThat(b.missingScripts()).isEmpty();
    assertThat(b.masterPropertiesFile()).contains(buildomatic.resolve("default_master.properties"));
  }

  @Test
  void should_strip_every_password_key_when_parsing_master_properties() {
    Buildomatic b =
        new BuildomaticLocator(new FakePlatform(Platform.OsFamily.LINUX))
            .locate(install)
            .orElseThrow();

    assertThat(b.masterProperties())
        .containsEntry("appServerType", "tomcat")
        .containsEntry("dbType", "postgresql")
        .containsEntry("dbUsername", "jasperdb")
        .containsEntry("encrypt.done", "true")
        .doesNotContainKeys("dbPassword", "keystorePass", "KEYSTORE_PASSWD_SEED", "sysPassword");
    assertThat(b.masterProperties().values())
        .noneMatch(v -> v.contains("hunter2") || v.contains("Secret"));
  }

  @Test
  void should_list_driver_jars_when_driver_dir_exists() {
    Buildomatic b =
        new BuildomaticLocator(new FakePlatform(Platform.OsFamily.LINUX))
            .locate(install)
            .orElseThrow();

    Optional<JdbcDriverDir> pg = b.driverDir(Config.DatabaseType.POSTGRESQL);
    Optional<JdbcDriverDir> oracle = b.driverDir("Oracle");

    assertThat(pg).isPresent();
    assertThat(pg.get().dir()).isEqualTo(buildomatic.resolve("conf_source/db/postgresql/jdbc"));
    assertThat(pg.get().jars()).hasSize(1);
    assertThat(pg.get().jars().get(0).getFileName().toString()).isEqualTo("postgresql-42.7.3.jar");
    assertThat(oracle).isPresent();
    assertThat(oracle.get().jars()).isEmpty();
    assertThat(b.driverDir("mysql")).isEmpty();
  }

  @Test
  void should_report_missing_scripts_when_layout_is_partial() throws IOException {
    Files.delete(buildomatic.resolve("js-ant.sh"));
    Buildomatic b =
        new BuildomaticLocator(new FakePlatform(Platform.OsFamily.LINUX))
            .locate(install)
            .orElseThrow();

    assertThat(b.missingScripts()).containsExactly("js-ant");
  }

  @Test
  void should_return_empty_when_no_buildomatic_dir(@TempDir Path elsewhere) {
    assertThat(new BuildomaticLocator(new FakePlatform(Platform.OsFamily.LINUX)).locate(elsewhere))
        .isEmpty();
  }

  @Test
  void should_tolerate_missing_master_properties_when_locating() throws IOException {
    Files.delete(buildomatic.resolve("default_master.properties"));
    Buildomatic b =
        new BuildomaticLocator(new FakePlatform(Platform.OsFamily.WINDOWS))
            .locate(install)
            .orElseThrow();

    assertThat(b.masterPropertiesFile()).isEmpty();
    assertThat(b.masterProperties()).isEmpty();
  }
}
