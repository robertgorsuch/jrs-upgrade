package com.jaspersoft.jrsupgrade.jrs.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.jrs.api.BrokenDependencies;
import com.jaspersoft.jrsupgrade.jrs.api.ExportRequest;
import com.jaspersoft.jrsupgrade.jrs.api.Handles;
import com.jaspersoft.jrsupgrade.jrs.api.ImportRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class RestJrsAdapterExportImportTest {

  @RegisterExtension
  WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @TempDir Path tmp;

  @Test
  void should_start_poll_and_stream_export_when_server_finishes(@TempDir Path dir)
      throws IOException {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    byte[] archive = new byte[3 * 1024 * 1024];
    Arrays.fill(archive, (byte) 'z');
    archive[0] = 'P';
    archive[1] = 'K';
    wm.stubFor(
        post(urlPathEqualTo(f.path("/rest_v2/export")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"exp-1\",\"phase\":\"inprogress\"}")));
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/export/exp-1/state")))
            .inScenario("export")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"phase\":\"inprogress\",\"message\":\"5%\"}"))
            .willSetStateTo("done"));
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/export/exp-1/state")))
            .inScenario("export")
            .whenScenarioStateIs("done")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"phase\":\"ready\",\"message\":\"Export succeeded\"}")));
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/export/exp-1/export.zip")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/zip")
                    .withBody(archive)));

    ExportRequest request =
        new ExportRequest(
            ExportRequest.Scope.REPOSITORY,
            Set.of("/public", "/organizations"),
            true,
            false,
            true,
            false,
            true,
            false,
            dir.resolve("out.zip"));
    Handles.ExportHandle handle = f.adapter.startExport(request);
    Handles.ExportStatus first = f.adapter.pollExport(handle);
    Handles.ExportStatus second = f.adapter.pollExport(handle);
    Path target = f.adapter.downloadExport(handle, dir.resolve("out.zip"));

    assertThat(handle.id()).isEqualTo("exp-1");
    assertThat(first.phase()).isEqualTo(Handles.Phase.INPROGRESS);
    assertThat(first.done()).isFalse();
    assertThat(second.phase()).isEqualTo(Handles.Phase.READY);
    assertThat(second.message()).contains("Export succeeded");
    assertThat(Files.size(target)).isEqualTo(archive.length);
    assertThat(Files.readAllBytes(target)).isEqualTo(archive);
    wm.verify(
        postRequestedFor(urlPathEqualTo(f.path("/rest_v2/export")))
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(
                equalToJson(
                    "{\"uris\":[\"/organizations\",\"/public\"],\"roles\":[],\"users\":[],"
                        + "\"parameters\":[\"repository-permissions\",\"role-users\","
                        + "\"include-audit-events\",\"include-server-settings\"]}")));
  }

  @Test
  void should_send_everything_parameter_when_scope_is_everything() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        post(urlPathEqualTo(f.path("/rest_v2/export")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"id\":\"exp-2\",\"phase\":\"inprogress\"}")));

    f.adapter.startExport(
        new ExportRequest(
            ExportRequest.Scope.EVERYTHING,
            Set.of(),
            false,
            true,
            false,
            true,
            false,
            true,
            tmp.resolve("x.zip")));

    wm.verify(
        postRequestedFor(urlPathEqualTo(f.path("/rest_v2/export")))
            .withRequestBody(
                equalToJson(
                    "{\"uris\":[],\"roles\":[],\"users\":[],\"parameters\":[\"everything\","
                        + "\"repository-permissions\",\"include-access-events\","
                        + "\"include-monitoring-events\"]}")));
  }

  @Test
  void should_send_skip_dependent_and_favorite_parameters_when_requested() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        post(urlPathEqualTo(f.path("/rest_v2/export")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"id\":\"exp-3\",\"phase\":\"inprogress\"}")));

    f.adapter.startExport(
        new ExportRequest(
            ExportRequest.Scope.REPOSITORY,
            Set.of("/public/report.jrxml"),
            false,
            false,
            false,
            false,
            false,
            false,
            tmp.resolve("x.zip"),
            true,
            Optional.empty(),
            Optional.empty(),
            true,
            true));

    wm.verify(
        postRequestedFor(urlPathEqualTo(f.path("/rest_v2/export")))
            .withRequestBody(
                equalToJson(
                    "{\"uris\":[\"/public/report.jrxml\"],\"roles\":[],\"users\":[],"
                        + "\"parameters\":[\"repository-permissions\",\"skip-dependent-resources\","
                        + "\"skip-favorite-resources\"]}")));
  }

  @Test
  void should_report_failed_when_export_state_is_failed() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/export/exp-9/state")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody(
                        "{\"phase\":\"failed\",\"message\":\"disk full\",\"errorCode\":\"export.failed\"}")));

    Handles.ExportStatus s = f.adapter.pollExport(new Handles.ExportHandle("exp-9"));

    assertThat(s.phase()).isEqualTo(Handles.Phase.FAILED);
    assertThat(s.errorCode()).contains("export.failed");
    assertThat(s.done()).isTrue();
  }

  @Test
  void should_stream_archive_and_poll_when_import_starts() throws IOException {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    Path archive = tmp.resolve("in.zip");
    byte[] bytes = new byte[512 * 1024];
    Arrays.fill(bytes, (byte) 7);
    try (OutputStream out = Files.newOutputStream(archive)) {
      out.write(bytes);
    }
    wm.stubFor(
        post(urlPathEqualTo(f.path("/rest_v2/import")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"id\":\"imp-1\",\"phase\":\"inprogress\"}")));
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/import/imp-1/state")))
            .willReturn(aResponse().withStatus(200).withBody("{\"phase\":\"ready\"}")));

    ImportRequest request =
        new ImportRequest(
            archive,
            true,
            false,
            false,
            true,
            false,
            true,
            true,
            Optional.empty(),
            Optional.empty());
    Handles.ImportHandle handle = f.adapter.startImport(request, archive);
    Handles.ImportStatus status = f.adapter.pollImport(handle);

    assertThat(handle.id()).isEqualTo("imp-1");
    assertThat(status.phase()).isEqualTo(Handles.Phase.READY);
    wm.verify(
        postRequestedFor(urlPathEqualTo(f.path("/rest_v2/import")))
            .withHeader("Content-Type", equalTo("application/zip"))
            .withQueryParam("update", equalTo("true"))
            .withQueryParam("skipUserUpdate", equalTo("false"))
            .withQueryParam("includeAccessEvents", equalTo("false"))
            .withQueryParam("includeAuditEvents", equalTo("true"))
            .withQueryParam("includeMonitoringEvents", equalTo("false"))
            // plural, the spelling the server's ImportJaxrsService declares; the 10.1 REST
            // reference's singular "includeServerSetting" is a documentation error (issue #110)
            .withQueryParam("includeServerSettings", equalTo("true"))
            .withQueryParam("skipThemes", equalTo("true"))
            .withRequestBody(
                new com.github.tomakehurst.wiremock.matching.BinaryEqualToPattern(bytes)));
  }

  @Test
  void should_report_pending_with_the_nested_error_when_server_parks_the_import() {
    // REST reference 10.1 p.120: a catalog with broken dependencies stops in phase "pending" and
    // the code is nested under "error", not a flat "errorCode".
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/import/imp-7/state")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody(
                        "{\"id\":\"imp-7\",\"phase\":\"pending\",\"message\":\"Import is pending\","
                            + "\"error\":{\"code\":\"import.broken.dependencies\","
                            + "\"parameters\":[\"/public/ds\",\"/public/other\"]}}")));

    Handles.ImportStatus s = f.adapter.pollImport(new Handles.ImportHandle("imp-7"));

    assertThat(s.phase()).isEqualTo(Handles.Phase.PENDING);
    assertThat(s.done()).isTrue();
    assertThat(s.errorCode()).contains("import.broken.dependencies");
    assertThat(s.errorParameters()).containsExactly("/public/ds", "/public/other");
  }

  @Test
  void should_cancel_the_import_task_when_asked() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        delete(urlPathEqualTo(f.path("/rest_v2/import/imp-8")))
            .willReturn(aResponse().withStatus(204)));

    f.adapter.cancelImport(new Handles.ImportHandle("imp-8"));

    wm.verify(deleteRequestedFor(urlPathEqualTo(f.path("/rest_v2/import/imp-8"))));
  }

  @Test
  void should_send_broken_dependencies_only_when_it_is_not_the_server_default() throws IOException {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    Path archive = tmp.resolve("bd.zip");
    Files.writeString(archive, "PK");
    wm.stubFor(
        post(urlPathEqualTo(f.path("/rest_v2/import")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"id\":\"imp-9\",\"phase\":\"inprogress\"}")));

    f.adapter.startImport(request(archive, BrokenDependencies.SKIP), archive);
    f.adapter.startImport(request(archive, BrokenDependencies.FAIL), archive);

    wm.verify(
        1,
        postRequestedFor(urlPathEqualTo(f.path("/rest_v2/import")))
            .withQueryParam("brokenDependencies", equalTo("skip")));
    wm.verify(
        1,
        postRequestedFor(urlPathEqualTo(f.path("/rest_v2/import")))
            .withoutQueryParam("brokenDependencies"));
  }

  private static ImportRequest request(Path archive, BrokenDependencies brokenDependencies) {
    return new ImportRequest(
        archive,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        Optional.empty(),
        Optional.empty(),
        brokenDependencies);
  }

  @Test
  void should_map_phase_strings_when_server_spells_them_differently() {
    assertThat(RestJrsAdapter.phase("pending")).isEqualTo(Handles.Phase.PENDING);
    assertThat(RestJrsAdapter.phase("inprogress")).isEqualTo(Handles.Phase.INPROGRESS);
    assertThat(RestJrsAdapter.phase("in-progress")).isEqualTo(Handles.Phase.INPROGRESS);
    assertThat(RestJrsAdapter.phase("READY")).isEqualTo(Handles.Phase.READY);
    assertThat(RestJrsAdapter.phase("finished")).isEqualTo(Handles.Phase.READY);
    assertThat(RestJrsAdapter.phase("failed")).isEqualTo(Handles.Phase.FAILED);
    assertThat(RestJrsAdapter.phase(null)).isEqualTo(Handles.Phase.FAILED);
  }
}
