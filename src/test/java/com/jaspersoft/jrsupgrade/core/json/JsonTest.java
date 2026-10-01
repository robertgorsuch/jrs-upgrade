package com.jaspersoft.jrsupgrade.core.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsupgrade.core.engine.PlanFingerprint;
import com.jaspersoft.jrsupgrade.core.engine.PlanSummary;
import com.jaspersoft.jrsupgrade.core.engine.StepFailure;
import com.jaspersoft.jrsupgrade.core.event.Event;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class JsonTest {

  private static final Instant TS = Instant.parse("2026-09-08T10:15:00.123Z");
  private static final String RUN = "r-20260908-101500-ab12";
  private static final Path BACKUP =
      Path.of("C:/jrs-upgrade/snapshots/s-1/lib.jar").toAbsolutePath();

  private static StepFailure recoverable() {
    return new StepFailure.Recoverable(
        "disk full",
        List.of(BACKUP),
        List.of(URI.create("http://localhost:8080/jasperserver/rest_v2/serverInfo")),
        List.of(BACKUP),
        "free space and retry");
  }

  static Stream<Event> events() {
    Optional<String> s1 = Optional.of("s1");
    return Stream.of(
        new Event.PlanCreated(TS, RUN, "run", "p-1", "sha256:abc"),
        new Event.StepPending(TS, RUN, s1, "apply", "Stop service"),
        new Event.StepRunning(TS, RUN, s1, "apply", "Stop service"),
        new Event.StepRetry(TS, RUN, s1, "apply", 2, 5, 2000L, "503"),
        new Event.StepSucceeded(TS, RUN, s1, "apply", 42L),
        new Event.StepFailed(TS, RUN, s1, "apply", recoverable()),
        new Event.StepSkipped(TS, RUN, s1, "apply", "irreversible"),
        new Event.StepRolledBack(TS, RUN, s1, "apply", 7L),
        new Event.StepRollbackFailed(TS, RUN, s1, "apply", "restore failed", List.of(BACKUP)),
        new Event.Log(TS, RUN, s1, "apply", Event.Log.Level.WARN, "slow disk"),
        new Event.Log(TS, RUN, Optional.empty(), "run", Event.Log.Level.INFO, "hello"),
        new Event.RunSucceeded(TS, RUN, "run", 1234L),
        new Event.RunFailed(TS, RUN, "run", "boom", List.of(BACKUP), "restore", true),
        new Event.RunCancelled(TS, RUN, "run", "ctrl-c"),
        new Event.RunRolledBack(TS, RUN, "run", "apply", "boom"));
  }

  @ParameterizedTest
  @MethodSource("events")
  void should_round_trip_every_event_variant_when_serialised_as_event(Event event)
      throws Exception {
    String json = Json.write(event);
    JsonNode node = Json.mapper().readTree(json);

    assertThat(node.get("type").asText()).isEqualTo(event.getClass().getSimpleName());
    assertThat(node.get("ts").asText()).isEqualTo("2026-09-08T10:15:00.123Z");
    assertThat(node.has("stepId")).isTrue();
    if (event.stepId().isPresent()) {
      assertThat(node.get("stepId").asText()).isEqualTo(event.stepId().get());
    } else {
      assertThat(node.get("stepId").isNull()).isTrue();
    }

    Event back = Json.read(json, Event.class);
    assertThat(back).isEqualTo(event);
  }

  @Test
  void should_cover_every_permitted_event_subtype_when_round_tripping() {
    Set<Class<?>> covered = new HashSet<>();
    events().forEach(e -> covered.add(e.getClass()));
    assertThat(covered).containsExactlyInAnyOrder(Event.class.getPermittedSubclasses());
  }

  @Test
  void should_round_trip_each_step_failure_kind_with_type_discriminator_when_serialised()
      throws Exception {
    List<StepFailure> failures =
        List.of(
            StepFailure.retryable("503", "wait"),
            recoverable(),
            StepFailure.fatal("keystore corrupt", "restore manually"));
    for (StepFailure f : failures) {
      String json = Json.write(f);
      assertThat(Json.mapper().readTree(json).get("type").asText())
          .isEqualTo(f.getClass().getSimpleName());
      assertThat(Json.read(json, StepFailure.class)).isEqualTo(f);
    }
  }

  @Test
  void should_round_trip_plan_summary_and_fingerprint_when_serialised() {
    PlanSummary summary =
        new PlanSummary(
            "hotfix apply",
            "server-1",
            List.of(BACKUP),
            List.of("/organizations/org_1"),
            true,
            List.of(BACKUP),
            Map.of("apply", "before apply", "verify", "after apply"),
            "rest",
            List.of("database rollback is the operator's responsibility"));
    PlanFingerprint fp = PlanFingerprint.of(Map.of("server", "srv-1", "bundle", "sha256:abc"));

    assertThat(Json.read(Json.write(summary), PlanSummary.class)).isEqualTo(summary);
    assertThat(Json.read(Json.write(fp), PlanFingerprint.class)).isEqualTo(fp);
  }

  @Test
  void should_ignore_unknown_properties_when_reading() {
    String json =
        "{\"type\":\"RunCancelled\",\"ts\":\"2026-09-08T10:15:00Z\",\"runId\":\"r1\","
            + "\"phase\":\"run\",\"detail\":\"x\",\"futureField\":42}";
    Event e = Json.read(json, Event.class);
    assertThat(e)
        .isEqualTo(new Event.RunCancelled(Instant.parse("2026-09-08T10:15:00Z"), "r1", "run", "x"));
  }

  @Test
  void should_indent_only_with_the_pretty_mapper_when_writing() {
    Event e = new Event.RunCancelled(TS, RUN, "run", "x");
    assertThat(Json.write(e)).doesNotContain("\n");
    assertThat(Json.writePretty(e)).contains("\n");
  }
}
