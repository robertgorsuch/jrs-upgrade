package com.jaspersoft.jrsupgrade.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #74: jrs-upgrade.properties, with the dotted keys --set already uses, as an alternative to
 * config.yaml for administrators used to buildomatic's properties files.
 */
class PropertiesConfigTest {

  @TempDir Path tmp;

  private final ConfigLoader loader = new ConfigLoader();

  @Test
  void should_load_the_same_configuration_as_yaml_when_the_home_has_jrsupgrade_properties()
      throws IOException {
    JrsUpgradeHome home = new JrsUpgradeHome(Files.createDirectories(tmp.resolve("home")));
    Files.writeString(
        home.root().resolve("jrs-upgrade.properties"),
        """
        # jrs-upgrade settings
        server.baseUrl = http://localhost:8080/jasperserver-pro
        server.installDir=C:\\Jaspersoft\\jasperreports-server-10.0.0
        server.auth.username: superuser
        server.auth.passwordRef=enc:JRS_PASSWORD
        network.mode=public
        network.proxy.noProxy=.corp.example, localhost
        """,
        StandardCharsets.UTF_8);

    Config c = loader.load(home, Map.of(), Map.of());

    assertThat(home.configFile().getFileName()).hasToString("jrs-upgrade.properties");
    assertThat(c.server().baseUrl()).contains(URI.create("http://localhost:8080/jasperserver-pro"));
    assertThat(c.server().installDir())
        .as("a backslash is taken literally, not as an escape")
        .contains(Path.of("C:\\Jaspersoft\\jasperreports-server-10.0.0"));
    assertThat(c.server().auth().username()).contains("superuser");
    assertThat(c.network().proxy().noProxy()).containsExactly(".corp.example", "localhost");
  }

  @Test
  void should_refuse_a_home_with_both_config_files() throws IOException {
    JrsUpgradeHome home = new JrsUpgradeHome(Files.createDirectories(tmp.resolve("home")));
    Files.writeString(home.root().resolve("config.yaml"), "server: {}\n", StandardCharsets.UTF_8);
    Files.writeString(home.root().resolve("jrs-upgrade.properties"), "\n", StandardCharsets.UTF_8);

    assertThatThrownBy(() -> loader.load(home, Map.of(), Map.of()))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("config.yaml")
        .hasMessageContaining("jrs-upgrade.properties");
  }

  @Test
  void should_name_the_line_of_an_unknown_or_repeated_key() throws IOException {
    Path file = tmp.resolve("jrs-upgrade.properties");
    Files.writeString(
        file, "server.baseUrl=http://a\nserver.baseUlr=http://b\n", StandardCharsets.UTF_8);

    assertThatThrownBy(() -> loader.load(file, Map.of(), Map.of()))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("server.baseUlr")
        .hasMessageContaining("line 2");

    Files.writeString(
        file, "server.baseUrl=http://a\nserver.baseUrl=http://b\n", StandardCharsets.UTF_8);
    assertThatThrownBy(() -> loader.load(file, Map.of(), Map.of()))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("server.baseUrl")
        .hasMessageContaining("line 2");
  }

  @Test
  void should_round_trip_through_the_properties_writer() throws IOException {
    Path yaml = tmp.resolve("full.yaml");
    Files.writeString(
        yaml,
        """
        server:
          baseUrl: https://jrs.example.com:8443/jasperserver-pro
          installDir: /opt/jrs
          auth:
            username: superuser
            passwordRef: env:JRS_PASSWORD
        service:
          kind: systemd
          name: jasperserver
        network:
          mode: public
          proxy:
            host: proxy.example
            port: 3128
            noProxy: [".corp.example", "localhost"]
        backups:
          retentionDays: 14
        """,
        StandardCharsets.UTF_8);
    Config original = loader.load(yaml, Map.of(), Map.of());
    Path properties = tmp.resolve("jrs-upgrade.properties");

    ConfigWriter.write(original, properties);

    List<String> lines = Files.readAllLines(properties, StandardCharsets.UTF_8);
    assertThat(lines)
        .contains("server.baseUrl=https://jrs.example.com:8443/jasperserver-pro")
        .contains("network.proxy.noProxy=.corp.example,localhost")
        .noneMatch(l -> l.contains("{") || l.startsWith("  "));
    assertThat(loader.load(properties, Map.of(), Map.of())).isEqualTo(original);
    assertThat(ConfigWriter.renderProperties(original)).contains("backups.retentionDays=14");
  }

  @Test
  void should_split_a_list_value_given_on_the_command_line() {
    Config c =
        loader.withOverrides(
            Config.defaults(), Map.of("network.proxy.noProxy", "a.example, .b.example"));

    assertThat(c.network().proxy().noProxy()).containsExactly("a.example", ".b.example");
  }
}
