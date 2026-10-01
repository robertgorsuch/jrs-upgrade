package com.jaspersoft.jrsupgrade.jrs.strategy;

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
import com.jaspersoft.jrsupgrade.jrs.TestConfigs;
import com.jaspersoft.jrsupgrade.jrs.api.Credentials;
import com.jaspersoft.jrsupgrade.jrs.keystore.KeystoreInspector;
import com.jaspersoft.jrsupgrade.jrs.rest.RestClient;
import com.jaspersoft.jrsupgrade.jrs.rest.RestJrsAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** A real {@link RestJrsAdapter} against WireMock with a keystore in a temp home. */
final class RestFixture {

  static final String CONTEXT = "/jasperserver-pro";
  static final String KEYSTORE_BYTES = "keystore-bytes-for-fingerprint";

  final WireMockExtension wm;
  final Config config;
  final RestJrsAdapter adapter;
  final Path keystoreFile;

  RestFixture(WireMockExtension wm, Platform platform, Redactor redactor, Path homeDir)
      throws IOException {
    this.wm = wm;
    this.config = TestConfigs.server(base(), Config.AuthMode.BASIC);
    Files.createDirectories(homeDir);
    this.keystoreFile = homeDir.resolve(KeystoreInspector.KEYSTORE_FILE);
    Files.writeString(keystoreFile, KEYSTORE_BYTES, StandardCharsets.US_ASCII);
    Files.writeString(homeDir.resolve(KeystoreInspector.PROPERTIES_FILE), "ks=x\n");
    KeystoreInspector.Homes homes =
        new KeystoreInspector.Homes(
            homeDir.resolve("Users"), homeDir.resolve("passwd"), homeDir.resolve("home"), homeDir);
    RestClient client =
        RestClient.builder(base())
            .redactor(redactor)
            .networkMode(Config.NetworkMode.ISOLATED)
            .correlationId(StrategyFixture.RUN)
            .build();
    Secret password = Secret.fromString("adm1n-Secret");
    client.useBasic("jasperadmin", password);
    this.adapter =
        new RestJrsAdapter(
            client,
            config,
            CompatMatrix.load(),
            Optional.of(new Credentials("jasperadmin", password, Optional.empty())),
            new KeystoreInspector(platform, config, homes));
    serverInfo("serverInfo-8.2.0-PRO.json");
    probe("/rest_v2/export/jrs-upgrade-probe/state", 404);
    probe("/rest_v2/import/jrs-upgrade-probe/state", 404);
    probe("/rest_v2/organizations", 200);
    probe("/rest_v2/login", 405);
  }

  URI base() {
    return URI.create("http://localhost:" + wm.getPort() + CONTEXT);
  }

  String path(String rel) {
    return CONTEXT + rel;
  }

  private void serverInfo(String fixture) throws IOException {
    String body;
    try (InputStream in = RestFixture.class.getResourceAsStream("/fixtures/" + fixture)) {
      if (in == null) {
        throw new IOException("missing fixture " + fixture);
      }
      body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    wm.stubFor(
        get(urlPathEqualTo(path("/rest_v2/serverInfo")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  private void probe(String rel, int status) {
    com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder r =
        aResponse().withStatus(status);
    if (status == 404) {
      r =
          r.withHeader("Content-Type", "application/json")
              .withBody(
                  "{\"message\":\"No export task with id jrs-upgrade-probe.\",\"errorCode\":\"no.such.export.process\"}");
    }
    wm.stubFor(any(urlPathEqualTo(path(rel))).willReturn(r));
  }
}
