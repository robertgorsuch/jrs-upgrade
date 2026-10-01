package com.jaspersoft.jrsupgrade.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class MainTest {

  @Test
  void should_print_banner_and_exit_zero_when_version_flag_given() {
    StringWriter out = new StringWriter();
    CommandLine cmd = new CommandLine(new JrsUpgradeCommand());
    cmd.setOut(new java.io.PrintWriter(out));
    int code = cmd.execute("--version");
    assertThat(code).isZero();
    assertThat(out.toString())
        .contains("jrs-upgrade")
        .contains("Jaspersoft")
        .doesNotContain("Actian");
  }

  @Test
  void should_name_jaspersoft_without_actian_when_help_is_printed() {
    StringWriter out = new StringWriter();
    CommandLine cmd = new CommandLine(new JrsUpgradeCommand());
    cmd.setOut(new java.io.PrintWriter(out));

    int code = cmd.execute("--help");

    assertThat(code).isZero();
    assertThat(out.toString())
        .contains("JasperReports Server lifecycle tool (Jaspersoft)")
        .doesNotContain("Actian");
  }

  @Test
  void should_exit_zero_when_selfcheck_passes() {
    StringWriter out = new StringWriter();
    CommandLine cmd = new CommandLine(new JrsUpgradeCommand());
    cmd.setOut(new java.io.PrintWriter(out));
    int code = cmd.execute("selfcheck");
    assertThat(code).isZero();
    assertThat(out.toString()).contains("selfcheck ok");
  }

  @Test
  void should_emit_json_when_selfcheck_json_flag_given() {
    StringWriter out = new StringWriter();
    CommandLine cmd = new CommandLine(new JrsUpgradeCommand());
    cmd.setOut(new java.io.PrintWriter(out));
    int code = cmd.execute("selfcheck", "--json");
    assertThat(code).isZero();
    assertThat(out.toString()).contains("\"items\"").contains("\"status\" : \"PASS\"");
  }

  /**
   * Review finding 4.5: {@code jrs-upgrade --json} with no command must not print usage to stdout.
   */
  @Test
  void should_emit_one_error_document_and_exit_1_when_json_given_without_a_command()
      throws Exception {
    StringWriter out = new StringWriter();
    java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
    PrintStream saved = System.err;
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    int code;
    try {
      CommandLine cmd = Main.commandLine();
      cmd.setOut(new java.io.PrintWriter(out));
      cmd.setErr(
          new java.io.PrintWriter(
              new java.io.OutputStreamWriter(System.err, StandardCharsets.UTF_8), true));
      code = cmd.execute("--json");
    } finally {
      System.setErr(saved);
    }
    assertThat(code).isEqualTo(ExitCodes.USAGE);
    assertThat(err.toString(StandardCharsets.UTF_8)).isBlank();
    com.fasterxml.jackson.databind.JsonNode doc =
        com.jaspersoft.jrsupgrade.core.json.Json.read(
            out.toString(), com.fasterxml.jackson.databind.JsonNode.class);
    assertThat(doc.get("error").get("exitCode").asInt()).isEqualTo(ExitCodes.USAGE);
    assertThat(doc.get("error").get("remediation").asText()).contains("--help");
  }

  @Test
  void should_print_usage_and_exit_1_when_no_command_given() {
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    CommandLine cmd = Main.commandLine();
    cmd.setOut(new java.io.PrintWriter(out));
    cmd.setErr(new java.io.PrintWriter(err));

    int code = cmd.execute();

    assertThat(code).isEqualTo(ExitCodes.USAGE);
    assertThat(err.toString()).contains("Usage:");
    assertThat(out.toString()).isEmpty();
  }

  @Test
  void should_exit_one_when_unknown_subcommand_given() {
    java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
    PrintStream saved = System.err;
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      assertThat(Main.run("no-such-command")).isEqualTo(ExitCodes.USAGE);
    } finally {
      System.setErr(saved);
    }
  }
}
