package com.jaspersoft.jrsupgrade.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.platform.ServiceConfig;
import com.jaspersoft.jrsupgrade.core.secrets.SecretRef;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {

  @TempDir Path tmp;

  private final ConfigLoader loader = new ConfigLoader();

  @Test
  void should_apply_schema_defaults_when_config_file_is_missing() {
    Config c = loader.load(new JrsUpgradeHome(tmp), Map.of(), Map.of());

    assertThat(c).isEqualTo(Config.defaults());
    assertThat(c.server().baseUrl()).isEmpty();
    assertThat(c.server().auth().mode()).isEqualTo(Config.AuthMode.BASIC);
    assertThat(c.network().mode()).isEqualTo(Config.NetworkMode.ISOLATED);
    assertThat(c.backups().retentionDays()).isEqualTo(30);
    assertThat(c.backups().maxSnapshots()).isEqualTo(20);
    assertThat(c.service().stopTimeoutSeconds()).isEqualTo(180);
  }

  /** Issue #70: config keys and config show say where each value comes from. */
  @Test
  void should_name_the_source_of_each_key_when_flag_env_file_and_default_mix() throws IOException {
    Path file = tmp.resolve("config.yaml");
    Files.writeString(
        file,
        "server:\n  baseUrl: http://file:8080/jasperserver-pro\n  webappName: jasperserver-pro\n",
        StandardCharsets.UTF_8);

    Map<String, ConfigLoader.Source> sources =
        loader.sources(
            file,
            Map.of("JRS_UPGRADE_NETWORK_PROXY_PORT", "7500"),
            Map.of("server.auth.username", "superuser"));

    assertThat(sources.get("server.baseUrl").origin()).isEqualTo(ConfigLoader.Origin.FILE);
    assertThat(sources.get("network.proxy.port").origin())
        .isEqualTo(ConfigLoader.Origin.ENVIRONMENT);
    assertThat(sources.get("network.proxy.port").detail())
        .isEqualTo("JRS_UPGRADE_NETWORK_PROXY_PORT");
    assertThat(sources.get("server.auth.username").origin()).isEqualTo(ConfigLoader.Origin.FLAG);
    assertThat(sources.get("backups.retentionDays").origin())
        .isEqualTo(ConfigLoader.Origin.DEFAULT);
    assertThat(sources.keySet()).containsExactlyElementsOf(loader.knownKeys());
  }

  /** Field test 2, G3: a leading {@code ~} in a path value means the operator's home. */
  @Test
  void should_expand_a_leading_tilde_in_path_values_from_the_file_the_environment_and_flags()
      throws IOException {
    Path file = tmp.resolve("config.yaml");
    Files.writeString(
        file,
        "server:\n  baseUrl: http://x:8080/jasperserver-pro\n  webappName: jasperserver-pro\n"
            + "  installDir: ~/jrs\n",
        StandardCharsets.UTF_8);
    Path home = tmp.resolve("operator-home");

    Config config =
        loader.load(
            file,
            Map.of("HOME", home.toString(), "JRS_UPGRADE_SERVER_TOMCAT_DIR", "~/tomcat"),
            Map.of("vendor.javaHome", "~/jdk"));

    assertThat(config.server().installDir()).contains(home.resolve("jrs"));
    assertThat(config.server().tomcatDir()).contains(home.resolve("tomcat"));
    assertThat(config.vendor().javaHome()).contains(home.resolve("jdk"));
  }

  /** A saved path whose directory vanished still loads; doctor is what reports it. */
  @Test
  void should_still_load_a_saved_config_whose_directory_vanished() throws IOException {
    Path file = tmp.resolve("config.yaml");
    Files.writeString(
        file,
        "server:\n  baseUrl: http://x:8080/jasperserver-pro\n  webappName: jasperserver-pro\n"
            + "  buildomaticDir: "
            + tmp.resolve("gone").toString().replace("\\", "/")
            + "\n",
        StandardCharsets.UTF_8);

    Config config = loader.load(file, Map.of(), Map.of());

    assertThat(config.server().buildomaticDir()).contains(tmp.resolve("gone"));
  }

  /** Issue #70: config set changes one key of the file and validates the result. */
  @Test
  void should_change_one_key_of_the_file_and_keep_the_others_when_setting() throws IOException {
    Path file = tmp.resolve("config.yaml");
    Files.writeString(
        file,
        "server:\n  baseUrl: http://old:8080/jasperserver-pro\n  runAsUser: tomcat\n",
        StandardCharsets.UTF_8);

    Config changed = loader.fileWith(file, "server.baseUrl", "https://new:8443/jasperserver-pro");
    Config reset = loader.fileWithout(file, "server.runAsUser");

    assertThat(changed.server().baseUrl())
        .contains(URI.create("https://new:8443/jasperserver-pro"));
    assertThat(changed.server().runAsUser()).contains("tomcat");
    assertThat(reset.server().runAsUser()).isEmpty();
    assertThat(reset.server().baseUrl()).contains(URI.create("http://old:8080/jasperserver-pro"));
  }

  @Test
  void should_refuse_an_unknown_key_or_an_invalid_value_when_setting() throws IOException {
    Path file = tmp.resolve("config.yaml");

    assertThatThrownBy(() -> loader.fileWith(file, "server.baseUlr", "http://x"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("server.baseUlr")
        .satisfies(
            t ->
                assertThat(((ConfigException) t).remediation())
                    .contains("jrs-upgrade config keys"));
    assertThatThrownBy(() -> loader.fileWith(file, "network.proxy.port", "seventy"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("network.proxy.port");
  }

  /** Issue #63: init applies the operator's edits the way --set applies an override. */
  @Test
  void should_apply_and_coerce_overrides_on_a_config_when_values_are_valid() {
    Config c =
        loader.withOverrides(
            Config.defaults(),
            Map.of(
                "server.baseUrl", "https://jrs.example.com:8443/jasperserver-pro",
                "server.auth.passwordRef", "enc:JRS_PASSWORD",
                "network.proxy.port", "7500"));

    assertThat(c.server().baseUrl())
        .contains(URI.create("https://jrs.example.com:8443/jasperserver-pro"));
    assertThat(c.server().auth().passwordRef().map(SecretRef::render)).contains("enc:JRS_PASSWORD");
    assertThat(c.network().proxy().port()).contains(7500);
  }

  @Test
  void should_refuse_an_override_naming_the_key_when_the_value_is_invalid() {
    assertThatThrownBy(
            () ->
                loader.withOverrides(Config.defaults(), Map.of("network.proxy.port", "not-a-port")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("network.proxy.port");
  }

  /** Issue #47: every env: reference in the configuration, by variable name. */
  @Test
  void should_list_the_env_secret_names_when_references_use_env_file_and_enc() throws IOException {
    Files.writeString(
        tmp.resolve("config.yaml"),
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
          auth:
            passwordRef: env:JRS_PASSWORD
        database:
          passwordRef: enc:DB
        network:
          proxy:
            passwordRef: env:PROXY_PW
        """,
        StandardCharsets.UTF_8);

    Config c = loader.load(new JrsUpgradeHome(tmp), Map.of(), Map.of());

    assertThat(c.secretRefs()).hasSize(3);
    assertThat(c.envSecretNames()).containsExactlyInAnyOrder("JRS_PASSWORD", "PROXY_PW");
  }

  /** Issue #45: {@code server.auth.tokenLocation} defaults to query and accepts header. */
  @Test
  void should_read_token_location_when_set_and_default_to_query_when_absent() throws IOException {
    assertThat(
            loader
                .load(new JrsUpgradeHome(tmp), Map.of(), Map.of())
                .server()
                .auth()
                .tokenLocation())
        .isEqualTo(Config.TokenLocation.QUERY);
    Files.writeString(
        tmp.resolve("config.yaml"),
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
          auth:
            mode: token
            tokenLocation: header
            passwordRef: env:JRS_TOKEN
        """,
        StandardCharsets.UTF_8);

    Config c = loader.load(new JrsUpgradeHome(tmp), Map.of(), Map.of());

    assertThat(c.server().auth().tokenLocation()).isEqualTo(Config.TokenLocation.HEADER);
  }

  @Test
  void should_read_every_block_when_file_is_complete() throws IOException {
    write(
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
          webappName: jasperserver-pro
          installDir: /opt/jrs
          tomcatDir: /opt/jrs/apache-tomcat
          buildomaticDir: //fileserver/jrs/buildomatic
          runAsUser: jasperserver
          auth:
            mode: form
            username: jasperadmin
            passwordRef: env:JRS_PASSWORD
        service:
          kind: windows-service
          name: jasperreportsTomcat
          stopTimeoutSeconds: 60
        database:
          type: postgresql
          url: jdbc:postgresql://localhost:5432/jasperserver
          username: jasperdb
          passwordRef: enc:db
          driverDir: null
        vendor:
          javaHome: /opt/jrs/java
        network:
          mode: public
          proxy: { host: proxy.local, port: 3128, username: u, passwordRef: file:/run/secrets/p }
          trustStore: { path: /etc/ssl/ts.p12, passwordRef: env:TS }
        backups:
          retentionDays: 7
          maxSnapshots: 3
        smoke:
          reportUri: /public/Samples/Reports/AllAccounts
        """);

    Config c = loader.load(new JrsUpgradeHome(tmp), Map.of(), Map.of());

    assertThat(c.server().baseUrl()).contains(URI.create("http://localhost:8080/jasperserver-pro"));
    assertThat(c.server().webappName()).contains(Config.WebappName.JASPERSERVER_PRO);
    assertThat(c.server().installDir()).contains(Path.of("/opt/jrs"));
    assertThat(c.server().buildomaticDir()).contains(Path.of("//fileserver/jrs/buildomatic"));
    assertThat(c.server().auth().mode()).isEqualTo(Config.AuthMode.FORM);
    assertThat(c.server().auth().passwordRef()).contains(new SecretRef.Env("JRS_PASSWORD"));
    assertThat(c.service().kind()).contains(ServiceConfig.Kind.WINDOWS_SERVICE);
    assertThat(c.service().stopTimeoutSeconds()).isEqualTo(60);
    assertThat(c.database().type()).contains(Config.DatabaseType.POSTGRESQL);
    assertThat(c.database().passwordRef()).contains(new SecretRef.Enc("db"));
    assertThat(c.database().driverDir()).isEmpty();
    assertThat(c.vendor().javaHome()).contains(Path.of("/opt/jrs/java"));
    assertThat(c.network().mode()).isEqualTo(Config.NetworkMode.PUBLIC);
    assertThat(c.network().proxy().port()).contains(3128);
    assertThat(c.network().proxy().passwordRef())
        .contains(new SecretRef.File(Path.of("/run/secrets/p")));
    assertThat(c.network().trustStore().path()).contains(Path.of("/etc/ssl/ts.p12"));
    assertThat(c.backups()).isEqualTo(new Config.Backups(7, 3));
    assertThat(c.smoke().reportUri()).contains("/public/Samples/Reports/AllAccounts");
  }

  @Test
  void should_prefer_flag_over_env_over_file_when_all_three_set_the_same_key() throws IOException {
    write(
        "server:\n  baseUrl: http://file:8080/jasperserver\nnetwork:\n  proxy:\n    port:"
            + " 1000\n");
    Map<String, String> env =
        Map.of(
            "JRS_UPGRADE_SERVER_BASE_URL", "http://env:8080/jasperserver",
            "JRS_UPGRADE_NETWORK_PROXY_PORT", "2000",
            "JRS_UPGRADE_BACKUPS_RETENTION_DAYS", "5");
    Map<String, String> flags = Map.of("server.baseUrl", "http://flag:8080/jasperserver");

    Config c = loader.load(new JrsUpgradeHome(tmp), env, flags);

    assertThat(c.server().baseUrl()).contains(URI.create("http://flag:8080/jasperserver"));
    assertThat(c.network().proxy().port()).contains(2000);
    assertThat(c.backups().retentionDays()).isEqualTo(5);
    assertThat(c.backups().maxSnapshots()).isEqualTo(20);
  }

  /** Review finding 2.9: hosts that must not go through the proxy are listed under the proxy. */
  @Test
  void should_parse_the_proxy_bypass_list_when_given() throws IOException {
    write(
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
        network:
          proxy:
            host: proxy.local
            port: 3128
            noProxy: [".corp.example", "intranet"]
        """);

    Config c = loader.load(new JrsUpgradeHome(tmp), Map.of(), Map.of());

    assertThat(c.network().proxy().host()).contains("proxy.local");
    assertThat(c.network().proxy().noProxy()).containsExactly(".corp.example", "intranet");
    assertThat(Config.Proxy.empty().noProxy()).isEmpty();
  }

  @Test
  void should_map_camel_case_paths_to_upper_snake_env_keys() {
    assertThat(ConfigLoader.envKey("server.baseUrl")).isEqualTo("JRS_UPGRADE_SERVER_BASE_URL");
    assertThat(ConfigLoader.envKey("server.auth.passwordRef"))
        .isEqualTo("JRS_UPGRADE_SERVER_AUTH_PASSWORD_REF");
    assertThat(ConfigLoader.envKey("network.trustStore.path"))
        .isEqualTo("JRS_UPGRADE_NETWORK_TRUST_STORE_PATH");
    assertThat(ConfigLoader.envKey("service.stopTimeoutSeconds"))
        .isEqualTo("JRS_UPGRADE_SERVICE_STOP_TIMEOUT_SECONDS");
    assertThat(loader.knownKeys()).contains("server.baseUrl", "network.trustStore.path");
  }

  @Test
  void should_ignore_unrelated_jrsupgrade_variables_when_reading_env() {
    Map<String, String> env =
        Map.of(
            "JRS_UPGRADE_HOME", tmp.toString(),
            "JRS_UPGRADE_PASSPHRASE", "not-a-config-key",
            "JRS_UPGRADE_BACKUPS_RETENTION_DAYS", "5",
            "JRS_UPGRADE_SERVER_BASE_URL", "http://h/jasperserver");

    Config c = loader.load(new JrsUpgradeHome(tmp), env, Map.of());

    assertThat(c.backups().retentionDays()).isEqualTo(5);
    assertThat(c.server().baseUrl()).contains(URI.create("http://h/jasperserver"));
  }

  @Test
  void should_list_every_violation_when_several_keys_are_invalid() throws IOException {
    write(
        """
        server:
          baseUrl: 42
          auth:
            mode: magic
        backups:
          retentionDays: -5
        bogus: 1
        """);

    assertThatThrownBy(() -> loader.load(new JrsUpgradeHome(tmp), Map.of(), Map.of()))
        .isInstanceOf(ConfigException.class)
        .satisfies(
            t -> {
              ConfigException e = (ConfigException) t;
              assertThat(e.violations()).hasSizeGreaterThanOrEqualTo(4);
              assertThat(e.violations()).anyMatch(v -> v.startsWith("server.baseUrl: "));
              assertThat(e.violations()).anyMatch(v -> v.startsWith("server.auth.mode: "));
              assertThat(e.violations()).anyMatch(v -> v.startsWith("backups.retentionDays: "));
              assertThat(e.violations()).anyMatch(v -> v.contains("bogus"));
              assertThat(e.getMessage())
                  .contains("backups.retentionDays: ")
                  .contains(e.remediation());
            });
  }

  @Test
  void should_reject_unknown_flag_key_when_schema_does_not_define_it() {
    assertThatThrownBy(
            () -> loader.load(new JrsUpgradeHome(tmp), Map.of(), Map.of("server.colour", "blue")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("colour");
  }

  @Test
  void should_report_type_error_when_env_integer_is_not_numeric() {
    assertThatThrownBy(
            () ->
                loader.load(
                    new JrsUpgradeHome(tmp),
                    Map.of("JRS_UPGRADE_BACKUPS_RETENTION_DAYS", "eighty"),
                    Map.of("server.baseUrl", "http://h/jasperserver")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("backups.retentionDays: ");
  }

  @Test
  void should_reject_malformed_secret_reference_when_present() throws IOException {
    write("server:\n  baseUrl: http://h/jasperserver\n  auth:\n    passwordRef: vault:x\n");

    assertThatThrownBy(() -> loader.load(new JrsUpgradeHome(tmp), Map.of(), Map.of()))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("server.auth.passwordRef");
  }

  @Test
  void should_tell_operator_to_run_init_when_server_is_absent() {
    Config c = loader.load(new JrsUpgradeHome(tmp), Map.of(), Map.of());

    assertThatThrownBy(() -> ConfigLoader.requireServer(c))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("run jrs-upgrade init");
  }

  @Test
  void should_return_base_url_when_server_is_configured() {
    Config c =
        loader.load(
            new JrsUpgradeHome(tmp), Map.of(), Map.of("server.baseUrl", "http://h/jasperserver"));

    assertThat(ConfigLoader.requireServer(c)).isEqualTo(URI.create("http://h/jasperserver"));
  }

  @Test
  void should_build_service_config_when_kind_is_set() throws IOException {
    write("service:\n  kind: ctlscript\n  scriptPath: /opt/jrs/ctlscript.sh\n");

    ServiceConfig sc = loader.load(new JrsUpgradeHome(tmp), Map.of(), Map.of()).toServiceConfig();

    assertThat(sc.kind()).isEqualTo(ServiceConfig.Kind.CTLSCRIPT);
    assertThat(sc.scriptPath()).contains(Path.of("/opt/jrs/ctlscript.sh"));
    assertThat(sc.name()).isEmpty();
    assertThat(sc.stopTimeout()).isEqualTo(Duration.ofMinutes(3));
  }

  @Test
  void should_fail_closed_when_service_kind_is_missing() {
    assertThatThrownBy(() -> Config.defaults().toServiceConfig())
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("service.kind");
  }

  @Test
  void should_treat_empty_file_as_defaults_when_file_has_no_content() throws IOException {
    write("# nothing here\n");

    assertThat(loader.load(new JrsUpgradeHome(tmp), Map.of(), Map.of()))
        .isEqualTo(Config.defaults());
  }

  @Test
  void should_report_parse_error_when_yaml_is_malformed() throws IOException {
    write("server: [unclosed\n");

    assertThatThrownBy(() -> loader.load(new JrsUpgradeHome(tmp), Map.of(), Map.of()))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("cannot parse");
  }

  @Test
  void should_carry_force_stop_after_seconds_into_service_config_when_kind_is_catalina()
      throws IOException {
    write(
        """
        service:
          kind: catalina
          scriptPath: /opt/tomcat/bin/catalina.sh
          stopTimeoutSeconds: 180
          forceStopAfterSeconds: 60
        """);

    Config c = loader.load(new JrsUpgradeHome(tmp), Map.of(), Map.of());

    assertThat(c.service().forceStopAfterSeconds()).contains(60);
    assertThat(c.toServiceConfig().forceStopAfter()).contains(Duration.ofSeconds(60));
  }

  @Test
  void should_leave_force_stop_off_when_the_key_is_absent() throws IOException {
    write(
        """
        service:
          kind: catalina
          scriptPath: /opt/tomcat/bin/catalina.sh
        """);

    Config c = loader.load(new JrsUpgradeHome(tmp), Map.of(), Map.of());

    assertThat(c.service().forceStopAfterSeconds()).isEmpty();
    assertThat(c.toServiceConfig().forceStopAfter()).isEmpty();
    assertThat(Config.defaults().service().forceStopAfterSeconds()).isEmpty();
  }

  @Test
  void should_refuse_force_stop_after_seconds_when_kind_is_not_a_script_kind() throws IOException {
    write(
        """
        service:
          kind: windows-service
          name: jasperreportsTomcat
          forceStopAfterSeconds: 60
        """);

    Config c = loader.load(new JrsUpgradeHome(tmp), Map.of(), Map.of());

    assertThatThrownBy(c::toServiceConfig)
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("service.forceStopAfterSeconds")
        .hasMessageContaining("windows-service");
  }

  @Test
  void should_refuse_force_stop_after_seconds_when_not_below_the_stop_timeout() throws IOException {
    write(
        """
        service:
          kind: ctlscript
          scriptPath: /opt/jrs/ctlscript.sh
          stopTimeoutSeconds: 60
          forceStopAfterSeconds: 60
        """);

    Config c = loader.load(new JrsUpgradeHome(tmp), Map.of(), Map.of());

    assertThatThrownBy(c::toServiceConfig)
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("service.stopTimeoutSeconds");
  }

  @Test
  void should_report_a_violation_when_force_stop_after_seconds_is_zero() throws IOException {
    write(
        """
        service:
          kind: catalina
          scriptPath: /opt/tomcat/bin/catalina.sh
          forceStopAfterSeconds: 0
        """);

    assertThatThrownBy(() -> loader.load(new JrsUpgradeHome(tmp), Map.of(), Map.of()))
        .isInstanceOf(ConfigException.class);
  }

  /**
   * #154, ADR-0038: 2.0 tolerated a 1.x {@code console:} block with a warning; 2.1 refuses it on
   * every read, naming the ADR and the command that removes it.
   */
  @Test
  void should_refuse_a_console_block_naming_the_way_out_when_loading() throws IOException {
    Path file = tmp.resolve("config.yaml");
    Files.writeString(
        file,
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
        console:
          port: 7421
        """);

    assertThatThrownBy(() -> new ConfigLoader().load(file, Map.of(), Map.of()))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("console: in " + file)
        .hasMessageContaining("ADR-0038")
        .hasFieldOrPropertyWithValue(
            "remediation", "remove it with: jrs-upgrade config unset console");
  }

  @Test
  void should_ignore_a_console_environment_variable_when_loading() throws IOException {
    Config config =
        new ConfigLoader()
            .load(tmp.resolve("missing.yaml"), Map.of("JRS_UPGRADE_CONSOLE_PORT", "1"), Map.of());

    assertThat(config)
        .isEqualTo(new ConfigLoader().load(tmp.resolve("missing.yaml"), Map.of(), Map.of()));
  }

  @Test
  void should_refuse_a_console_key_naming_the_adr_when_setting() throws IOException {
    Path file = tmp.resolve("config.yaml");
    Files.writeString(file, "server:\n  baseUrl: http://localhost:8080/jasperserver-pro\n");

    assertThatThrownBy(() -> new ConfigLoader().fileWith(file, "console.port", "7421"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("console.port is no longer used (ADR-0038)");
  }

  @Test
  void should_refuse_a_console_flag_naming_the_adr_when_loading() {
    assertThatThrownBy(
            () ->
                new ConfigLoader()
                    .load(tmp.resolve("missing.yaml"), Map.of(), Map.of("console.port", "7421")))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("console.port is no longer used (ADR-0038)");
  }

  /** #154: changing another key is refused too, until the console block is removed. */
  @Test
  void should_refuse_to_change_another_key_when_a_1x_file_still_has_a_console_block()
      throws IOException {
    Path file = tmp.resolve("config.yaml");
    Files.writeString(
        file,
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
        console:
          port: 7421
        """);

    assertThatThrownBy(() -> new ConfigLoader().fileWith(file, "backups.retentionDays", "10"))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("ADR-0038");
  }

  /** {@code config unset console} (ADR-0038): the block goes, everything else stays, silently. */
  @Test
  void should_drop_the_console_block_and_keep_the_rest_when_removing_console() throws IOException {
    Path file = tmp.resolve("config.yaml");
    Files.writeString(
        file,
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
        console:
          port: 7421
        """);
    Optional<Config> updated = new ConfigLoader().fileWithoutConsole(file);

    assertThat(updated).isPresent();
    assertThat(updated.get().server().baseUrl()).isPresent();
    ConfigWriter.write(updated.get(), file);
    assertThat(Files.readString(file, StandardCharsets.UTF_8)).doesNotContain("console");
  }

  @Test
  void should_drop_console_lines_when_removing_console_from_a_properties_file() throws IOException {
    Path file = tmp.resolve("config.properties");
    Files.writeString(
        file, "server.baseUrl=http://localhost:8080/jasperserver-pro\nconsole.port=7421\n");

    Optional<Config> updated = new ConfigLoader().fileWithoutConsole(file);

    assertThat(updated).isPresent();
    ConfigWriter.write(updated.get(), file);
    assertThat(Files.readString(file, StandardCharsets.UTF_8)).doesNotContain("console");
  }

  @Test
  void should_report_nothing_to_remove_when_the_file_has_no_console_entry() throws IOException {
    Path file = tmp.resolve("config.yaml");
    Files.writeString(file, "server:\n  baseUrl: http://localhost:8080/jasperserver-pro\n");

    assertThat(new ConfigLoader().fileWithoutConsole(file)).isEmpty();
    assertThat(new ConfigLoader().fileWithoutConsole(tmp.resolve("missing.yaml"))).isEmpty();
  }

  /**
   * #154: a {@code console.*} line in {@code jrs-upgrade.properties} is refused, naming its line.
   */
  @Test
  void should_refuse_a_console_line_naming_it_when_a_properties_file_still_has_one()
      throws IOException {
    Path file = tmp.resolve("config.properties");
    Files.writeString(
        file, "server.baseUrl=http://localhost:8080/jasperserver-pro\nconsole.port=7421\n");

    assertThatThrownBy(() -> new ConfigLoader().load(file, Map.of(), Map.of()))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("console.port at " + file + " line 2")
        .hasMessageContaining("ADR-0038");
    assertThatThrownBy(() -> new ConfigLoader().fileWith(file, "backups.retentionDays", "10"))
        .isInstanceOf(ConfigException.class);
  }

  private void write(String yaml) throws IOException {
    Files.writeString(new JrsUpgradeHome(tmp).configFile(), yaml, StandardCharsets.UTF_8);
  }
}
