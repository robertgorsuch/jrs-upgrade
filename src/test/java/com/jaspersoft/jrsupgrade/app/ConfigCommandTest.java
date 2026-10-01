package com.jaspersoft.jrsupgrade.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.config.ConfigLoader;
import com.jaspersoft.jrsupgrade.core.secrets.EncryptedSecretStore;
import com.jaspersoft.jrsupgrade.core.secrets.PassphraseSource;
import com.jaspersoft.jrsupgrade.core.secrets.Secret;
import com.jaspersoft.jrsupgrade.core.secrets.SecretRef;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #70: change one setting without editing config.yaml. */
class ConfigCommandTest {

  @TempDir Path tmp;
  private Path home;

  @BeforeEach
  void setUp() throws IOException {
    home = Files.createDirectories(tmp.resolve("home"));
    Files.writeString(
        home.resolve("config.yaml"),
        """
        server:
          baseUrl: http://old.example.com:8080/jasperserver-pro
          webappName: jasperserver-pro
          runAsUser: tomcat
          auth:
            username: superuser
            passwordRef: env:JRS_PASSWORD
        """,
        StandardCharsets.UTF_8);
    Env.override(Map.of());
  }

  @AfterEach
  void restore() {
    Prompter.reset();
    Env.reset();
  }

  private InitCommandTest.Run cli(String... args) {
    String[] all = new String[args.length + 2];
    System.arraycopy(args, 0, all, 0, args.length);
    all[args.length] = "--home";
    all[args.length + 1] = home.toString();
    return InitCommandTest.run(all);
  }

  private Config fileConfig() {
    return new ConfigLoader().load(new JrsUpgradeHome(home), Map.of(), Map.of());
  }

  @Test
  void should_write_the_new_value_show_old_and_new_and_keep_a_backup_when_setting() {
    InitCommandTest.Run run =
        cli("config", "set", "server.baseUrl", "https://new.example.com:8443/jasperserver-pro");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(run.out())
        .contains("server.baseUrl")
        .contains("http://old.example.com:8080/jasperserver-pro")
        .contains("https://new.example.com:8443/jasperserver-pro")
        .contains("config.yaml.bak");
    assertThat(fileConfig().server().baseUrl())
        .contains(URI.create("https://new.example.com:8443/jasperserver-pro"));
    assertThat(fileConfig().server().runAsUser()).contains("tomcat");
    assertThat(home.resolve("config.yaml.bak")).content().contains("old.example.com");
  }

  @Test
  void should_refuse_an_unknown_key_and_change_nothing_when_setting() throws IOException {
    String before = Files.readString(home.resolve("config.yaml"), StandardCharsets.UTF_8);

    InitCommandTest.Run run = cli("config", "set", "server.baseUlr", "http://x");

    assertThat(run.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(run.err()).contains("server.baseUlr").contains("jrs-upgrade config keys");
    assertThat(home.resolve("config.yaml")).content().isEqualTo(before);
  }

  @Test
  void should_refuse_a_console_key_naming_the_adr_when_setting() throws IOException {
    String before = Files.readString(home.resolve("config.yaml"), StandardCharsets.UTF_8);

    InitCommandTest.Run run = cli("config", "set", "console.port", "1");

    assertThat(run.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(run.err()).contains("ADR-0038");
    assertThat(home.resolve("config.yaml")).content().isEqualTo(before);
  }

  /**
   * The ADR-0038 warning tells the operator to remove the 1.x block; {@code config unset console}
   * is how, and a single {@code console.*} key removes the whole block too.
   */
  @Test
  void should_remove_the_1x_console_block_and_keep_other_settings_when_unsetting_console()
      throws IOException {
    for (String key : List.of("console", "console.port")) {
      writeOneXConfigWithConsoleBlock();

      InitCommandTest.Run run = cli("config", "unset", key);

      assertThat(run.code()).as(key + ": " + run.out() + run.err()).isZero();
      assertThat(run.out()).contains("removed the console block");
      assertThat(home.resolve("config.yaml")).content().doesNotContain("console");
      assertThat(home.resolve("config.yaml.bak")).content().contains("console:");
      assertThat(fileConfig().server().baseUrl())
          .contains(URI.create("http://old.example.com:8080/jasperserver-pro"));
    }
  }

  @Test
  void should_change_nothing_when_unsetting_console_and_there_is_no_console_block()
      throws IOException {
    String before = Files.readString(home.resolve("config.yaml"), StandardCharsets.UTF_8);

    InitCommandTest.Run run = cli("config", "unset", "console");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(run.out()).contains("nothing changed");
    assertThat(home.resolve("config.yaml")).content().isEqualTo(before);
  }

  /** Field test 2, G9: a directory setting that does not exist is refused when it is written. */
  @Test
  void should_refuse_config_set_of_a_directory_key_that_does_not_exist() throws IOException {
    InitCommandTest.Run run = cli("config", "set", "server.buildomaticDir", "/zugzug/whatever");

    assertThat(run.code()).isEqualTo(ExitCodes.USAGE);
    assertThat(run.err()).contains("no such directory: /zugzug/whatever");
    assertThat(Files.readString(home.resolve("config.yaml"), StandardCharsets.UTF_8))
        .doesNotContain("zugzug");
  }

  /**
   * Field test 2, G3: {@code ~} is the operator's home when the setting is checked, and the file
   * gets the expanded path, since the service account that reads it later has another home.
   */
  @Test
  void should_accept_a_tilde_path_that_exists_and_store_it_expanded() throws IOException {
    Path operatorHome = Files.createDirectories(tmp.resolve("operator"));
    Files.createDirectories(operatorHome.resolve("bd"));
    Env.override(Map.of("HOME", operatorHome.toString()));

    InitCommandTest.Run run = cli("config", "set", "server.buildomaticDir", "~/bd");

    assertThat(run.code()).as(run.err()).isZero();
    assertThat(Files.readString(home.resolve("config.yaml"), StandardCharsets.UTF_8))
        .doesNotContain("~/bd");
    assertThat(fileConfig().server().buildomaticDir()).contains(operatorHome.resolve("bd"));
  }

  @Test
  void should_refuse_a_password_on_the_command_line_without_echoing_it() {
    InitCommandTest.Run run = cli("config", "set", "server.auth.passwordRef", "Hunter2-Secret");

    assertThat(run.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(run.out() + run.err())
        .doesNotContain("Hunter2-Secret")
        .contains("jrs-upgrade config set server.auth.passwordRef");
    assertThat(fileConfig().server().auth().passwordRef().map(SecretRef::render))
        .contains("env:JRS_PASSWORD");
  }

  @Test
  void should_accept_a_secret_reference_as_the_value_of_a_password_key() {
    InitCommandTest.Run run = cli("config", "set", "server.auth.passwordRef", "env:JRS_ADMIN_PW");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(fileConfig().server().auth().passwordRef().map(SecretRef::render))
        .contains("env:JRS_ADMIN_PW");
  }

  @Test
  void should_store_a_typed_password_encrypted_when_a_password_key_is_set_without_a_value() {
    Env.override(Map.of("JRS_UPGRADE_PASSPHRASE", "store-pass"));
    Prompter.override(new StringReader("Adm1n-Secret\n"));

    InitCommandTest.Run run = cli("config", "set", "server.auth.passwordRef");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(run.out() + run.err()).doesNotContain("Adm1n-Secret").contains("enc:JRS_PASSWORD");
    assertThat(fileConfig().server().auth().passwordRef().map(SecretRef::render))
        .contains("enc:JRS_PASSWORD");
    EncryptedSecretStore store =
        new EncryptedSecretStore(
            new JrsUpgradeHome(home).secretsFile(),
            new PassphraseSource.Fixed(Secret.fromString("store-pass")));
    try (Secret stored = store.get("JRS_PASSWORD").orElseThrow()) {
      assertThat(new String(stored.chars())).isEqualTo("Adm1n-Secret");
    }
  }

  @Test
  void should_ask_for_the_value_when_an_ordinary_key_is_set_without_one() {
    Prompter.override(new StringReader("jasperadmin\n"));

    InitCommandTest.Run run = cli("config", "set", "server.auth.username");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(run.out()).contains("[superuser]");
    assertThat(fileConfig().server().auth().username()).contains("jasperadmin");
  }

  @Test
  void should_warn_when_an_environment_variable_still_overrides_the_key_that_was_set() {
    Env.override(Map.of("JRS_UPGRADE_SERVER_RUN_AS_USER", "jasper"));

    InitCommandTest.Run run = cli("config", "set", "server.runAsUser", "jrs");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(run.out()).contains("JRS_UPGRADE_SERVER_RUN_AS_USER").contains("still overrides");
  }

  @Test
  void should_remove_the_key_from_the_file_when_unsetting() {
    InitCommandTest.Run run = cli("config", "unset", "server.runAsUser");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(run.out()).contains("server.runAsUser").contains("tomcat");
    assertThat(fileConfig().server().runAsUser()).isEmpty();
    assertThat(fileConfig().server().baseUrl())
        .contains(URI.create("http://old.example.com:8080/jasperserver-pro"));
  }

  @Test
  void should_list_every_key_with_its_value_source_and_description() {
    Env.override(Map.of("JRS_UPGRADE_BACKUPS_RETENTION_DAYS", "45"));

    InitCommandTest.Run run = cli("config", "keys");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    for (String key : new ConfigLoader().knownKeys()) {
      assertThat(run.out()).contains(key);
    }
    assertThat(run.out())
        .contains("JRS_UPGRADE_BACKUPS_RETENTION_DAYS")
        .contains("45")
        .contains(ConfigKeys.description("server.baseUrl"));
  }

  /** Field test 2, G8: the keys table fits a default terminal, wider with COLUMNS. */
  @Test
  void should_keep_config_keys_within_the_terminal_width() {
    InitCommandTest.Run narrow = cli("config", "keys");
    Env.override(Map.of("COLUMNS", "160"));
    InitCommandTest.Run wide = cli("config", "keys");

    assertThat(narrow.code()).isZero();
    assertThat(narrow.out().lines()).allMatch(l -> l.length() <= 80);
    assertThat(narrow.out().lines().count()).isGreaterThan(wide.out().lines().count());
    assertThat(wide.out().lines()).allMatch(l -> l.length() <= 160);
  }

  /** Issue #73: a database value read from default_master.properties says so. */
  @Test
  void should_label_database_values_read_from_buildomatic_when_listing_keys() throws Exception {
    Path install = InitCommandTest.fakeLayout(tmp.resolve("jrs"));
    Files.writeString(
        home.resolve("config.yaml"),
        """
        server:
          baseUrl: http://localhost:8089/jasperserver-pro
          installDir: %s
        """
            .formatted(install.toString().replace("\\", "/")),
        StandardCharsets.UTF_8);

    InitCommandTest.Run keys = cli("config", "keys");
    InitCommandTest.Run show = cli("config", "show");

    assertThat(keys.code()).as(keys.out() + keys.err()).isZero();
    // the source sits on the key's line when the columns fit, else on the line beneath (G8)
    java.util.List<String> lines = keys.out().lines().toList();
    int at = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).startsWith("database.type")) {
        at = i;
      }
    }
    assertThat(at).as(keys.out()).isNotNegative();
    assertThat(lines.get(at)).contains("postgresql");
    assertThat(lines.get(at) + " " + lines.get(at + 1)).contains("default_master.properties");
    assertThat(show.out()).contains("# database.type: from").contains("default_master.properties");
  }

  /** Issue #74: config set and show work on a home configured with jrs-upgrade.properties. */
  @Test
  void should_change_and_show_a_properties_configuration_in_its_own_format() throws IOException {
    Files.delete(home.resolve("config.yaml"));
    Files.writeString(
        home.resolve("jrs-upgrade.properties"),
        "server.baseUrl=http://old.example.com:8080/jasperserver-pro\nserver.runAsUser=tomcat\n",
        StandardCharsets.UTF_8);

    InitCommandTest.Run set =
        cli("config", "set", "server.baseUrl", "https://new.example.com/jasperserver-pro");
    InitCommandTest.Run show = cli("config", "show", "--format", "properties");

    assertThat(set.code()).as(set.out() + set.err()).isZero();
    assertThat(home.resolve("config.yaml")).doesNotExist();
    assertThat(home.resolve("jrs-upgrade.properties"))
        .content()
        .contains("server.baseUrl=https://new.example.com/jasperserver-pro")
        .contains("server.runAsUser=tomcat");
    assertThat(home.resolve("jrs-upgrade.properties.bak")).exists();
    assertThat(show.code()).as(show.out() + show.err()).isZero();
    assertThat(show.out())
        .contains("server.baseUrl=https://new.example.com/jasperserver-pro")
        .doesNotContain("server:");
  }

  @Test
  void should_describe_every_key_the_schema_knows() {
    for (String key : new ConfigLoader().knownKeys()) {
      assertThat(ConfigKeys.description(key)).as(key).isNotBlank().doesNotContain("no description");
    }
  }

  @Test
  void should_note_overridden_values_when_showing_the_configuration() {
    Env.override(Map.of("JRS_UPGRADE_BACKUPS_RETENTION_DAYS", "45"));

    InitCommandTest.Run run = cli("config", "show", "--set", "server.runAsUser=jrs");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(run.out())
        .contains("# backups.retentionDays: overridden by JRS_UPGRADE_BACKUPS_RETENTION_DAYS")
        .contains("# server.runAsUser: overridden by --set");
  }

  /**
   * #154, ADR-0038: 2.0 tolerated a 1.x {@code console:} block with a warning; 2.1 refuses it,
   * naming the ADR and the command that removes it.
   */
  @Test
  void should_refuse_a_1x_console_block_and_name_the_way_out_when_showing() throws IOException {
    writeOneXConfigWithConsoleBlock();

    InitCommandTest.Run run = cli("config", "show");

    assertThat(run.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(run.err()).contains("ADR-0038").contains("jrs-upgrade config unset console");
  }

  /** #154: the way out works although every other command refuses the file. */
  @Test
  void should_repair_a_1x_file_with_config_unset_console_when_everything_else_refuses_it()
      throws IOException {
    writeOneXConfigWithConsoleBlock();

    InitCommandTest.Run unset = cli("config", "unset", "console");
    InitCommandTest.Run show = cli("config", "show");

    assertThat(unset.code()).as(unset.out() + unset.err()).isZero();
    assertThat(show.code()).as(show.out() + show.err()).isZero();
    assertThat(home.resolve("config.yaml")).content().doesNotContain("console");
  }

  private void writeOneXConfigWithConsoleBlock() throws IOException {
    Files.writeString(
        home.resolve("config.yaml"),
        """
        server:
          baseUrl: http://old.example.com:8080/jasperserver-pro
        console:
          port: 7421
        """,
        StandardCharsets.UTF_8);
  }
}
