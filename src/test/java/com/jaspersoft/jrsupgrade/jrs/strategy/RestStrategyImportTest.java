package com.jaspersoft.jrsupgrade.jrs.strategy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.RunOutcome;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepFailure;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.jrs.FakeJrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.RecordingSink;
import com.jaspersoft.jrsupgrade.jrs.TestConfigs;
import com.jaspersoft.jrsupgrade.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsupgrade.jrs.api.ExportRequest;
import com.jaspersoft.jrsupgrade.jrs.api.ImportRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class RestStrategyImportTest {

  private static final String FP_A = "a".repeat(64);
  private static final String FP_B = "b".repeat(64);

  @RegisterExtension
  WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @TempDir Path tmp;
  private StrategyFixture fx;
  private Path archive;

  @BeforeEach
  void setUp() throws IOException {
    fx = new StrategyFixture(tmp);
    archive = tmp.resolve("in.zip");
    Files.writeString(archive, "PK".repeat(1000));
  }

  @AfterEach
  void tearDown() {
    fx.close();
  }

  private ImportRequest request(Optional<Path> sourceKeystore) {
    return new ImportRequest(
        archive, true, false, false, false, false, false, false, sourceKeystore, Optional.empty());
  }

  private void sidecarWith(Optional<String> fingerprint) throws IOException {
    Sidecar.write(
        Sidecar.pathFor(archive),
        new Sidecar(
            StrategyFixture.NOW,
            "srv",
            "8.2.0",
            fingerprint,
            new Sidecar.Flags(
                ExportRequest.Scope.REPOSITORY,
                List.of("/public"),
                false,
                false,
                false,
                false,
                false,
                false),
            "deadbeef",
            ExportImportStrategy.Kind.REST));
  }

  private CheckResult precheck(FakeJrsAdapter adapter, ImportRequest request) {
    // built directly: a request with a source keystore is refused by the REST strategy as a whole
    Step check = new CheckKeystoreFingerprint(RestStrategy.IMPORT_PHASE, request);
    assertThat(check.id()).isEqualTo(CheckKeystoreFingerprint.ID);
    return check.precheck(
        fx.context(TestConfigs.server(StrategyFixture.BASE, Config.AuthMode.BASIC), adapter));
  }

  /** Field test 2, I1: a named key decrypts the archive; this server's keystore is not compared. */
  @Test
  void should_pass_precheck_without_comparing_when_the_request_names_a_key_alias()
      throws IOException {
    sidecarWith(Optional.of("0000000000000000000000000000000000000000000000000000000000000000"));
    FakeJrsAdapter adapter = new FakeJrsAdapter();

    CheckResult result =
        precheck(adapter, request(Optional.empty()).withKeyAlias(ExportRequest.PORTABLE_KEY_ALIAS));

    assertThat(result).isInstanceOf(CheckResult.Pass.class);
  }

  @Test
  void should_send_the_key_alias_on_the_import_request_when_given() throws IOException {
    RestFixture rest = new RestFixture(wm, fx.platform, fx.redactor, tmp.resolve("userhome"));
    wm.stubFor(
        post(urlPathEqualTo(rest.path("/rest_v2/import")))
            .withQueryParam("keyAlias", equalTo("k1"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"id\":\"imp-1\",\"phase\":\"inprogress\"}")));
    wm.stubFor(
        get(urlPathEqualTo(rest.path("/rest_v2/import/imp-1/state")))
            .willReturn(aResponse().withStatus(200).withBody("{\"phase\":\"ready\"}")));
    List<Step> steps =
        new RestStrategy(fx.polling).importSteps(request(Optional.empty()).withKeyAlias("k1"));
    Context ctx = fx.context(rest.config, rest.adapter);

    RunOutcome outcome = fx.run(steps, ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
    wm.verify(
        1,
        postRequestedFor(urlPathEqualTo(rest.path("/rest_v2/import")))
            .withQueryParam("keyAlias", equalTo("k1")));
  }

  /** Field test 2, I4: the target organisation and the merge switch go on the import query. */
  @Test
  void should_send_the_organisation_and_merge_switch_on_the_import_request_when_given()
      throws IOException {
    RestFixture rest = new RestFixture(wm, fx.platform, fx.redactor, tmp.resolve("userhome"));
    wm.stubFor(
        post(urlPathEqualTo(rest.path("/rest_v2/import")))
            .withQueryParam("organization", equalTo("org1"))
            .withQueryParam("mergeOrganization", equalTo("true"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"id\":\"imp-1\",\"phase\":\"inprogress\"}")));
    wm.stubFor(
        get(urlPathEqualTo(rest.path("/rest_v2/import/imp-1/state")))
            .willReturn(aResponse().withStatus(200).withBody("{\"phase\":\"ready\"}")));
    List<Step> steps =
        new RestStrategy(fx.polling)
            .importSteps(request(Optional.empty()).withOrganization("org1", true));
    Context ctx = fx.context(rest.config, rest.adapter);

    RunOutcome outcome = fx.run(steps, ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
    wm.verify(
        1,
        postRequestedFor(urlPathEqualTo(rest.path("/rest_v2/import")))
            .withQueryParam("organization", equalTo("org1"))
            .withQueryParam("mergeOrganization", equalTo("true")));
  }

  @Test
  void should_fail_precheck_with_remediation_when_keystore_fingerprints_differ()
      throws IOException {
    sidecarWith(Optional.of(FP_A));
    FakeJrsAdapter adapter =
        new FakeJrsAdapter().withKeystore(FakeJrsAdapter.keystoreWith(FP_B, Optional.empty()));

    CheckResult result = precheck(adapter, request(Optional.empty()));

    assertThat(result).isInstanceOf(CheckResult.Fail.class);
    CheckResult.Fail fail = (CheckResult.Fail) result;
    assertThat(fail.message()).contains("mismatch");
    assertThat(fail.remediation())
        .isEqualTo(CheckKeystoreFingerprint.REMEDIATION)
        .contains("--source-keystore")
        .contains("--source-keystore-password-ref");
  }

  @Test
  void should_pass_precheck_when_keystore_fingerprints_match() throws IOException {
    sidecarWith(Optional.of(FP_A));
    FakeJrsAdapter adapter =
        new FakeJrsAdapter().withKeystore(FakeJrsAdapter.keystoreWith(FP_A, Optional.empty()));

    CheckResult result = precheck(adapter, request(Optional.empty()));

    assertThat(result).isInstanceOf(CheckResult.Pass.class);
  }

  @Test
  void should_not_fail_precheck_when_fingerprints_differ_but_source_keystore_supplied()
      throws IOException {
    sidecarWith(Optional.of(FP_A));
    Path source = tmp.resolve("source.jrsks");
    Files.writeString(source, "ks");
    FakeJrsAdapter adapter =
        new FakeJrsAdapter().withKeystore(FakeJrsAdapter.keystoreWith(FP_B, Optional.empty()));

    CheckResult result = precheck(adapter, request(Optional.of(source)));

    assertThat(result).isInstanceOf(CheckResult.Warn.class);
    assertThat(((CheckResult.Warn) result).message()).contains("will be imported first");
  }

  @Test
  void should_warn_when_no_sidecar_exists() {
    FakeJrsAdapter adapter =
        new FakeJrsAdapter().withKeystore(FakeJrsAdapter.keystoreWith(FP_B, Optional.empty()));

    CheckResult result = precheck(adapter, request(Optional.empty()));

    assertThat(result).isInstanceOf(CheckResult.Warn.class);
  }

  @Test
  void should_pass_verify_when_server_purges_task_after_poll_observed_ready() throws IOException {
    RestFixture rest = new RestFixture(wm, fx.platform, fx.redactor, tmp.resolve("userhome"));
    wm.stubFor(
        post(urlPathEqualTo(rest.path("/rest_v2/import")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"id\":\"imp-1\",\"phase\":\"inprogress\"}")));
    wm.stubFor(
        get(urlPathEqualTo(rest.path("/rest_v2/import/imp-1/state")))
            .inScenario("purge")
            .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
            .willReturn(aResponse().withStatus(200).withBody("{\"phase\":\"ready\"}"))
            .willSetStateTo("purged"));
    wm.stubFor(
        get(urlPathEqualTo(rest.path("/rest_v2/import/imp-1/state")))
            .inScenario("purge")
            .whenScenarioStateIs("purged")
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withBody(
                        "{\"message\":\"No export task with id imp-1.\","
                            + "\"errorCode\":\"no.such.export.process\"}")));
    List<Step> steps = new RestStrategy(fx.polling).importSteps(request(Optional.empty()));
    Context ctx = fx.context(rest.config, rest.adapter);

    RunOutcome outcome = fx.run(steps, ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
    assertThat(fx.journal())
        .contains("import.start:SUCCEEDED", "import.poll:SUCCEEDED", "import.verify:SUCCEEDED");
  }

  /**
   * A task still running when the poll gives up must not be compensated: the compensation of the
   * import phase re-imports the pre-import snapshot, which would race the server's own import
   * (assessment item U3). Exactly one POST /import proves nothing was re-imported.
   */
  @Test
  void should_fail_fatally_and_not_reimport_when_the_import_task_outlives_the_poll_timeout()
      throws IOException {
    RestFixture rest = new RestFixture(wm, fx.platform, fx.redactor, tmp.resolve("userhome"));
    wm.stubFor(
        post(urlPathEqualTo(rest.path("/rest_v2/import")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"id\":\"imp-2\",\"phase\":\"inprogress\"}")));
    wm.stubFor(
        get(urlPathEqualTo(rest.path("/rest_v2/import/imp-2/state")))
            .willReturn(aResponse().withStatus(200).withBody("{\"phase\":\"inprogress\"}")));
    List<Step> steps =
        new RestStrategy(fx.polling.withTimeout(Duration.ZERO))
            .importSteps(request(Optional.empty()));
    Context ctx = fx.context(rest.config, rest.adapter);

    RunOutcome outcome = fx.run(steps, ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.Failed.class);
    assertThat(((RunOutcome.Failed) outcome).cause()).contains("still in progress");
    assertThat(((RunOutcome.Failed) outcome).nextAction()).contains("not re-imported");
    wm.verify(1, postRequestedFor(urlPathEqualTo(rest.path("/rest_v2/import"))));
  }

  @Test
  void should_fail_fatally_and_not_reimport_when_the_poll_gets_a_definitive_http_error()
      throws IOException {
    RestFixture rest = new RestFixture(wm, fx.platform, fx.redactor, tmp.resolve("userhome"));
    wm.stubFor(
        post(urlPathEqualTo(rest.path("/rest_v2/import")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"id\":\"imp-3\",\"phase\":\"inprogress\"}")));
    wm.stubFor(
        get(urlPathEqualTo(rest.path("/rest_v2/import/imp-3/state")))
            .willReturn(
                aResponse().withStatus(404).withBody("{\"message\":\"no such import task\"}")));
    List<Step> steps = new RestStrategy(fx.polling).importSteps(request(Optional.empty()));
    Context ctx = fx.context(rest.config, rest.adapter);

    RunOutcome outcome = fx.run(steps, ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.Failed.class);
    assertThat(((RunOutcome.Failed) outcome).cause()).contains("HTTP 404");
    wm.verify(1, postRequestedFor(urlPathEqualTo(rest.path("/rest_v2/import"))));
  }

  @Test
  void should_refuse_a_source_keystore_and_never_swap_keys_under_a_running_server()
      throws IOException {
    Path source = tmp.resolve("source.jrsks");
    Files.writeString(source, "ks");
    RestStrategy strategy = new RestStrategy(fx.polling);

    List<String> without =
        strategy.importSteps(request(Optional.empty())).stream().map(Step::id).toList();

    assertThat(without)
        .containsExactly(
            CheckKeystoreFingerprint.ID, StartImport.ID, PollImport.ID, VerifyImport.ID);
    assertThat(strategy.requiresServiceStop()).isFalse();
    assertThat(strategy.kind()).isEqualTo(ExportImportStrategy.Kind.REST);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> strategy.importSteps(request(Optional.of(source))))
        .as("js-import --keystore needs the service stopped, which this strategy never does")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("vendor strategy");
  }

  @Test
  void should_import_through_runner_and_post_once_when_start_executes_twice() throws IOException {
    RestFixture rest = new RestFixture(wm, fx.platform, fx.redactor, tmp.resolve("userhome"));
    wm.stubFor(
        post(urlPathEqualTo(rest.path("/rest_v2/import")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"id\":\"imp-1\",\"phase\":\"inprogress\"}")));
    wm.stubFor(
        get(urlPathEqualTo(rest.path("/rest_v2/import/imp-1/state")))
            .willReturn(aResponse().withStatus(200).withBody("{\"phase\":\"ready\"}")));
    List<Step> steps = new RestStrategy(fx.polling).importSteps(request(Optional.empty()));
    Context ctx = fx.context(rest.config, rest.adapter);

    RunOutcome outcome = fx.run(steps, ctx);
    StepResult again = steps.get(1).execute(ctx, EventSink.discard());

    assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
    assertThat(again).isInstanceOf(StepResult.Ok.class);
    wm.verify(1, postRequestedFor(urlPathEqualTo(rest.path("/rest_v2/import"))));
    assertThat(RunFiles.read(RunFiles.in(ctx, RunFiles.IMPORT_HANDLE))).contains("imp-1");
    assertThat(fx.journal())
        .contains("import.start:SUCCEEDED", "import.poll:SUCCEEDED", "import.verify:SUCCEEDED");
  }

  /** Issue #44: a 502 may come from a proxy after the server accepted the upload. */
  @Test
  void should_post_once_and_fail_fatally_when_start_import_gets_a_gateway_error()
      throws IOException {
    RestFixture rest = new RestFixture(wm, fx.platform, fx.redactor, tmp.resolve("userhome"));
    wm.stubFor(
        post(urlPathEqualTo(rest.path("/rest_v2/import"))).willReturn(aResponse().withStatus(502)));
    Step start = new StartImport(request(Optional.empty()));
    Context ctx = fx.context(rest.config, rest.adapter);

    StepResult first = start.execute(ctx, EventSink.discard());
    StepResult again = start.execute(ctx, EventSink.discard());

    assertThat(first)
        .isInstanceOfSatisfying(
            StepResult.Failed.class,
            f -> assertThat(f.failure()).isInstanceOf(StepFailure.Fatal.class));
    assertThat(again)
        .isInstanceOfSatisfying(
            StepResult.Failed.class,
            f -> assertThat(f.failure()).isInstanceOf(StepFailure.Fatal.class));
    wm.verify(1, postRequestedFor(urlPathEqualTo(rest.path("/rest_v2/import"))));
    assertThat(RunFiles.in(ctx, RunFiles.IMPORT_STARTED)).exists();
  }

  /** Issue #44: a 503 is a refusal, so the request never started an import and may be retried. */
  @Test
  void should_retry_start_import_when_the_server_refused_it_with_503() throws IOException {
    RestFixture rest = new RestFixture(wm, fx.platform, fx.redactor, tmp.resolve("userhome"));
    wm.stubFor(
        post(urlPathEqualTo(rest.path("/rest_v2/import"))).willReturn(aResponse().withStatus(503)));
    Step start = new StartImport(request(Optional.empty()));
    Context ctx = fx.context(rest.config, rest.adapter);

    StepResult refused = start.execute(ctx, EventSink.discard());
    wm.stubFor(
        post(urlPathEqualTo(rest.path("/rest_v2/import")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"id\":\"imp-2\",\"phase\":\"inprogress\"}")));
    StepResult accepted = start.execute(ctx, EventSink.discard());

    assertThat(refused)
        .isInstanceOfSatisfying(
            StepResult.Failed.class,
            f -> assertThat(f.failure()).isInstanceOf(StepFailure.Retryable.class));
    assertThat(accepted).isInstanceOf(StepResult.Ok.class);
    wm.verify(2, postRequestedFor(urlPathEqualTo(rest.path("/rest_v2/import"))));
    assertThat(RunFiles.read(RunFiles.in(ctx, RunFiles.IMPORT_HANDLE))).contains("imp-2");
  }

  @Test
  void should_log_snapshot_note_when_start_import_compensates() {
    Step start = new StartImport(request(Optional.empty()));
    RecordingSink sink = new RecordingSink();

    StepResult result =
        start.compensate(
            fx.context(
                TestConfigs.server(StrategyFixture.BASE, Config.AuthMode.BASIC),
                new FakeJrsAdapter()),
            sink);

    assertThat(result).isInstanceOf(StepResult.Ok.class);
    assertThat(sink.logMessages()).containsExactly(StartImport.ROLLBACK_NOTE);
  }

  @Test
  void should_fail_fatally_when_poll_import_times_out() throws IOException {
    RestFixture rest = new RestFixture(wm, fx.platform, fx.redactor, tmp.resolve("userhome"));
    wm.stubFor(
        get(urlPathEqualTo(rest.path("/rest_v2/import/imp-to/state")))
            .willReturn(aResponse().withStatus(200).withBody("{\"phase\":\"inprogress\"}")));
    Polling shortPolling =
        new Polling(
            Duration.ofMillis(1),
            Duration.ofMillis(2),
            Duration.ofMillis(10),
            Duration.ofSeconds(30),
            java.time.Clock.systemUTC(),
            com.jaspersoft.jrsupgrade.core.engine.Sleeper.none());
    Step poll = new PollImport(shortPolling);
    Context ctx = fx.context(rest.config, rest.adapter);
    Path handleFile = RunFiles.in(ctx, RunFiles.IMPORT_HANDLE);
    RunFiles.write(handleFile, "imp-to");

    StepResult result = poll.execute(ctx, EventSink.discard());

    assertThat(result).isInstanceOf(StepResult.Failed.class);
    StepResult.Failed failed = (StepResult.Failed) result;
    assertThat(failed.failure())
        .isInstanceOf(com.jaspersoft.jrsupgrade.core.engine.StepFailure.Fatal.class);
    assertThat(failed.failure().cause()).contains("imp-to still in progress");
  }

  @Test
  void should_fail_fatally_when_poll_import_encounters_rest_error() throws IOException {
    RestFixture rest = new RestFixture(wm, fx.platform, fx.redactor, tmp.resolve("userhome"));
    wm.stubFor(
        get(urlPathEqualTo(rest.path("/rest_v2/import/imp-err/state")))
            .willReturn(aResponse().withStatus(500).withBody("internal error")));
    Step poll = new PollImport(fx.polling);
    Context ctx = fx.context(rest.config, rest.adapter);
    Path handleFile = RunFiles.in(ctx, RunFiles.IMPORT_HANDLE);
    RunFiles.write(handleFile, "imp-err");

    StepResult result = poll.execute(ctx, EventSink.discard());

    assertThat(result).isInstanceOf(StepResult.Failed.class);
    StepResult.Failed failed = (StepResult.Failed) result;
    assertThat(failed.failure())
        .isInstanceOf(com.jaspersoft.jrsupgrade.core.engine.StepFailure.Fatal.class);
    assertThat(failed.failure().cause())
        .contains("cannot tell whether import imp-err is still running: HTTP 500");
  }

  @Test
  void should_fail_recoverably_when_server_reports_failed_import() throws IOException {
    RestFixture rest = new RestFixture(wm, fx.platform, fx.redactor, tmp.resolve("userhome"));
    wm.stubFor(
        get(urlPathEqualTo(rest.path("/rest_v2/import/imp-fail/state")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"phase\":\"failed\",\"message\":\"schema mismatch\"}")));
    Step poll = new PollImport(fx.polling);
    Context ctx = fx.context(rest.config, rest.adapter);
    Path handleFile = RunFiles.in(ctx, RunFiles.IMPORT_HANDLE);
    RunFiles.write(handleFile, "imp-fail");

    StepResult result = poll.execute(ctx, EventSink.discard());

    assertThat(result).isInstanceOf(StepResult.Failed.class);
    StepResult.Failed failed = (StepResult.Failed) result;
    assertThat(failed.failure())
        .isInstanceOf(com.jaspersoft.jrsupgrade.core.engine.StepFailure.Recoverable.class);
    assertThat(failed.failure().cause())
        .contains("server reported import imp-fail failed: schema mismatch");
  }

  @Test
  void should_cancel_the_task_and_fail_recoverably_when_server_parks_the_import_pending()
      throws IOException {
    // REST 10.1 p.119: "pending" is a task that imported nothing and never resumes by itself
    RestFixture rest = new RestFixture(wm, fx.platform, fx.redactor, tmp.resolve("userhome"));
    wm.stubFor(
        get(urlPathEqualTo(rest.path("/rest_v2/import/imp-pend/state")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody(
                        "{\"id\":\"imp-pend\",\"phase\":\"pending\",\"message\":\"Import is pending\","
                            + "\"error\":{\"code\":\"import.broken.dependencies\","
                            + "\"parameters\":[\"/public/ds\"]}}")));
    wm.stubFor(
        delete(urlPathEqualTo(rest.path("/rest_v2/import/imp-pend")))
            .willReturn(aResponse().withStatus(204)));
    Step poll = new PollImport(fx.polling);
    Context ctx = fx.context(rest.config, rest.adapter);
    RunFiles.write(RunFiles.in(ctx, RunFiles.IMPORT_HANDLE), "imp-pend");

    StepResult result = poll.execute(ctx, EventSink.discard());

    assertThat(result).isInstanceOf(StepResult.Failed.class);
    StepResult.Failed failed = (StepResult.Failed) result;
    assertThat(failed.failure())
        .isInstanceOf(com.jaspersoft.jrsupgrade.core.engine.StepFailure.Recoverable.class);
    assertThat(failed.failure().cause())
        .contains("imp-pend")
        .contains("import.broken.dependencies")
        .contains("/public/ds")
        .contains("nothing was imported");
    assertThat(failed.failure().nextAction()).contains("--broken-dependencies skip");
    wm.verify(deleteRequestedFor(urlPathEqualTo(rest.path("/rest_v2/import/imp-pend"))));
  }
}
