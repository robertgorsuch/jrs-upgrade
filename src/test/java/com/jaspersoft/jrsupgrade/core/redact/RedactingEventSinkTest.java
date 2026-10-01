package com.jaspersoft.jrsupgrade.core.redact;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.engine.StepFailure;
import com.jaspersoft.jrsupgrade.core.event.Event;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RedactingEventSinkTest {

  private static final Instant TS = Instant.parse("2026-01-01T00:00:00Z");
  private static final String SECRET = "hunter2!";

  private final List<Event> received = new ArrayList<>();
  private final Redactor redactor = new Redactor();
  private final RedactingEventSink sink = new RedactingEventSink(received::add, redactor);

  RedactingEventSinkTest() {
    redactor.register(SECRET);
  }

  @Test
  void should_redact_log_message_when_it_contains_a_registered_secret() {
    sink.emit(
        new Event.Log(
            TS, "run1", Optional.of("s1"), "apply", Event.Log.Level.INFO, "pw is " + SECRET));

    assertThat(received).singleElement().isInstanceOf(Event.Log.class);
    Event.Log log = (Event.Log) received.get(0);
    assertThat(log.message()).isEqualTo("pw is [redacted]");
    assertThat(log.runId()).isEqualTo("run1");
    assertThat(log.stepId()).contains("s1");
  }

  @Test
  void should_strip_userinfo_and_query_from_affected_uris_when_step_fails() {
    StepFailure failure =
        new StepFailure.Recoverable(
            "export failed",
            List.of(),
            List.of(
                URI.create("https://admin:" + SECRET + "@jrs.example:8443/rest_v2/export?pp=tok"),
                URI.create("/repository/reports/AllAccounts")),
            List.of(),
            "retry");

    sink.emit(new Event.StepFailed(TS, "run1", Optional.of("s1"), "apply", failure));

    Event.StepFailed e = (Event.StepFailed) received.get(0);
    assertThat(e.failure().affectedUris())
        .containsExactly(
            URI.create("https://jrs.example:8443/rest_v2/export"),
            URI.create("/repository/reports/AllAccounts"));
    assertThat(e.toString()).doesNotContain(SECRET).doesNotContain("pp=tok");
  }

  @Test
  void should_redact_step_failure_fields_when_step_fails() {
    StepFailure failure =
        new StepFailure.Recoverable(
            "login failed with " + SECRET,
            List.of(Path.of("a")),
            List.of(),
            List.of(Path.of("b")),
            "retry with password=" + SECRET);

    sink.emit(new Event.StepFailed(TS, "run1", Optional.of("s1"), "apply", failure));

    Event.StepFailed e = (Event.StepFailed) received.get(0);
    assertThat(e.failure()).isInstanceOf(StepFailure.Recoverable.class);
    assertThat(e.failure().cause()).isEqualTo("login failed with [redacted]");
    assertThat(e.failure().nextAction()).isEqualTo("retry with password=[redacted]");
    assertThat(e.failure().affectedPaths()).containsExactly(Path.of("a"));
    assertThat(e.failure().backups()).containsExactly(Path.of("b"));
  }

  @Test
  void should_redact_every_free_text_field_when_each_event_type_is_emitted() {
    Optional<String> step = Optional.of("s1");
    List<Event> events =
        List.of(
            new Event.PlanCreated(TS, "r", "run", "plan", "sha256:x"),
            new Event.StepPending(TS, "r", step, "p", "title " + SECRET),
            new Event.StepRunning(TS, "r", step, "p", "title " + SECRET),
            new Event.StepRetry(TS, "r", step, "p", 2, 5, 100L, "cause " + SECRET),
            new Event.StepSucceeded(TS, "r", step, "p", 1L),
            new Event.StepFailed(
                TS, "r", step, "p", StepFailure.fatal("c " + SECRET, "n " + SECRET)),
            new Event.StepSkipped(TS, "r", step, "p", "reason " + SECRET),
            new Event.StepRolledBack(TS, "r", step, "p", 1L),
            new Event.StepRollbackFailed(TS, "r", step, "p", "cause " + SECRET, List.of()),
            new Event.Log(TS, "r", step, "p", Event.Log.Level.WARN, "m " + SECRET),
            new Event.RunSucceeded(TS, "r", "run", 1L),
            new Event.RunFailed(TS, "r", "run", "c " + SECRET, List.of(), "n " + SECRET, true),
            new Event.RunCancelled(TS, "r", "run", "detail " + SECRET),
            new Event.RunRolledBack(TS, "r", "run", "prepare", "cause " + SECRET));

    events.forEach(sink::emit);

    assertThat(received).hasSize(events.size());
    for (int i = 0; i < events.size(); i++) {
      Event out = received.get(i);
      assertThat(out.getClass()).isEqualTo(events.get(i).getClass());
      assertThat(out.toString()).as(out.type()).doesNotContain(SECRET);
    }
    assertThat(((Event.StepRetry) received.get(3)).cause()).isEqualTo("cause [redacted]");
    assertThat(((Event.RunFailed) received.get(11)).nextAction()).isEqualTo("n [redacted]");
    assertThat(((Event.RunFailed) received.get(11)).rollbackIncomplete()).isTrue();
  }

  @Test
  void should_redact_retryable_and_fatal_failures_when_classified() {
    assertThat(sink.redact(StepFailure.retryable("x " + SECRET, "y")).cause())
        .isEqualTo("x [redacted]");
    assertThat(sink.redact(StepFailure.fatal("x", "y " + SECRET)).nextAction())
        .isEqualTo("y [redacted]");
  }
}
