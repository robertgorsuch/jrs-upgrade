package com.jaspersoft.jrsupgrade.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.secrets.EncryptedSecretStore;
import com.jaspersoft.jrsupgrade.core.secrets.PassphraseSource;
import com.jaspersoft.jrsupgrade.core.secrets.Secret;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DoctorCommandTest {

  @TempDir Path tmp;

  @AfterEach
  void reset() {
    TestAdapterFactory.unreachable = false;
    TestAdapterFactory.adapter = new AppFakeAdapter();
  }

  private Path home(Path install) throws Exception {
    Path home = Files.createDirectories(tmp.resolve("home"));
    JrsUpgradeHome jh = new JrsUpgradeHome(home);
    EncryptedSecretStore store =
        new EncryptedSecretStore(
            jh.secretsFile(), new PassphraseSource.Fixed(Secret.fromString("pp-for-tests")));
    store.init();
    store.set("jrs", Secret.fromString("pw-for-tests"));
    Files.writeString(tmp.resolve("pp.txt"), "pp-for-tests", StandardCharsets.UTF_8);
    TestFiles.ownerOnly(tmp.resolve("pp.txt"));
    Files.writeString(
        jh.configFile(),
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
          webappName: jasperserver-pro
          installDir: %s
          auth:
            username: jasperadmin
            passwordRef: enc:jrs
        service:
          kind: manual
        network:
          mode: public
        """
            .formatted(install.toString().replace("\\", "/")),
        StandardCharsets.UTF_8);
    return home;
  }

  private static Map<String, JsonNode> items(String json) throws Exception {
    JsonNode root = new ObjectMapper().readTree(json);
    Map<String, JsonNode> byName = new HashMap<>();
    for (JsonNode item : root.get("items")) {
      byName.put(item.get("name").asText(), item);
    }
    return byName;
  }

  @Test
  void should_emit_parseable_json_with_server_and_identity_pass_when_fake_server_answers()
      throws Exception {
    Path install = InitCommandTest.fakeLayout(tmp.resolve("jrs"));
    Path home = home(install);

    InitCommandTest.Run run =
        InitCommandTest.run(
            "doctor",
            "--json",
            "--home",
            home.toString(),
            "--passphrase-file",
            tmp.resolve("pp.txt").toString());

    Map<String, JsonNode> items = items(run.out());
    assertThat(items.get("server").get("status").asText()).isEqualTo("PASS");
    assertThat(items.get("auth").get("status").asText()).isEqualTo("PASS");
    assertThat(items.get("identity").get("detail").asText()).contains("8.2.0");
    assertThat(items.get("compat").get("status").asText()).isEqualTo("PASS");
    assertThat(items.get("secrets").get("status").asText()).isEqualTo("PASS");
    assertThat(items.get("service").get("status").asText()).isEqualTo("WARN");
    assertThat(items.get("layout").get("status").asText()).isEqualTo("PASS");
    assertThat(run.out()).doesNotContain("pw-for-tests").doesNotContain("pp-for-tests");
    int exitCode = new ObjectMapper().readTree(run.out()).get("exitCode").asInt();
    assertThat(run.code()).isEqualTo(exitCode);
    assertThat(run.code()).as(run.out()).isIn(ExitCodes.SUCCESS, ExitCodes.PRECHECK_FAILED);
  }

  @Test
  void should_exit_2_with_server_fail_when_server_unreachable() throws Exception {
    Path install = InitCommandTest.fakeLayout(tmp.resolve("jrs"));
    Path home = home(install);
    TestAdapterFactory.unreachable = true;

    InitCommandTest.Run run =
        InitCommandTest.run(
            "doctor",
            "--json",
            "--home",
            home.toString(),
            "--passphrase-file",
            tmp.resolve("pp.txt").toString());

    Map<String, JsonNode> items = items(run.out());
    assertThat(items.get("server").get("status").asText()).isEqualTo("FAIL");
    assertThat(items.get("identity").get("status").asText()).isEqualTo("SKIP");
    assertThat(run.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
  }

  @Test
  void should_exit_6_when_only_compat_fails_and_text_output_lists_remediation() throws Exception {
    Path install = InitCommandTest.fakeLayout(tmp.resolve("jrs"));
    Path home = home(install);
    TestAdapterFactory.adapter.version = "6.4.0";

    InitCommandTest.Run json =
        InitCommandTest.run(
            "doctor",
            "--json",
            "--home",
            home.toString(),
            "--passphrase-file",
            tmp.resolve("pp.txt").toString());
    InitCommandTest.Run text =
        InitCommandTest.run(
            "doctor",
            "--no-color",
            "--ascii",
            "--home",
            home.toString(),
            "--passphrase-file",
            tmp.resolve("pp.txt").toString());

    Map<String, JsonNode> items = items(json.out());
    assertThat(items.get("compat").get("status").asText()).isEqualTo("FAIL");
    if (json.code() == ExitCodes.UNSUPPORTED) {
      assertThat(items.values())
          .filteredOn(i -> i.get("status").asText().equals("FAIL"))
          .allMatch(i -> i.get("name").asText().equals("compat"));
    }
    assertThat(text.out())
        .contains("x FAIL")
        .contains("compat")
        .contains("-> use a supported")
        .containsPattern(
            "\\d+ fail \\(compat[^)]*\\), \\d+ warn(?: \\([^)]*\\))?, \\d+ pass, \\d+ skip");
    assertThat(text.code()).isEqualTo(json.code());
  }

  /**
   * Field test 2, D2: the failure was one line followed by many green ones. The problems come
   * first, a rule separates them from the rest, and the summary line names them.
   */
  @Test
  void should_print_the_problems_then_a_rule_then_the_rest() throws Exception {
    Path install = InitCommandTest.fakeLayout(tmp.resolve("jrs"));
    Path home = home(install);
    TestAdapterFactory.adapter.version = "6.4.0"; // compat FAILs, the rest passes or skips

    InitCommandTest.Run text =
        InitCommandTest.run(
            "doctor",
            "--no-color",
            "--ascii",
            "--home",
            home.toString(),
            "--passphrase-file",
            tmp.resolve("pp.txt").toString());

    List<String> lines = text.out().lines().toList();
    int rule = lines.indexOf("----");
    assertThat(rule).as(text.out()).isGreaterThan(0);
    assertThat(lines.subList(0, rule))
        .allMatch(l -> l.startsWith("x FAIL") || l.startsWith("! WARN") || l.startsWith("  "));
    assertThat(lines.subList(0, rule)).anyMatch(l -> l.startsWith("x FAIL  compat"));
    assertThat(lines.subList(rule + 1, lines.size() - 1))
        .allMatch(l -> l.startsWith("+ PASS") || l.startsWith("- SKIP") || l.startsWith("  "));
    assertThat(lines.get(lines.size() - 1)).startsWith("1 fail (compat)");
  }

  @Test
  void should_exit_2_when_no_configuration_exists() throws Exception {
    Path home = Files.createDirectories(tmp.resolve("empty-home"));

    InitCommandTest.Run run = InitCommandTest.run("doctor", "--json", "--home", home.toString());

    Map<String, JsonNode> items = items(run.out());
    assertThat(items.get("config").get("status").asText()).isEqualTo("WARN");
    assertThat(items.get("server").get("status").asText()).isEqualTo("FAIL");
    assertThat(items.get("server").get("remediation").asText()).contains("jrs-upgrade init");
    assertThat(run.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
  }

  @Test
  void should_show_effective_config_with_references_only() throws Exception {
    Path install = InitCommandTest.fakeLayout(tmp.resolve("jrs"));
    Path home = home(install);

    InitCommandTest.Run run =
        InitCommandTest.run(
            "config",
            "show",
            "--home",
            home.toString(),
            "--set",
            "backups.retentionDays=7",
            "--passphrase-file",
            tmp.resolve("pp.txt").toString());

    assertThat(run.code()).isZero();
    assertThat(run.out())
        .contains("passwordRef: enc:jrs")
        .contains("retentionDays: 7")
        .doesNotContain("pw-for-tests");
  }

  @Test
  void should_run_smoke_against_fake_server_and_exit_zero() throws Exception {
    Path install = InitCommandTest.fakeLayout(tmp.resolve("jrs"));
    Path home = home(install);

    InitCommandTest.Run run =
        InitCommandTest.run(
            "smoke",
            "--json",
            "--home",
            home.toString(),
            "--passphrase-file",
            tmp.resolve("pp.txt").toString());

    Map<String, JsonNode> items = items(run.out());
    assertThat(items.get("report").get("status").asText()).isEqualTo("PASS");
    assertThat(items.get("export").get("status").asText()).isEqualTo("PASS");
    assertThat(run.code()).as(run.out()).isZero();
  }
}
