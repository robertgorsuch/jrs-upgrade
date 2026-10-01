package com.jaspersoft.jrsupgrade.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsupgrade.core.config.ConfigException;
import com.jaspersoft.jrsupgrade.core.json.Json;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Review finding 4.3: an exception nobody mapped must not be reported as "rollback incomplete" (4)
 * when no run has started, must be logged, must point the operator at the log, and must never print
 * {@code error: null}.
 */
class ExitCodesHandlerTest {

  private static final String LOG = "C:/logs/jrs-upgrade-test.log";

  private String savedLogFile;

  @BeforeEach
  void logFile() {
    savedLogFile = System.getProperty(LogFile.PROPERTY);
    System.setProperty(LogFile.PROPERTY, LOG);
    RunState.reset();
  }

  @AfterEach
  void restore() {
    if (savedLogFile == null) {
      System.clearProperty(LogFile.PROPERTY);
    } else {
      System.setProperty(LogFile.PROPERTY, savedLogFile);
    }
    RunState.reset();
  }

  private record Handled(int code, String out, String err) {}

  private static Handled handle(Exception ex, String... args) {
    CommandLine cmd = Main.commandLine();
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    cmd.setOut(new PrintWriter(out));
    cmd.setErr(new PrintWriter(err));
    CommandLine.ParseResult parsed = cmd.parseArgs(args);
    int code = new ExitCodes.Handler().handleExecutionException(ex, cmd, parsed);
    return new Handled(code, out.toString(), err.toString());
  }

  @Test
  void should_exit_2_and_point_at_the_log_when_an_unmapped_exception_occurs_before_any_run() {
    Handled h = handle(new IllegalStateException("boom"), "selfcheck");

    assertThat(h.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(h.err()).contains("boom").contains(LOG);
    assertThat(h.out()).isEmpty();
  }

  @Test
  void should_exit_4_when_an_unmapped_exception_occurs_after_a_run_started() {
    RunState.markStarted();

    Handled h = handle(new IllegalStateException("boom"), "selfcheck");

    assertThat(h.code()).isEqualTo(ExitCodes.FAILED_ROLLBACK_INCOMPLETE);
    assertThat(h.err()).contains("boom").contains(LOG);
  }

  /**
   * Found against a real 10.0.0 server: a refused login printed the server's whole HTML error page
   * after "error:" and pointed at nothing.
   */
  @Test
  void should_say_the_credentials_were_refused_without_the_html_page_when_planning_gets_401() {
    StringWriter err = new StringWriter();

    int code =
        ExitCodes.reportPlanningFailure(
            new PrintWriter(err),
            new com.jaspersoft.jrsupgrade.jrs.rest.RestException(
                401,
                "GET",
                "/rest_v2/serverInfo",
                "HTTP 401 from GET /rest_v2/serverInfo: <!doctype html><html>Unauthorized"));

    assertThat(code).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(err.toString())
        .contains("refused the credentials")
        .contains("401")
        .contains("server.auth.passwordRef")
        .doesNotContain("<html");
  }

  @Test
  void should_name_the_exception_class_instead_of_null_when_it_has_no_message() {
    Handled h = handle(new IllegalStateException(), "selfcheck");

    assertThat(h.err()).contains("IllegalStateException").doesNotContain("null");
  }

  @Test
  void should_put_the_log_pointer_in_the_error_document_when_json_given() throws Exception {
    Handled h = handle(new IllegalStateException("boom"), "selfcheck", "--json");

    assertThat(h.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(h.err()).isEmpty();
    JsonNode doc = Json.read(h.out(), JsonNode.class);
    assertThat(doc.get("error").get("exitCode").asInt()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(doc.get("error").get("message").asText()).contains("boom");
    assertThat(doc.get("error").get("remediation").asText()).contains(LOG);
  }

  @Test
  void should_keep_mapping_configuration_problems_to_2_without_a_log_pointer() {
    Handled h = handle(new ConfigException("bad config", "server.baseUrl"), "selfcheck");

    assertThat(h.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(h.err()).contains("bad config").doesNotContain(LOG);
  }
}
