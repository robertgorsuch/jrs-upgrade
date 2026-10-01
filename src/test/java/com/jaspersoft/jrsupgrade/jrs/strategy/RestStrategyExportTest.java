package com.jaspersoft.jrsupgrade.jrs.strategy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.RunOutcome;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepFailure;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsupgrade.jrs.api.ExportRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class RestStrategyExportTest {

  private static final int TWO_MB = 2 * 1024 * 1024;

  @RegisterExtension
  WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @TempDir Path tmp;
  private StrategyFixture fx;
  private RestFixture rest;
  private Path output;

  @BeforeEach
  void setUp() throws IOException {
    fx = new StrategyFixture(tmp);
    rest = new RestFixture(wm, fx.platform, fx.redactor, tmp.resolve("userhome"));
    output = tmp.resolve("out").resolve("export.zip");
  }

  @AfterEach
  void tearDown() {
    fx.close();
  }

  private ExportRequest request() {
    return new ExportRequest(
        ExportRequest.Scope.REPOSITORY,
        Set.of("/public"),
        true,
        false,
        false,
        false,
        false,
        false,
        output);
  }

  private void stubStart() {
    wm.stubFor(
        post(urlPathEqualTo(rest.path("/rest_v2/export")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"exp-1\",\"phase\":\"inprogress\"}")));
  }

  private void stubStates(String... phases) {
    String state = Scenario.STARTED;
    for (int i = 0; i < phases.length; i++) {
      String next = "s" + (i + 1);
      boolean last = i == phases.length - 1;
      wm.stubFor(
          get(urlPathEqualTo(rest.path("/rest_v2/export/exp-1/state")))
              .inScenario("export")
              .whenScenarioStateIs(state)
              .willReturn(aResponse().withStatus(200).withBody(phases[i]))
              .willSetStateTo(last ? state : next));
      state = next;
    }
  }

  private byte[] stubDownload() {
    // a real archive with index.xml, as a JasperReports Server export always is, padded to the
    // size the streaming tests want
    byte[] payload = new byte[TWO_MB];
    Arrays.fill(payload, (byte) 'z');
    byte[] archive = zip(java.util.Map.of("index.xml", payload, "resources/", new byte[0]));
    stubDownload(archive);
    return archive;
  }

  private void stubDownload(byte[] archive) {
    wm.stubFor(
        get(urlPathEqualTo(rest.path("/rest_v2/export/exp-1/export.zip")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/zip")
                    .withBody(archive)));
  }

  private static byte[] zip(java.util.Map<String, byte[]> entries) {
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(bytes)) {
      for (var e : entries.entrySet()) {
        out.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
        out.write(e.getValue());
        out.closeEntry();
      }
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  /** Field test 2, E3: a failed export carries its cause in errorDescriptor, not in message. */
  @Test
  void should_report_the_error_descriptor_when_the_export_task_fails() throws IOException {
    stubStart();
    stubStates(
        "{\"phase\":\"inprogress\"}",
        "{\"phase\":\"failed\",\"errorDescriptor\":{\"message\":\"Resource /a/b not found\","
            + "\"errorCode\":\"resource.not.found\",\"parameters\":[\"/a/b\"]}}");
    List<Step> steps = new RestStrategy(fx.polling).exportSteps(request());
    Context ctx = fx.context(rest.config, rest.adapter);

    RunOutcome outcome = fx.run(steps, ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(((RunOutcome.RolledBack) outcome).cause())
        .contains("Resource /a/b not found")
        .contains("resource.not.found")
        .doesNotContain("without a message");
  }

  /**
   * Field test 2, E3: an export whose uris matched nothing used to exit 0 with an empty archive.
   */
  @Test
  void should_fail_the_download_when_the_archive_holds_no_index() throws IOException {
    stubStart();
    stubStates("{\"phase\":\"ready\"}");
    stubDownload(zip(java.util.Map.of("resources/", new byte[0])));
    List<Step> steps = new RestStrategy(fx.polling).exportSteps(request());
    Context ctx = fx.context(rest.config, rest.adapter);

    RunOutcome outcome = fx.run(steps, ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(((RunOutcome.RolledBack) outcome).cause())
        .contains("holds no index.xml")
        .contains("no resource matched");
    assertThat(Files.exists(output)).isFalse();
  }

  /** Field test 2, E4: the alias goes in the export body and into the sidecar for the import. */
  @Test
  void should_send_the_key_alias_and_record_it_in_the_sidecar_when_given() throws IOException {
    stubStart();
    stubStates("{\"phase\":\"ready\",\"message\":\"Export succeeded\"}");
    stubDownload();
    List<Step> steps =
        new RestStrategy(fx.polling)
            .exportSteps(request().withKeyAlias(ExportRequest.PORTABLE_KEY_ALIAS));
    Context ctx = fx.context(rest.config, rest.adapter);

    RunOutcome outcome = fx.run(steps, ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
    wm.verify(
        postRequestedFor(urlPathEqualTo(rest.path("/rest_v2/export")))
            .withRequestBody(
                containing("\"keyAlias\":\"" + ExportRequest.PORTABLE_KEY_ALIAS + "\"")));
    Optional<Sidecar> sidecar = Sidecar.read(Sidecar.pathFor(output));
    assertThat(sidecar).isPresent();
    assertThat(sidecar.get().flags().keyAlias()).contains(ExportRequest.PORTABLE_KEY_ALIAS);
  }

  /** Field test 2, E5: the organisation goes in the export body and into the sidecar. */
  @Test
  void should_send_the_organisation_and_record_it_in_the_sidecar_when_given() throws IOException {
    stubStart();
    stubStates("{\"phase\":\"ready\",\"message\":\"Export succeeded\"}");
    stubDownload();
    List<Step> steps = new RestStrategy(fx.polling).exportSteps(request().withOrganization("org1"));
    Context ctx = fx.context(rest.config, rest.adapter);

    RunOutcome outcome = fx.run(steps, ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
    wm.verify(
        postRequestedFor(urlPathEqualTo(rest.path("/rest_v2/export")))
            .withRequestBody(containing("\"organization\":\"org1\"")));
    Optional<Sidecar> sidecar = Sidecar.read(Sidecar.pathFor(output));
    assertThat(sidecar).isPresent();
    assertThat(sidecar.get().flags().organization()).contains("org1");
  }

  @Test
  void should_export_download_and_write_sidecar_when_server_finishes() throws IOException {
    stubStart();
    stubStates(
        "{\"phase\":\"inprogress\",\"message\":\"10%\"}",
        "{\"phase\":\"inprogress\",\"message\":\"60%\"}",
        "{\"phase\":\"ready\",\"message\":\"Export succeeded\"}");
    byte[] archive = stubDownload();
    List<Step> steps = new RestStrategy(fx.polling).exportSteps(request());
    Context ctx = fx.context(rest.config, rest.adapter);

    RunOutcome outcome = fx.run(steps, ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
    assertThat(Files.size(output)).isEqualTo(archive.length);
    assertThat(Files.exists(RunFiles.partOf(output))).isFalse();
    Optional<Sidecar> sidecar = Sidecar.read(Sidecar.pathFor(output));
    assertThat(sidecar).isPresent();
    assertThat(sidecar.get().keystoreFingerprint())
        .contains(fx.platform.files().sha256(rest.keystoreFile));
    assertThat(sidecar.get().sha256()).isEqualTo(fx.platform.files().sha256(output));
    assertThat(sidecar.get().serverVersion()).isEqualTo("8.2.0");
    assertThat(sidecar.get().strategy()).isEqualTo(ExportImportStrategy.Kind.REST);
    assertThat(sidecar.get().flags().uris()).containsExactly("/public");
    assertThat(sidecar.get().flags().includeUsersRoles()).isTrue();
    assertThat(sidecar.get().exportedAt()).isEqualTo(StrategyFixture.NOW);
    wm.verify(1, postRequestedFor(urlPathEqualTo(rest.path("/rest_v2/export"))));
    wm.verify(3, getRequestedFor(urlPathEqualTo(rest.path("/rest_v2/export/exp-1/state"))));
    assertThat(fx.journal())
        .contains(
            "export.start:SUCCEEDED",
            "export.poll:SUCCEEDED",
            "export.download:SUCCEEDED",
            "export.sidecar:SUCCEEDED");
  }

  @Test
  void should_post_export_once_when_start_step_executes_twice() {
    stubStart();
    Step start = new RestStrategy(fx.polling).exportSteps(request()).get(0);
    Context ctx = fx.context(rest.config, rest.adapter);

    StepResult first = start.execute(ctx, EventSink.discard());
    StepResult second = start.execute(ctx, EventSink.discard());

    assertThat(first).isInstanceOf(StepResult.Ok.class);
    assertThat(second).isInstanceOf(StepResult.Ok.class);
    wm.verify(1, postRequestedFor(urlPathEqualTo(rest.path("/rest_v2/export"))));
  }

  @Test
  void should_fail_recoverably_and_skip_download_when_poll_reports_failed() throws IOException {
    stubStart();
    stubStates(
        "{\"phase\":\"inprogress\"}",
        "{\"phase\":\"failed\",\"message\":\"disk full on server\",\"errorCode\":\"export.failed\"}");
    stubDownload();
    List<Step> steps = new RestStrategy(fx.polling).exportSteps(request());
    Context ctx = fx.context(rest.config, rest.adapter);

    RunOutcome outcome = fx.run(steps, ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(((RunOutcome.RolledBack) outcome).cause())
        .contains("disk full on server")
        .contains("export.failed");
    wm.verify(0, getRequestedFor(urlPathEqualTo(rest.path("/rest_v2/export/exp-1/export.zip"))));
    assertThat(fx.journal()).doesNotContain("export.download:RUNNING");
    assertThat(Files.exists(output)).isFalse();
    assertThat(Files.exists(Sidecar.pathFor(output))).isFalse();
  }

  /**
   * Review finding 2.2: one 503 from a restarting Tomcat or a proxy during a two-hour export used
   * to fail the run while the server-side task went on. The poll tick now tolerates transient
   * answers and keeps polling.
   */
  @Test
  void should_keep_polling_when_a_poll_answers_503_then_recovers() throws IOException {
    stubStart();
    wm.stubFor(
        get(urlPathEqualTo(rest.path("/rest_v2/export/exp-1/state")))
            .inScenario("flaky")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(503).withHeader("Retry-After", "1"))
            .willSetStateTo("up"));
    wm.stubFor(
        get(urlPathEqualTo(rest.path("/rest_v2/export/exp-1/state")))
            .inScenario("flaky")
            .whenScenarioStateIs("up")
            .willReturn(aResponse().withStatus(200).withBody("{\"phase\":\"ready\"}")));
    byte[] archive = stubDownload();
    List<Step> steps = new RestStrategy(fx.polling).exportSteps(request());
    Context ctx = fx.context(rest.config, rest.adapter);

    RunOutcome outcome = fx.run(steps, ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
    assertThat(Files.size(output)).isEqualTo(archive.length);
    assertThat(fx.sink.logMessages()).anyMatch(m -> m.contains("503"));
  }

  @Test
  void should_fail_recoverably_when_polls_keep_answering_503() {
    stubStart();
    wm.stubFor(
        get(urlPathEqualTo(rest.path("/rest_v2/export/exp-1/state")))
            .willReturn(aResponse().withStatus(503)));
    List<Step> steps = new RestStrategy(fx.polling).exportSteps(request());
    Context ctx = fx.context(rest.config, rest.adapter);

    RunOutcome outcome = fx.run(steps, ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(((RunOutcome.RolledBack) outcome).cause())
        .contains(String.valueOf(Polling.MAX_CONSECUTIVE_FAILURES))
        .contains("503");
    wm.verify(
        Polling.MAX_CONSECUTIVE_FAILURES,
        getRequestedFor(urlPathEqualTo(rest.path("/rest_v2/export/exp-1/state"))));
  }

  @Test
  void should_retry_the_start_when_the_server_answers_503_with_retry_after() {
    wm.stubFor(
        post(urlPathEqualTo(rest.path("/rest_v2/export")))
            .willReturn(aResponse().withStatus(503).withHeader("Retry-After", "7")));
    Step start = new RestStrategy(fx.polling).exportSteps(request()).get(0);

    StepResult result = start.execute(fx.context(rest.config, rest.adapter), EventSink.discard());

    assertThat(result).isInstanceOf(StepResult.Failed.class);
    StepFailure failure = ((StepResult.Failed) result).failure();
    assertThat(failure).isInstanceOf(StepFailure.Retryable.class);
    assertThat(failure.cause()).contains("503");
    assertThat(((StepFailure.Retryable) failure).retryAfter())
        .contains(java.time.Duration.ofSeconds(7));
  }

  @Test
  void should_retry_the_download_when_the_server_answers_502() throws IOException {
    wm.stubFor(
        get(urlPathEqualTo(rest.path("/rest_v2/export/exp-1/export.zip")))
            .willReturn(aResponse().withStatus(502)));
    Context ctx = fx.context(rest.config, rest.adapter);
    RunFiles.write(RunFiles.in(ctx, RunFiles.EXPORT_HANDLE), "exp-1");
    Step download = new DownloadExport(output);

    StepResult result = download.execute(ctx, EventSink.discard());

    assertThat(result).isInstanceOf(StepResult.Failed.class);
    assertThat(((StepResult.Failed) result).failure())
        .isInstanceOf(StepFailure.Retryable.class)
        .extracting(StepFailure::cause)
        .asString()
        .contains("502");
  }

  @Test
  void should_delete_partial_and_final_file_when_download_step_compensates() throws IOException {
    Files.createDirectories(output.getParent());
    Files.writeString(output, "archive");
    Files.writeString(RunFiles.partOf(output), "partial");
    Step download = new DownloadExport(output);

    StepResult result =
        download.compensate(fx.context(rest.config, rest.adapter), EventSink.discard());

    assertThat(result).isInstanceOf(StepResult.Ok.class);
    assertThat(Files.exists(output)).isFalse();
    assertThat(Files.exists(RunFiles.partOf(output))).isFalse();
  }
}
