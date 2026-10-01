package com.jaspersoft.jrsupgrade.ops.exim;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.RunOutcome;
import com.jaspersoft.jrsupgrade.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.rest.RestJrsAdapterFactory;
import com.jaspersoft.jrsupgrade.jrs.strategy.Sidecar;
import com.jaspersoft.jrsupgrade.ops.Lazy;
import com.jaspersoft.jrsupgrade.ops.exim.ExportImportOperations.ExportOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * The export plan executed by the real Runner against a WireMock server through the real adapter.
 */
class ExportRunTest {

  private static final String CONTEXT = "/jasperserver-pro";
  private static final int ONE_MB = 1024 * 1024;
  private static final String SERVER_INFO =
      """
      {
        "version": "8.2.0",
        "edition": "PRO",
        "editionName": "Professional",
        "features": "Fusion AHD EXP DB AUD ANA MT ",
        "build": "20230315_1234",
        "licenseType": "Commercial",
        "expiration": "2099-01-01",
        "dateFormatPattern": "yyyy-MM-dd",
        "datetimeFormatPattern": "yyyy-MM-dd'T'HH:mm:ss"
      }
      """;

  @RegisterExtension
  WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @TempDir Path tmp;

  private EximFixture fx;

  @BeforeEach
  void setUp() throws IOException {
    wm.stubFor(
        get(urlPathEqualTo(CONTEXT + "/rest_v2/serverInfo"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(SERVER_INFO)));
    wm.stubFor(
        get(urlPathEqualTo(CONTEXT + "/rest_v2/export/jrs-upgrade-probe/state"))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"errorCode\":\"no.such.export.process\",\"message\":\"No task jrs-upgrade-probe\"}")));
    wm.stubFor(
        get(urlPathEqualTo(CONTEXT + "/rest_v2/import/jrs-upgrade-probe/state"))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"errorCode\":\"no.such.export.process\",\"message\":\"No task jrs-upgrade-probe\"}")));
    wm.stubFor(
        get(urlPathEqualTo(CONTEXT + "/rest_v2/organizations"))
            .willReturn(aResponse().withStatus(200).withBody("{\"organization\":[]}")));
    wm.stubFor(
        get(urlPathEqualTo(CONTEXT + "/rest_v2/login")).willReturn(aResponse().withStatus(405)));
    // field test 2, E3: planning asks whether each --uri exists before starting the export
    wm.stubFor(
        get(urlPathEqualTo(CONTEXT + "/rest_v2/resources/public"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/repository.folder+json")
                    .withBody("{\"uri\":\"/public\",\"label\":\"Public\"}")));
    wm.stubFor(
        post(urlPathEqualTo(CONTEXT + "/rest_v2/export"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"e1\",\"phase\":\"inprogress\"}")));
    wm.stubFor(
        get(urlPathEqualTo(CONTEXT + "/rest_v2/export/e1/state"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"phase\":\"ready\",\"fileName\":\"export.zip\"}")));
    byte[] archive = new byte[ONE_MB];
    Arrays.fill(archive, (byte) 'z');
    archive[0] = 'P';
    archive[1] = 'K';
    wm.stubFor(
        get(urlPathEqualTo(CONTEXT + "/rest_v2/export/e1/export.zip"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/zip")
                    .withBody(archive)));

    Supplier<JrsAdapter> adapter = Lazy.of(this::connect);
    fx =
        new EximFixture(
            tmp,
            adapter,
            """
            server:
              baseUrl: http://localhost:%d%s
              auth:
                username: jasperadmin
                passwordRef: env:JRS_PASSWORD
            service:
              kind: manual
            network:
              mode: public
            """
                .formatted(wm.getPort(), CONTEXT));
  }

  private JrsAdapter connect() {
    return new RestJrsAdapterFactory()
        .connect(fx.services.config(), fx.services.secrets(), fx.services.redactor(), fx.platform);
  }

  @AfterEach
  void tearDown() {
    fx.close();
  }

  @Test
  void should_write_archive_and_sidecar_when_export_plan_runs_against_wiremock()
      throws IOException {
    Path out = tmp.resolve("exports").resolve("public.zip");
    ExportOptions options =
        new ExportOptions(
            Set.of("/public"), true, false, false, false, false, false, out, Optional.empty());

    Plan plan = fx.ops().planExport(options);
    RunOutcome outcome = fx.run(plan, EximFixture.RUN);

    assertThat(outcome).as(fx.events.toString()).isInstanceOf(RunOutcome.Succeeded.class);
    assertThat(Files.size(out)).isEqualTo(ONE_MB);
    Optional<Sidecar> sidecar = Sidecar.read(Sidecar.pathFor(out));
    assertThat(sidecar).isPresent();
    assertThat(sidecar.get().serverVersion()).isEqualTo("8.2.0");
    assertThat(sidecar.get().strategy()).isEqualTo(ExportImportStrategy.Kind.REST);
    assertThat(sidecar.get().flags().uris()).containsExactly("/public");
    assertThat(sidecar.get().sha256()).isEqualTo(fx.platform.files().sha256(out));
    assertThat(plan.summary().strategy()).startsWith("rest (");
    wm.verify(1, postRequestedFor(urlPathEqualTo(CONTEXT + "/rest_v2/export")));
    wm.verify(1, getRequestedFor(urlPathEqualTo(CONTEXT + "/rest_v2/export/e1/export.zip")));
    assertThat(fx.journal(EximFixture.RUN))
        .contains(
            "export.start:SUCCEEDED",
            "export.poll:SUCCEEDED",
            "export.download:SUCCEEDED",
            "export.sidecar:SUCCEEDED");
  }
}
