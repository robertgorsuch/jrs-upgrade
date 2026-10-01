package com.jaspersoft.jrsupgrade.jrs.rest;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
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
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The recorded-server harness (ADR-0011): every directory under {@code
 * src/test/resources/recordings/<version>-<edition>} is one JasperReports Server captured in
 * WireMock's stub format ({@code mappings/}, optional {@code __files/}) with a {@code
 * recording.json} that says which server it is and whether it was recorded from a real one or
 * synthesised from a serverInfo fixture. {@link RecordedJrsContractTest} replays each recording
 * through the real {@link RestJrsAdapter}, so the adapter contract runs for every compat-matrix row
 * on any machine, with no Docker and no server. {@link RecordJrsTest} produces a recording from a
 * live server. Recordings carry no credential: the recorder strips them and the contract test
 * checks.
 */
final class RecordedJrs {

  static final String ROOT = "/recordings";
  static final String USERNAME = "jasperadmin";

  /** The default administrator password; a recording never contains it. */
  static final String PASSWORD = "jasperadmin";

  private static final ObjectMapper JSON = new ObjectMapper();

  private RecordedJrs() {}

  /** What {@code recording.json} says about a recording. */
  record Metadata(
      String version,
      String edition,
      String tenancy,
      String context,
      String source,
      String recordedAt,
      String note) {

    static final String RECORDED = "recorded";
    static final String SYNTHESISED = "synthesised";
  }

  /** One recording directory. */
  record Recording(String name, Path dir, Metadata meta) {

    /** Starts WireMock on the recording and wires an adapter at it, Basic auth as recorded. */
    Replay replay() {
      WireMockServer server =
          new WireMockServer(
              wireMockConfig().dynamicPort().usingFilesUnderDirectory(dir.toString()));
      server.start();
      URI base = URI.create("http://localhost:" + server.port() + meta.context());
      Config config = TestConfigs.server(base, Config.AuthMode.BASIC);
      Secret password = Secret.fromString(PASSWORD);
      RestClient client =
          RestClient.builder(base)
              .redactor(new Redactor())
              .networkMode(Config.NetworkMode.ISOLATED)
              .correlationId("recorded-" + name)
              .build();
      client.useBasic(USERNAME, password);
      Platform platform = new FakePlatform(Platform.OsFamily.LINUX);
      RestJrsAdapter adapter =
          new RestJrsAdapter(
              client,
              config,
              CompatMatrix.load(),
              Optional.of(new Credentials(USERNAME, password, Optional.empty())),
              new KeystoreInspector(platform, config));
      return new Replay(server, adapter);
    }

    @Override
    public String toString() {
      return name + " (" + meta.source() + ")";
    }
  }

  /** A running replay; closing stops the WireMock server. */
  record Replay(WireMockServer server, RestJrsAdapter adapter) implements AutoCloseable {
    @Override
    public void close() {
      server.stop();
    }
  }

  /** Every recording on the test classpath, sorted by name. */
  static List<Recording> all() {
    Path root = root();
    List<Recording> out = new ArrayList<>();
    try (Stream<Path> dirs = Files.list(root)) {
      dirs.filter(Files::isDirectory)
          .sorted(Comparator.comparing(p -> p.getFileName().toString()))
          .forEach(dir -> out.add(new Recording(dir.getFileName().toString(), dir, metadata(dir))));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return List.copyOf(out);
  }

  static Metadata metadata(Path dir) {
    Path file = dir.resolve("recording.json");
    try {
      return JSON.readValue(Files.readString(file), Metadata.class);
    } catch (IOException e) {
      throw new UncheckedIOException("unreadable " + file, e);
    }
  }

  static void writeMetadata(Path dir, Metadata meta) throws IOException {
    Files.writeString(
        dir.resolve("recording.json"),
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(meta) + "\n");
  }

  /** The recordings directory on the test classpath (a real directory under target/). */
  static Path root() {
    URL url = RecordedJrs.class.getResource(ROOT);
    if (url == null) {
      throw new IllegalStateException("no " + ROOT + " directory on the test classpath");
    }
    try {
      return Path.of(url.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
