package com.jaspersoft.jrsupgrade.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.secrets.EncryptedSecretStore;
import com.jaspersoft.jrsupgrade.core.secrets.PassphraseSource;
import com.jaspersoft.jrsupgrade.core.secrets.Secret;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretsCommandTest {

  private static final String PASSPHRASE = "pp-for-tests";
  private static final String VALUE = "s3cret-value";

  @TempDir Path tmp;

  private Path home;

  @BeforeEach
  void setUp() throws Exception {
    home = Files.createDirectories(tmp.resolve("home"));
    Env.override(Map.of("JRS_UPGRADE_PASSPHRASE", PASSPHRASE, "MY_SECRET", VALUE));
  }

  @AfterEach
  void tearDown() {
    Env.reset();
  }

  private String stored(String name) {
    EncryptedSecretStore store =
        new EncryptedSecretStore(
            new JrsUpgradeHome(home).secretsFile(),
            new PassphraseSource.Fixed(Secret.fromString(PASSPHRASE)));
    try (Secret s = store.get(name).orElseThrow()) {
      return new String(s.chars());
    }
  }

  @Test
  void should_init_set_list_and_remove_when_passphrase_in_environment() {
    InitCommandTest.Run init = InitCommandTest.run("secrets", "init", "--home", home.toString());
    InitCommandTest.Run set =
        InitCommandTest.run(
            "secrets", "set", "db", "--from-env", "MY_SECRET", "--home", home.toString());
    InitCommandTest.Run list = InitCommandTest.run("secrets", "list", "--home", home.toString());
    InitCommandTest.Run listJson =
        InitCommandTest.run("secrets", "list", "--json", "--home", home.toString());

    assertThat(init.code()).as(init.err()).isZero();
    assertThat(home.resolve("secrets.enc")).exists();
    assertThat(set.code()).as(set.err()).isZero();
    assertThat(set.out()).contains("enc:db").doesNotContain(VALUE).doesNotContain(PASSPHRASE);
    assertThat(list.code()).isZero();
    assertThat(list.out()).contains("db").doesNotContain(VALUE);
    assertThat(listJson.out()).contains("\"db\"");
    assertThat(stored("db")).isEqualTo(VALUE);

    InitCommandTest.Run remove =
        InitCommandTest.run("secrets", "remove", "db", "--home", home.toString());
    InitCommandTest.Run removeAgain =
        InitCommandTest.run("secrets", "remove", "db", "--home", home.toString());
    InitCommandTest.Run listAfter =
        InitCommandTest.run("secrets", "list", "--home", home.toString());

    assertThat(remove.code()).isZero();
    assertThat(removeAgain.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(listAfter.out()).contains("no entries");
  }

  @Test
  void should_set_value_from_file_when_from_file_given() throws Exception {
    Path file = tmp.resolve("value.txt");
    Files.writeString(file, VALUE + "\r\n", StandardCharsets.UTF_8);
    InitCommandTest.run("secrets", "init", "--home", home.toString());

    InitCommandTest.Run set =
        InitCommandTest.run(
            "secrets", "set", "jrs", "--from-file", file.toString(), "--home", home.toString());

    assertThat(set.code()).as(set.err()).isZero();
    assertThat(stored("jrs")).isEqualTo(VALUE);
  }

  @Test
  void should_exit_2_when_no_passphrase_is_available() {
    Env.override(Map.of());

    InitCommandTest.Run init = InitCommandTest.run("secrets", "init", "--home", home.toString());

    assertThat(init.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(init.err()).contains("JRS_UPGRADE_PASSPHRASE");
    assertThat(home.resolve("secrets.enc")).doesNotExist();
  }

  @Test
  void should_exit_2_when_environment_variable_for_value_is_missing() {
    InitCommandTest.run("secrets", "init", "--home", home.toString());

    InitCommandTest.Run set =
        InitCommandTest.run(
            "secrets", "set", "db", "--from-env", "NOT_SET", "--home", home.toString());

    assertThat(set.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(set.err()).contains("NOT_SET");
  }

  @Test
  void should_report_missing_store_when_list_before_init() {
    InitCommandTest.Run list = InitCommandTest.run("secrets", "list", "--home", home.toString());

    assertThat(list.code()).isZero();
    assertThat(list.out()).contains("secrets init");
  }
}
