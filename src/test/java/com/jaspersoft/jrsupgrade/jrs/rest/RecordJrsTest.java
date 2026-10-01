package com.jaspersoft.jrsupgrade.jrs.rest;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.recording.RecordSpecBuilder;
import com.jaspersoft.jrsupgrade.core.compat.CompatMatrix;
import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.core.secrets.Secret;
import com.jaspersoft.jrsupgrade.jrs.FakePlatform;
import com.jaspersoft.jrsupgrade.jrs.TestConfigs;
import com.jaspersoft.jrsupgrade.jrs.api.Credentials;
import com.jaspersoft.jrsupgrade.jrs.api.HealthReport;
import com.jaspersoft.jrsupgrade.jrs.api.ServerIdentity;
import com.jaspersoft.jrsupgrade.jrs.keystore.KeystoreInspector;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * The recorder half of the recorded-server harness (ADR-0011): drives the adapter contract calls
 * through a WireMock proxy at a live JasperReports Server and writes the exchanges into {@code
 * src/test/resources/recordings/<version>-<edition>/}, replacing what was there. Off unless {@code
 * -Djrs-upgrade.record.baseUrl=http://host:port/context} is given; the password comes from {@code
 * JRS_UPGRADE_RECORD_PASSWORD} (default {@code jasperadmin}) and never reaches the recording:
 * request header and body matchers and response cookies are stripped before the files are written,
 * and {@link RecordedJrsContractTest} refuses a recording that carries a credential. Run it with:
 *
 * <pre>
 * scripts/mvn.sh -pl jrs -am test -Dtest=RecordJrsTest -Dgroups=needs-jrs -Dexcluded.groups= \
 *     -Dsurefire.failIfNoSpecifiedTests=false -Djrs-upgrade.record.baseUrl=http://localhost:8080/jasperserver-pro
 * </pre>
 *
 * (from bash on either OS: PowerShell splits dotted -D arguments.) A recording is only accepted
 * when the live server passes the contract, so a broken server never becomes a fixture.
 */
@Tag("needs-jrs")
@EnabledIfSystemProperty(named = "jrs-upgrade.record.baseUrl", matches = ".+")
class RecordJrsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void record_the_live_server_into_the_recordings_directory(@TempDir Path tmp) throws Exception {
    URI target = URI.create(System.getProperty("jrs-upgrade.record.baseUrl"));
    String context = target.getPath().replaceAll("/+$", "");
    String origin =
        target.getScheme()
            + "://"
            + target.getHost()
            + (target.getPort() > 0 ? ":" + target.getPort() : "");
    String username = System.getProperty("jrs-upgrade.record.username", RecordedJrs.USERNAME);
    String password =
        Optional.ofNullable(System.getenv("JRS_UPGRADE_RECORD_PASSWORD"))
            .orElse(RecordedJrs.PASSWORD);

    Files.createDirectories(tmp.resolve("mappings"));
    Files.createDirectories(tmp.resolve("__files"));
    WireMockServer wm =
        new WireMockServer(wireMockConfig().dynamicPort().withRootDirectory(tmp.toString()));
    wm.start();
    ServerIdentity id;
    try {
      wm.startRecording(
          new RecordSpecBuilder()
              .forTarget(origin)
              .makeStubsPersistent(true)
              .ignoreRepeatRequests()
              .extractTextBodiesOver(1_000_000));
      RestJrsAdapter adapter =
          adapter(URI.create("http://localhost:" + wm.port() + context), username, password);
      id = adapter.identity();
      adapter.capabilities();
      HealthReport health = adapter.health();
      assertThat(health.ok()).as("the live server must pass the contract: " + health).isTrue();
      wm.stopRecording();
    } finally {
      wm.stop();
    }

    String name =
        System.getProperty("jrs-upgrade.record.name", id.version() + "-" + id.edition().name());
    Path dest = Path.of("src/test/resources/recordings").resolve(name);
    replace(tmp, dest, password.equals(username) ? Optional.empty() : Optional.of(password));
    RecordedJrs.writeMetadata(
        dest,
        new RecordedJrs.Metadata(
            id.version(),
            id.edition().name(),
            id.tenancy().name(),
            context,
            RecordedJrs.Metadata.RECORDED,
            LocalDate.now(ZoneOffset.UTC).toString(),
            "recorded from a live server, build " + id.build()));

    assertThat(CompatMatrix.load().find(id.version())).as("matrix row for the server").isPresent();
    System.out.println("recorded " + name + " into " + dest.toAbsolutePath());
  }

  private static RestJrsAdapter adapter(URI base, String username, String password) {
    Config config = TestConfigs.server(base, Config.AuthMode.BASIC);
    Secret secret = Secret.fromString(password);
    RestClient client =
        RestClient.builder(base)
            .redactor(new Redactor())
            .networkMode(Config.NetworkMode.ISOLATED)
            .correlationId("record")
            .build();
    client.useBasic(username, secret);
    Platform platform = new FakePlatform(Platform.OsFamily.LINUX);
    return new RestJrsAdapter(
        client,
        config,
        CompatMatrix.load(),
        Optional.of(new Credentials(username, secret, Optional.empty())),
        new KeystoreInspector(platform, config));
  }

  /** Copies mappings and files into {@code dest} (emptied first), stripping credentials. */
  private static void replace(Path from, Path dest, Optional<String> secret) throws IOException {
    if (Files.exists(dest)) {
      try (Stream<Path> walk = Files.walk(dest)) {
        walk.sorted(Comparator.reverseOrder())
            .forEach(
                p -> {
                  try {
                    Files.delete(p);
                  } catch (IOException e) {
                    throw new IllegalStateException(e);
                  }
                });
      }
    }
    for (String sub : new String[] {"mappings", "__files"}) {
      Path src = from.resolve(sub);
      if (!Files.isDirectory(src)) {
        continue;
      }
      try (Stream<Path> files = Files.list(src)) {
        for (Path file : files.filter(Files::isRegularFile).toList()) {
          Path out = dest.resolve(sub).resolve(file.getFileName().toString());
          Files.createDirectories(out.getParent());
          String text = Files.readString(file);
          if (sub.equals("mappings")) {
            text = sanitise(text);
          }
          if (secret.isPresent()) {
            text = text.replace(secret.get(), "<redacted>");
          }
          Files.writeString(out, text);
        }
      }
    }
  }

  /** Drops every request matcher that could carry a credential and every response cookie. */
  private static String sanitise(String mapping) throws IOException {
    JsonNode tree = JSON.readTree(mapping);
    ObjectNode request = (ObjectNode) tree.get("request");
    String url = request.path("url").asText(request.path("urlPath").asText(""));
    request.remove("headers");
    request.remove("cookies");
    request.remove("basicAuthCredentials");
    if (url.toLowerCase(Locale.ROOT).contains("login") || url.contains("j_spring")) {
      request.remove("bodyPatterns");
    }
    JsonNode response = tree.get("response");
    if (response != null && response.has("headers")) {
      ((ObjectNode) response.get("headers")).remove("Set-Cookie");
    }
    return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tree) + "\n";
  }
}
