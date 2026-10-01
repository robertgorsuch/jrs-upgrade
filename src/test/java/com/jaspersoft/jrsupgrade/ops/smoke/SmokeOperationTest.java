package com.jaspersoft.jrsupgrade.ops.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.engine.CancellationToken;
import com.jaspersoft.jrsupgrade.core.engine.Sleeper;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.jrs.api.Handles;
import com.jaspersoft.jrsupgrade.ops.FakeServices;
import com.jaspersoft.jrsupgrade.ops.ReportItem;
import com.jaspersoft.jrsupgrade.ops.ReportItem.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SmokeOperationTest {

  @TempDir Path tmp;

  private static final String YAML =
      """
      server:
        baseUrl: http://localhost:8080/jasperserver-pro
        auth:
          username: jasperadmin
          passwordRef: env:JRS_PASSWORD
      """;

  private static Map<String, ReportItem> byName(SmokeReport report) {
    return report.items().stream().collect(Collectors.toMap(ReportItem::name, Function.identity()));
  }

  private static SmokeOperation op(FakeServices fake) {
    return new SmokeOperation(fake.build(), EventSink.discard(), Sleeper.none());
  }

  @Test
  void should_pass_every_probe_when_server_answers_with_a_pdf() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("home")).yaml(YAML)) {
      SmokeReport report = op(fake).run(SmokeOptions.DEFAULT);

      Map<String, ReportItem> items = byName(report);
      assertThat(items.keySet())
          .containsExactlyInAnyOrder(
              "server", "login", "repository", "report", "scheduler", "export");
      assertThat(report.exitCode()).as(report.items().toString()).isEqualTo(0);
      assertThat(items.get("report").detail()).contains(SmokeOperation.DEFAULT_REPORT_URI);
      assertThat(items.get("export").status()).isEqualTo(Status.PASS);
      assertThat(report.runId()).isEmpty();
      assertThat(fake.adapter.calls)
          .contains(
              "login jasperadmin", "listFolder /", "schedulerReachable", "downloadExport exp-1")
          .noneMatch(c -> c.startsWith("createFolder"));
      try (var files = Files.list(fake.home.runs())) {
        assertThat(files).as("temp files removed").isEmpty();
      }
    }
  }

  @Test
  void should_warn_not_fail_when_sample_report_is_missing() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("home")).yaml(YAML)) {
      fake.adapter.missingReports.add(SmokeOperation.DEFAULT_REPORT_URI);

      SmokeReport report = op(fake).run(SmokeOptions.DEFAULT);

      ReportItem item = byName(report).get("report");
      assertThat(item.status()).isEqualTo(Status.WARN);
      assertThat(item.remediation()).contains("smoke.reportUri");
      assertThat(report.exitCode()).isEqualTo(0);
    }
  }

  @Test
  void should_fail_report_when_output_is_not_a_pdf() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("home")).yaml(YAML)) {
      fake.adapter.reportBytes = new byte[4096];

      SmokeReport report = op(fake).run(SmokeOptions.DEFAULT);

      assertThat(byName(report).get("report").status()).isEqualTo(Status.FAIL);
      assertThat(byName(report).get("report").detail()).contains("%PDF");
      assertThat(report.exitCode()).isEqualTo(2);
    }
  }

  @Test
  void should_fail_export_when_server_reports_failure() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("home")).yaml(YAML)) {
      fake.adapter.exportPhase = Handles.Phase.FAILED;

      SmokeReport report = op(fake).run(SmokeOptions.DEFAULT);

      assertThat(byName(report).get("export").status()).isEqualTo(Status.FAIL);
    }
  }

  /**
   * Review finding 1.11, remainder: the export probe polled with a token-less sleep and caught
   * every runtime exception, so a cancellation during an upgrade's verify smoke was slept out and
   * then reported as a failed export instead of ending the run.
   */
  @Test
  void should_stop_the_export_probe_at_the_cancellation_rather_than_after_the_poll_interval()
      throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("home")).yaml(YAML)) {
      fake.adapter.exportPhase = Handles.Phase.INPROGRESS;
      CancellationToken token = new CancellationToken();
      CancellingSleeper sleeper = new CancellingSleeper(token);
      SmokeOperation op = new SmokeOperation(fake.build(), EventSink.discard(), sleeper);

      assertThatThrownBy(() -> op.run(SmokeOptions.DEFAULT, token))
          .isInstanceOf(CancellationToken.CancelledException.class);
      assertThat(sleeper.slept).isLessThanOrEqualTo(Sleeper.SLICE);
      assertThat(fake.adapter.calls.stream().filter(c -> c.startsWith("pollExport")).count())
          .isEqualTo(1);
    }
  }

  @Test
  void should_report_the_mutating_probe_cancelled_when_the_token_is_cancelled() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("home")).yaml(YAML)) {
      CancellationToken token = new CancellationToken();
      token.cancel("operator");

      SmokeReport report = op(fake).run(new SmokeOptions(true), token);

      ReportItem mutating = byName(report).get("mutating");
      assertThat(mutating.status()).isEqualTo(Status.FAIL);
      assertThat(mutating.detail()).contains("cancelled");
    }
  }

  /** Books every requested sleep and cancels the token on the first one, like Ctrl-C mid-wait. */
  static final class CancellingSleeper implements Sleeper {
    private final CancellationToken token;
    Duration slept = Duration.ZERO;

    CancellingSleeper(CancellationToken token) {
      this.token = token;
    }

    @Override
    public void sleep(Duration duration) {
      slept = slept.plus(duration);
      token.cancel("operator");
    }
  }

  @Test
  void should_skip_everything_when_server_unreachable() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("home")).yaml(YAML)) {
      fake.unreachable = true;

      SmokeReport report = op(fake).run(new SmokeOptions(true));

      Map<String, ReportItem> items = byName(report);
      assertThat(items.get("server").status()).isEqualTo(Status.FAIL);
      assertThat(items.get("login").status()).isEqualTo(Status.SKIP);
      assertThat(items.get("mutating").status()).isEqualTo(Status.SKIP);
      assertThat(report.exitCode()).isEqualTo(2);
    }
  }

  @Test
  void should_create_run_and_remove_smoke_folder_when_mutating() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("home")).yaml(YAML)) {
      SmokeReport report = op(fake).run(new SmokeOptions(true));

      ReportItem item = byName(report).get("mutating");
      assertThat(item.status()).as(item.toString()).isEqualTo(Status.PASS);
      assertThat(report.runId()).isPresent();
      String folder = SmokePlan.folderUri(report.runId().get());
      assertThat(fake.adapter.calls)
          .containsSubsequence(
              "createFolder " + folder,
              "uploadJrxmlReport " + folder + "/" + SmokePlan.REPORT_LABEL,
              "runReportToPdf " + folder + "/" + SmokePlan.REPORT_LABEL,
              "deleteResource " + folder);
      assertThat(fake.adapter.folders.get("/temp")).doesNotContain(folder);
      assertThat(fake.stateStore().run(report.runId().get())).isPresent();
      assertThat(fake.stateStore().pendingRuns()).isEmpty();
    }
  }

  @Test
  void should_roll_back_folder_when_report_run_fails_in_mutating_plan() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("home")).yaml(YAML)) {
      fake.adapter.reportBytes = new byte[10];

      SmokeReport report = op(fake).run(new SmokeOptions(true));

      ReportItem item = byName(report).get("mutating");
      assertThat(item.status()).isEqualTo(Status.FAIL);
      String folder = SmokePlan.folderUri(report.runId().get());
      assertThat(fake.adapter.folders.get("/temp")).doesNotContain(folder);
      assertThat(fake.adapter.calls).contains("deleteResource " + folder);
    }
  }
}
