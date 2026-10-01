package com.jaspersoft.jrsupgrade.jrs.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.compat.CompatMatrix;
import com.jaspersoft.jrsupgrade.jrs.api.Capability;
import com.jaspersoft.jrsupgrade.jrs.api.HealthReport;
import com.jaspersoft.jrsupgrade.jrs.api.ServerIdentity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The adapter contract (spec §16, §20: "adapter contract suite green across every version in the
 * matrix"), run against every recorded server instead of a container (ADR-0011). {@link
 * LiveJrsContainerTest} runs the same contract against a real server when Docker and an image are
 * available; this suite runs everywhere, on every build.
 */
class RecordedJrsContractTest {

  static Stream<RecordedJrs.Recording> recordings() {
    return RecordedJrs.all().stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recordings")
  void should_report_the_identity_the_recording_claims_when_replayed(RecordedJrs.Recording r) {
    try (RecordedJrs.Replay replay = r.replay()) {
      ServerIdentity id = replay.adapter().identity();

      assertThat(id.version()).isEqualTo(r.meta().version());
      assertThat(id.edition().name()).isEqualTo(r.meta().edition());
      assertThat(id.tenancy().name()).isEqualTo(r.meta().tenancy());
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recordings")
  void should_find_exactly_the_matrix_capabilities_when_replayed(RecordedJrs.Recording r) {
    try (RecordedJrs.Replay replay = r.replay()) {
      Set<Capability> found = replay.adapter().capabilities();

      Set<Capability> matrix =
          CompatMatrix.load().expectedCapabilities(r.meta().version(), r.meta().edition()).stream()
              .map(n -> Capability.valueOf(n.toUpperCase(Locale.ROOT)))
              .collect(Collectors.toCollection(() -> EnumSet.noneOf(Capability.class)));
      assertThat(found).as(replay.adapter().probeResults().toString()).isEqualTo(matrix);
      assertThat(replay.adapter().expectedCapabilities()).isEqualTo(matrix);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recordings")
  void should_pass_health_when_replayed(RecordedJrs.Recording r) {
    try (RecordedJrs.Replay replay = r.replay()) {
      HealthReport report = replay.adapter().health();

      assertThat(report.reachable()).isTrue();
      assertThat(report.ok()).as(report.items().toString()).isTrue();
      assertThat(report.items())
          .extracting(HealthReport.Item::name)
          .contains("serverInfo", "login", "repository");
    }
  }

  /** A matrix row without a recording is a row the contract never ran on. */
  @Test
  void should_cover_every_matrix_row_and_edition() {
    CompatMatrix matrix = CompatMatrix.load();
    List<RecordedJrs.Recording> recordings = RecordedJrs.all();
    List<String> uncovered = new ArrayList<>();
    for (CompatMatrix.Entry entry : matrix.entries()) {
      for (String edition : entry.editions()) {
        boolean covered =
            recordings.stream()
                .anyMatch(
                    r ->
                        r.meta().edition().equalsIgnoreCase(edition)
                            && matrix
                                .find(r.meta().version())
                                .map(e -> e.range().equals(entry.range()))
                                .orElse(false));
        if (!covered) {
          uncovered.add(entry.label() + " " + edition);
        }
      }
    }
    assertThat(uncovered).as("matrix rows without a recording").isEmpty();
    assertThat(recordings).isNotEmpty();
  }

  /** Every recording is complete, says where it came from, and carries no credential. */
  @Test
  void should_carry_metadata_and_no_credential_in_any_recording() throws IOException {
    for (RecordedJrs.Recording r : RecordedJrs.all()) {
      assertThat(r.meta().source())
          .as(r.name() + " source")
          .isIn(RecordedJrs.Metadata.RECORDED, RecordedJrs.Metadata.SYNTHESISED);
      assertThat(r.meta().context()).as(r.name() + " context").startsWith("/");
      assertThat(r.meta().recordedAt())
          .as(r.name() + " recordedAt")
          .matches("\\d{4}-\\d{2}-\\d{2}");
      Path mappings = r.dir().resolve("mappings");
      assertThat(mappings).as(r.name() + " mappings").isDirectory();
      try (Stream<Path> files = Files.walk(r.dir())) {
        List<Path> regular = files.filter(Files::isRegularFile).toList();
        assertThat(regular.stream().filter(p -> p.startsWith(mappings)).count())
            .as(r.name() + " has stub mappings")
            .isPositive();
        for (Path file : regular) {
          String text = Files.readString(file, StandardCharsets.UTF_8);
          assertThat(text)
              .as(r.dir().relativize(file).toString())
              .doesNotContain("\"Authorization\"")
              .doesNotContain("j_password=")
              .doesNotContain("Set-Cookie")
              .doesNotContainIgnoringCase("password\"");
        }
      }
    }
  }
}
