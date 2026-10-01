package com.jaspersoft.jrsupgrade.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.platform.ServiceConfig;
import com.jaspersoft.jrsupgrade.core.secrets.SecretRef;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigWriterTest {

  @TempDir Path tmp;

  @Test
  void should_round_trip_through_loader_when_every_key_is_present() throws IOException {
    Config original = fullConfig();
    JrsUpgradeHome home = new JrsUpgradeHome(tmp.resolve("home"));

    ConfigWriter.write(original, home.configFile());
    Config reloaded = new ConfigLoader().load(home, Map.of(), Map.of());

    assertThat(reloaded).isEqualTo(original);
  }

  @Test
  void should_round_trip_defaults_when_nothing_is_configured() throws IOException {
    JrsUpgradeHome home = new JrsUpgradeHome(tmp);

    ConfigWriter.write(Config.defaults(), home.configFile());

    assertThat(new ConfigLoader().load(home, Map.of(), Map.of())).isEqualTo(Config.defaults());
  }

  @Test
  void should_use_spec_key_names_and_omit_absent_keys_when_writing() throws IOException {
    Path file = tmp.resolve("config.yaml");
    Config c = fullConfig();

    ConfigWriter.write(c, file);
    String yaml = Files.readString(file, StandardCharsets.UTF_8);

    assertThat(yaml)
        .contains("baseUrl:")
        .contains("webappName: jasperserver-pro")
        .contains("passwordRef: env:JRS_PASSWORD")
        .contains("kind: windows-service")
        .contains("stopTimeoutSeconds: 60")
        .contains("trustStore:")
        .contains("retentionDays: 7")
        .doesNotContain("driverDir")
        .doesNotContain("hunter2");
  }

  @Test
  void should_round_trip_force_stop_after_seconds_and_omit_it_when_unset() throws IOException {
    Config base = Config.defaults();
    Config original =
        new Config(
            base.server(),
            new Config.Service(
                Optional.of(ServiceConfig.Kind.CATALINA),
                Optional.empty(),
                Optional.of(Path.of("/opt/tomcat/bin/catalina.sh")),
                180,
                Optional.of(60)),
            base.database(),
            base.vendor(),
            base.network(),
            base.backups(),
            base.smoke());
    JrsUpgradeHome home = new JrsUpgradeHome(tmp.resolve("home"));

    ConfigWriter.write(original, home.configFile());
    Config reloaded = new ConfigLoader().load(home, Map.of(), Map.of());

    assertThat(reloaded).isEqualTo(original);
    assertThat(ConfigWriter.render(original)).contains("forceStopAfterSeconds: 60");
    assertThat(ConfigWriter.render(base)).doesNotContain("forceStopAfterSeconds");
  }

  @Test
  void should_never_emit_secret_values_when_rendering() {
    String yaml = ConfigWriter.render(fullConfig());

    assertThat(yaml).contains("enc:db").doesNotContain("password:");
  }

  private static Config fullConfig() {
    return new Config(
        new Config.Server(
            Optional.of(URI.create("http://localhost:8080/jasperserver-pro")),
            Optional.of(Config.WebappName.JASPERSERVER_PRO),
            Optional.of(Path.of("/opt/jrs")),
            Optional.of(Path.of("/opt/jrs/apache-tomcat")),
            Optional.of(Path.of("/mnt/vendor/buildomatic")),
            Optional.of("jasperserver"),
            new Config.Auth(
                Config.AuthMode.TOKEN,
                Optional.of("jasperadmin"),
                Optional.of(new SecretRef.Env("JRS_PASSWORD")))),
        new Config.Service(
            Optional.of(ServiceConfig.Kind.WINDOWS_SERVICE),
            Optional.of("jasperreportsTomcat"),
            Optional.of(Path.of("/opt/jrs/ctlscript.sh")),
            60),
        new Config.Database(
            Optional.of(Config.DatabaseType.MSSQL),
            Optional.of("jdbc:sqlserver://db:1433;databaseName=jasperserver"),
            Optional.of("jasperdb"),
            Optional.of(new SecretRef.Enc("db")),
            Optional.empty()),
        new Config.Vendor(Optional.of(Path.of("/opt/jrs/java"))),
        new Config.Network(
            Config.NetworkMode.PUBLIC,
            new Config.Proxy(
                Optional.of("proxy.local"),
                Optional.of(3128),
                Optional.of("proxyuser"),
                Optional.of(new SecretRef.File(Path.of("/run/secrets/proxy")))),
            new Config.TrustStore(
                Optional.of(Path.of("/etc/ssl/ts.p12")), Optional.of(new SecretRef.Env("TS")))),
        new Config.Backups(7, 3),
        new Config.Smoke(Optional.of("/public/Samples/Reports/AllAccounts")));
  }
}
