package com.jaspersoft.jrsupgrade.jrs.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.jrs.api.Handles;
import com.jaspersoft.jrsupgrade.jrs.api.JrsUnreachableException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review finding 5.4: transport faults, not just error statuses. A connection the server resets
 * mid-poll or mid-download is what a load balancer recycling a connection actually looks like, and
 * until now nothing in the suite produced one.
 */
class TransportFaultTest {

  @RegisterExtension
  WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @Test
  void should_stay_usable_after_a_poll_whose_connection_the_server_resets() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        post(urlPathEqualTo(f.path("/rest_v2/export")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"exp-1\",\"phase\":\"inprogress\"}")));
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/export/exp-1/state")))
            .inScenario("reset")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
            .willSetStateTo("second"));
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/export/exp-1/state")))
            .inScenario("reset")
            .whenScenarioStateIs("second")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"phase\":\"ready\",\"message\":\"Export succeeded\"}")));

    Handles.ExportHandle handle = f.adapter.startExport(everything());

    // the reset is the retryable failure the poll step counts, not a fatal one
    assertThatThrownBy(() -> f.adapter.pollExport(handle))
        .isInstanceOf(JrsUnreachableException.class);

    // and the next poll on the same adapter works: a dead connection does not poison the client
    Handles.ExportStatus status = f.adapter.pollExport(handle);

    assertThat(status.phase()).isEqualTo(Handles.Phase.READY);
    wm.verify(2, getRequestedFor(urlPathEqualTo(f.path("/rest_v2/export/exp-1/state"))));
  }

  @Test
  void should_give_up_with_an_unreachable_server_when_every_poll_is_reset() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        post(urlPathEqualTo(f.path("/rest_v2/export")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"exp-1\",\"phase\":\"inprogress\"}")));
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/export/exp-1/state")))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

    Handles.ExportHandle handle = f.adapter.startExport(everything());

    assertThatThrownBy(() -> f.adapter.pollExport(handle))
        .isInstanceOf(JrsUnreachableException.class);
  }

  @Test
  void should_refuse_a_download_whose_connection_dies_part_way(@TempDir Path dir)
      throws IOException {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        post(urlPathEqualTo(f.path("/rest_v2/export")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"exp-1\",\"phase\":\"ready\"}")));
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/export/exp-1/export.zip")))
            .willReturn(aResponse().withFault(Fault.RANDOM_DATA_THEN_CLOSE)));

    Handles.ExportHandle handle = f.adapter.startExport(everything());
    Path target = dir.resolve("out.zip");

    assertThatThrownBy(() -> f.adapter.downloadExport(handle, target))
        .isInstanceOf(RuntimeException.class);
    assertThat(Files.exists(target))
        .as("a half-downloaded archive must not be left where a plan would use it")
        .isFalse();
  }

  private static com.jaspersoft.jrsupgrade.jrs.api.ExportRequest everything() {
    return new com.jaspersoft.jrsupgrade.jrs.api.ExportRequest(
        com.jaspersoft.jrsupgrade.jrs.api.ExportRequest.Scope.EVERYTHING,
        java.util.Set.of(),
        false,
        false,
        false,
        false,
        false,
        true,
        Path.of("out.zip"));
  }
}
