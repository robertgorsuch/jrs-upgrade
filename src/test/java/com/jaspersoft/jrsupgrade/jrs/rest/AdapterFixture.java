package com.jaspersoft.jrsupgrade.jrs.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jaspersoft.jrsupgrade.core.compat.CompatMatrix;
import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.core.secrets.Secret;
import com.jaspersoft.jrsupgrade.jrs.FakePlatform;
import com.jaspersoft.jrsupgrade.jrs.TestConfigs;
import com.jaspersoft.jrsupgrade.jrs.api.Credentials;
import com.jaspersoft.jrsupgrade.jrs.keystore.KeystoreInspector;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;

/** Wires a {@link RestJrsAdapter} against a WireMock server for the adapter tests. */
final class AdapterFixture {

  static final String CONTEXT = "/jasperserver-pro";
  static final String PASSWORD = "adm1n-Secret";

  final WireMockExtension wm;
  final Redactor redactor = new Redactor();
  final Config config;
  final RestClient client;
  final RestJrsAdapter adapter;
  final Secret password = Secret.fromString(PASSWORD);

  AdapterFixture(WireMockExtension wm, Config.AuthMode mode) {
    this(wm, mode, new FakePlatform(Platform.OsFamily.LINUX));
  }

  AdapterFixture(WireMockExtension wm, Config.AuthMode mode, Platform platform) {
    this.wm = wm;
    this.config = TestConfigs.server(base(), mode);
    this.client =
        RestClient.builder(base())
            .redactor(redactor)
            .networkMode(Config.NetworkMode.ISOLATED)
            .correlationId("run-1")
            .build();
    Credentials credentials = new Credentials("jasperadmin", password, Optional.empty());
    switch (mode) {
      case BASIC -> client.useBasic("jasperadmin", password);
      case TOKEN -> client.useToken(password);
      case FORM -> {}
    }
    this.adapter =
        new RestJrsAdapter(
            client,
            config,
            CompatMatrix.load(),
            Optional.of(credentials),
            new KeystoreInspector(platform, config));
  }

  URI base() {
    return URI.create("http://localhost:" + wm.getPort() + CONTEXT);
  }

  /** Stubs serverInfo with a fixture from {@code src/test/resources/fixtures}. */
  void serverInfo(String fixture) {
    try {
      wm.stubFor(
          get(urlPathEqualTo(CONTEXT + "/rest_v2/serverInfo"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody(ServerIdentitiesTest.fixture(fixture))));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Stubs the four probe endpoints for a fully capable PRO multi-tenant server. */
  void allProbesPresent() {
    probe("/rest_v2/export/jrs-upgrade-probe/state", 404);
    probe("/rest_v2/import/jrs-upgrade-probe/state", 404);
    probe("/rest_v2/organizations", 200);
    probe("/rest_v2/login", 405);
    // issue #112: the keys service answers on 7.5+ (204 with no custom keys, as 10.0.0 does),
    // and a commercial licence without clustering
    probe("/rest_v2/keys/", 204);
    licenseFeatures(false);
  }

  /** {@code GET /rest_v2/licenseFeatures} as the 10.0.0 server answers it (issue #112). */
  void licenseFeatures(boolean clustered) {
    wm.stubFor(
        any(urlPathEqualTo(CONTEXT + "/rest_v2/licenseFeatures"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"mt\":true,\"cl\":" + clustered + ",\"aud\":true}")));
  }

  /**
   * A present task endpoint answers the probe id with 404 and a JSON error body, as every recorded
   * server does ({@code jrs/src/test/resources/recordings}); other statuses are stubbed bare.
   */
  void probe(String path, int status) {
    com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder r =
        aResponse().withStatus(status);
    if (status == 404) {
      r =
          r.withHeader("Content-Type", "application/json")
              .withBody(
                  "{\"message\":\"No export task with id jrs-upgrade-probe.\","
                      + "\"errorCode\":\"no.such.export.process\",\"parameters\":[\"jrs-upgrade-probe\"]}");
    }
    wm.stubFor(any(urlPathEqualTo(CONTEXT + path)).willReturn(r));
  }

  String path(String rel) {
    return CONTEXT + rel;
  }
}
