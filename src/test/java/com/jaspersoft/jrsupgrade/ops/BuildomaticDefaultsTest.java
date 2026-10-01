package com.jaspersoft.jrsupgrade.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.config.Config;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #73: the repository database settings come from the installed buildomatic's
 * default_master.properties unless config.yaml gives them, so the two cannot drift apart.
 */
class BuildomaticDefaultsTest {

  @TempDir Path tmp;

  private static String yaml(Path install, String database) {
    return """
        server:
          baseUrl: http://localhost:8081/jasperserver-pro
          installDir: %s
        %s
        """
        .formatted(install.toString().replace("\\", "/"), database);
  }

  @Test
  void should_fill_the_database_settings_config_yaml_leaves_out() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    try (FakeServices fake =
        FakeServices.in(tmp.resolve("home"))
            .yaml(yaml(install, "database:\n  passwordRef: env:JRS_DB_PASSWORD"))) {
      Services s = fake.build();

      BuildomaticDefaults.Result result = BuildomaticDefaults.apply(s.config(), s.platform());

      Config.Database db = result.config().database();
      assertThat(db.type()).contains(Config.DatabaseType.POSTGRESQL);
      assertThat(db.url()).contains("jdbc:postgresql://db.example.internal:5433/jasperserver");
      assertThat(db.username()).contains("jasperdb");
      assertThat(db.passwordRef().map(r -> r.render())).contains("env:JRS_DB_PASSWORD");
      assertThat(result.filled())
          .containsOnlyKeys("database.type", "database.url", "database.username");
      assertThat(result.filled().get("database.url").getFileName())
          .hasToString("default_master.properties");
    }
  }

  @Test
  void should_keep_a_value_config_yaml_gives_and_report_where_it_disagrees() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    try (FakeServices fake =
        FakeServices.in(tmp.resolve("home"))
            .yaml(yaml(install, "database:\n  username: reporting"))) {
      Services s = fake.build();

      BuildomaticDefaults.Result result = BuildomaticDefaults.apply(s.config(), s.platform());

      assertThat(result.config().database().username()).contains("reporting");
      assertThat(result.config().database().type()).contains(Config.DatabaseType.POSTGRESQL);
      assertThat(result.filled()).doesNotContainKey("database.username");
      assertThat(BuildomaticDefaults.disagreements(result.config(), s.platform()))
          .containsOnlyKeys("database.username")
          .hasEntrySatisfying(
              "database.username", v -> assertThat(v).contains("reporting").contains("jasperdb"));
    }
  }

  @Test
  void should_read_nothing_when_no_local_installation_is_configured() throws Exception {
    try (FakeServices fake =
        FakeServices.in(tmp.resolve("home"))
            .yaml("server:\n  baseUrl: http://jrs.example.com/jasperserver-pro\n")) {
      Services s = fake.build();

      BuildomaticDefaults.Result result = BuildomaticDefaults.apply(s.config(), s.platform());

      assertThat(result.config()).isEqualTo(s.config());
      assertThat(result.filled()).isEmpty();
    }
  }
}
