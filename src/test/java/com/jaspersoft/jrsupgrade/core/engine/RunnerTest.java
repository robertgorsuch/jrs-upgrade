package com.jaspersoft.jrsupgrade.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.event.Event;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.core.state.StateStore;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunnerTest {

  private static final String RUN = "r-20260908-101500-0001";

  @TempDir Path tmp;
  private EngineFixture fx;

  @BeforeEach
  void setUp() {
    fx = new EngineFixture(tmp);
  }

  @AfterEach
  void tearDown() {
    fx.close();
  }

  private RunOutcome run(Plan plan) {
    return fx.runner.run(plan, fx.context(RUN), EngineFixture.fingerprint(), RunOptions.DEFAULT);
  }

  private List<String> journal() {
    return fx.store.transitions(RUN).stream().map(t -> t.stepId() + ":" + t.toState()).toList();
  }

  /**
   * Issue #160: the JSON log recorded nothing about a run; every line the runner writes now names
   * the run (MDC {@code runId}) and there is one line for the start, each transition and the end.
   */
  @Test
  void should_log_the_start_each_transition_and_the_end_with_the_run_id_when_a_run_executes() {
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.start();
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(Runner.class);
    logger.addAppender(appender);
    try {
      run(EngineFixture.plan("p1", fx.step("s1", "apply"), fx.step("s2", "apply")));
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(appender.list)
        .isNotEmpty()
        .allSatisfy(e -> assertThat(e.getMDCPropertyMap()).containsEntry("runId", RUN));
    assertThat(appender.list)
        .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
        .anySatisfy(m -> assertThat(m).contains("run " + RUN + " started"))
        .anySatisfy(m -> assertThat(m).contains("s1").contains("SUCCEEDED"))
        .anySatisfy(m -> assertThat(m).contains("s2").contains("SUCCEEDED"))
        .anySatisfy(m -> assertThat(m).contains("run " + RUN + " ended SUCCEEDED"));
    assertThat(org.slf4j.MDC.get("runId")).as("the run id must not leak past the run").isNull();
  }

  /**
   * A step that records a mutation made by an earlier phase undoes the whole run when it fails: a
   * phase-scoped rollback would leave that mutation in place while reporting "rolled back"
   * (assessment item H4).
   */
  @Test
  void should_undo_earlier_phases_when_a_step_that_rolls_back_all_on_failure_fails() {
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply"),
            fx.step("s2", "record")
                .failureRollsBackAll()
                .executeReturns(StepResult.failed(StepFailure.recoverable("busy", "retry"))));

    RunOutcome outcome = run(plan);

    assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(((RunOutcome.RolledBack) outcome).rolledBackToPhase()).isEqualTo("apply");
    assertThat(fx.trace).containsExactly("exec:s1", "exec:s2", "comp:s2", "comp:s1");
  }

  @Test
  void should_roll_back_steps_4_3_2_1_in_order_when_step_4_fails_recoverably() {
    JournalCheckingSink check = new JournalCheckingSink(fx.store);
    fx.bus.subscribe(check);
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply"),
            fx.step("s2", "apply"),
            fx.step("s3", "apply"),
            fx.step("s4", "apply")
                .executeReturns(
                    StepResult.failed(StepFailure.recoverable("disk full", "free space"))),
            fx.step("s5", "apply"));

    RunOutcome outcome = run(plan);

    assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(outcome.exitCode()).isEqualTo(3);
    assertThat(((RunOutcome.RolledBack) outcome).rolledBackToPhase()).isEqualTo("apply");
    assertThat(fx.trace)
        .containsExactly(
            "exec:s1", "exec:s2", "exec:s3", "exec:s4", "comp:s4", "comp:s3", "comp:s2", "comp:s1");
    assertThat(journal())
        .containsExactly(
            "s1:PENDING",
            "s1:RUNNING",
            "s1:SUCCEEDED",
            "s2:PENDING",
            "s2:RUNNING",
            "s2:SUCCEEDED",
            "s3:PENDING",
            "s3:RUNNING",
            "s3:SUCCEEDED",
            "s4:PENDING",
            "s4:RUNNING",
            "s4:FAILED",
            "s4:ROLLED_BACK",
            "s3:ROLLED_BACK",
            "s2:ROLLED_BACK",
            "s1:ROLLED_BACK");
    assertThat(fx.sink.of(Event.StepRolledBack.class))
        .extracting(e -> e.stepId().orElseThrow())
        .containsExactly("s4", "s3", "s2", "s1");
    assertThat(fx.sink.types()).startsWith("PlanCreated").endsWith("RunRolledBack");
    assertThat(check.violations).isEmpty();
    assertThat(fx.store.run(RUN).orElseThrow().terminalState()).contains(TerminalState.ROLLED_BACK);
    assertThat(fx.store.run(RUN).orElseThrow().exitCode()).contains(3);
  }

  @Test
  void should_stop_rollback_at_phase_boundary_when_failure_is_in_a_later_phase() {
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "prepare"),
            fx.step("s2", "prepare"),
            fx.step("s3", "apply"),
            fx.step("s4", "apply")
                .executeReturns(StepResult.failed(StepFailure.recoverable("boom", "retry"))));

    RunOutcome outcome = run(plan);

    assertThat(outcome).isEqualTo(new RunOutcome.RolledBack("apply", "boom"));
    assertThat(fx.trace)
        .containsExactly("exec:s1", "exec:s2", "exec:s3", "exec:s4", "comp:s4", "comp:s3");
  }

  @Test
  void should_roll_back_every_phase_when_rollback_all_is_requested() {
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "prepare"),
            fx.step("s2", "apply"),
            fx.step("s3", "apply")
                .executeReturns(StepResult.failed(StepFailure.recoverable("boom", "retry"))));

    RunOutcome outcome =
        fx.runner.run(
            plan, fx.context(RUN), EngineFixture.fingerprint(), RunOptions.withRollbackAll());

    assertThat(outcome).isEqualTo(new RunOutcome.RolledBack("prepare", "boom"));
    assertThat(fx.trace)
        .containsExactly("exec:s1", "exec:s2", "exec:s3", "comp:s3", "comp:s2", "comp:s1");
  }

  @Test
  void should_report_rollback_incomplete_with_exit_4_when_a_compensation_fails() {
    Path backup = tmp.resolve("backup.zip");
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply"),
            fx.step("s2", "apply")
                .compensateReturns(
                    StepResult.failed(
                        new StepFailure.Recoverable(
                            "restore failed", List.of(), List.of(), List.of(backup), "manual"))),
            fx.step("s3", "apply")
                .executeReturns(StepResult.failed(StepFailure.recoverable("boom", "retry"))));

    RunOutcome outcome = run(plan);

    assertThat(outcome).isInstanceOf(RunOutcome.Failed.class);
    RunOutcome.Failed failed = (RunOutcome.Failed) outcome;
    assertThat(failed.rollbackIncomplete()).isTrue();
    assertThat(failed.exitCode()).isEqualTo(4);
    assertThat(failed.backups()).containsExactly(backup);
    assertThat(fx.trace).containsExactly("exec:s1", "exec:s2", "exec:s3", "comp:s3", "comp:s2");
    assertThat(fx.sink.of(Event.StepRollbackFailed.class))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.stepId()).contains("s2");
              assertThat(e.backups()).containsExactly(backup);
            });
    assertThat(journal()).endsWith("s3:FAILED", "s3:ROLLED_BACK", "s2:ROLLBACK_FAILED");
    assertThat(fx.sink.of(Event.RunFailed.class))
        .singleElement()
        .extracting(Event.RunFailed::rollbackIncomplete)
        .isEqualTo(true);
    assertThat(fx.store.run(RUN).orElseThrow().terminalState()).contains(TerminalState.FAILED);
  }

  @Test
  void should_emit_two_retries_and_no_rollback_when_step_fails_retryably_twice_then_succeeds() {
    AtomicInteger calls = new AtomicInteger();
    RetryPolicy policy = new RetryPolicy(5, Duration.ofSeconds(2), 2.0, Duration.ofSeconds(60), 0);
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply"),
            fx.step("s2", "apply")
                .retry(policy)
                .onExecute(
                    (ctx, out) ->
                        calls.incrementAndGet() <= 2
                            ? StepResult.failed(StepFailure.retryable("503", "wait"))
                            : StepResult.ok()),
            fx.step("s3", "apply"));

    RunOutcome outcome = run(plan);

    assertThat(outcome).isEqualTo(new RunOutcome.Succeeded());
    assertThat(outcome.exitCode()).isZero();
    assertThat(calls.get()).isEqualTo(3);
    assertThat(fx.sink.of(Event.StepRetry.class))
        .extracting(Event.StepRetry::attempt)
        .containsExactly(2, 3);
    assertThat(fx.sink.of(Event.StepRetry.class))
        .extracting(Event.StepRetry::delayMillis)
        .containsExactly(2000L, 4000L);
    assertThat(fx.sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));
    assertThat(fx.sink.of(Event.StepRolledBack.class)).isEmpty();
    assertThat(fx.trace).containsExactly("exec:s1", "exec:s2", "exec:s2", "exec:s2", "exec:s3");
    assertThat(fx.store.run(RUN).orElseThrow().terminalState()).contains(TerminalState.SUCCEEDED);
  }

  @Test
  void should_treat_exhausted_retries_as_recoverable_when_step_keeps_failing() {
    RetryPolicy policy = new RetryPolicy(2, Duration.ofMillis(10), 1.0, Duration.ofMillis(10), 0);
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply"),
            fx.step("s2", "apply")
                .retry(policy)
                .executeReturns(StepResult.failed(StepFailure.retryable("503", "wait"))));

    RunOutcome outcome = run(plan);

    assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(fx.sink.of(Event.StepRetry.class)).hasSize(1);
    assertThat(fx.sink.of(Event.StepFailed.class))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.failure()).isInstanceOf(StepFailure.Recoverable.class);
              assertThat(e.failure().cause()).contains("503").contains("2 attempts");
            });
    assertThat(fx.trace).containsExactly("exec:s1", "exec:s2", "exec:s2", "comp:s2", "comp:s1");
  }

  @Test
  void should_compensate_3_2_1_and_return_cancelled_when_cancelled_inside_step_3() {
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply"),
            fx.step("s2", "apply"),
            fx.step("s3", "apply")
                .onExecute(
                    (ctx, out) -> {
                      ctx.cancel().cancel("operator pressed Ctrl-C");
                      ctx.cancel().checkpoint();
                      return StepResult.ok();
                    }),
            fx.step("s4", "apply"));

    RunOutcome outcome = run(plan);

    assertThat(outcome).isEqualTo(new RunOutcome.Cancelled("operator pressed Ctrl-C"));
    assertThat(outcome.exitCode()).isEqualTo(5);
    assertThat(fx.trace)
        .containsExactly("exec:s1", "exec:s2", "exec:s3", "comp:s3", "comp:s2", "comp:s1");
    assertThat(fx.sink.types()).endsWith("RunCancelled");
    assertThat(journal())
        .endsWith("s3:FAILED", "s3:ROLLED_BACK", "s2:ROLLED_BACK", "s1:ROLLED_BACK");
    assertThat(fx.store.run(RUN).orElseThrow().terminalState()).contains(TerminalState.CANCELLED);
  }

  @Test
  void should_compensate_succeeded_steps_when_cancelled_between_steps() {
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply")
                .onExecute(
                    (ctx, out) -> {
                      ctx.cancel().cancel("timeout");
                      return StepResult.ok();
                    }),
            fx.step("s2", "apply"));

    RunOutcome outcome = run(plan);

    assertThat(outcome).isEqualTo(new RunOutcome.Cancelled("timeout"));
    assertThat(fx.trace).containsExactly("exec:s1", "comp:s1");
  }

  @Test
  void should_return_fingerprint_mismatch_and_write_nothing_when_inputs_changed() {
    Plan plan = EngineFixture.plan("p1", fx.step("s1", "apply"));
    PlanFingerprint recomputed =
        PlanFingerprint.of(Map.of("server", "srv-1", "bundle", "sha256:changed"));

    RunOutcome outcome = fx.runner.run(plan, fx.context(RUN), recomputed, RunOptions.DEFAULT);

    assertThat(outcome).isEqualTo(new RunOutcome.FingerprintMismatch(List.of("bundle")));
    assertThat(outcome.exitCode()).isEqualTo(2);
    assertThat(fx.trace).isEmpty();
    assertThat(fx.sink.events()).isEmpty();
    assertThat(fx.store.runs(10)).isEmpty();
    assertThat(fx.store.transitions(RUN)).isEmpty();
  }

  @Test
  void should_return_precheck_failed_with_exit_2_and_no_mutation_when_step_1_precheck_fails() {
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply").precheck(ctx -> CheckResult.fail("service running", "stop it")),
            fx.step("s2", "apply"));

    RunOutcome outcome = run(plan);

    assertThat(outcome)
        .isEqualTo(new RunOutcome.PrecheckFailed("s1", "service running", "stop it"));
    assertThat(outcome.exitCode()).isEqualTo(2);
    assertThat(fx.trace).isEmpty();
    assertThat(journal()).containsExactly("s1:PENDING", "s1:FAILED");
    assertThat(fx.store.run(RUN).orElseThrow().terminalState())
        .contains(TerminalState.PRECHECK_FAILED);
    assertThat(fx.store.run(RUN).orElseThrow().exitCode()).contains(2);
    assertThat(fx.sink.of(Event.RunFailed.class))
        .singleElement()
        .extracting(Event.RunFailed::rollbackIncomplete)
        .isEqualTo(false);
  }

  @Test
  void should_roll_back_when_a_precheck_fails_after_earlier_steps_mutated() {
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply"),
            fx.step("s2", "apply").precheck(ctx -> CheckResult.fail("port busy", "free it")));

    RunOutcome outcome = run(plan);

    assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
    // s2 never executed, so it has nothing to compensate
    assertThat(fx.trace).containsExactly("exec:s1", "comp:s1");
    assertThat(journal()).endsWith("s2:FAILED", "s1:ROLLED_BACK");
  }

  @Test
  void should_report_exit_4_when_the_failing_step_itself_cannot_be_compensated() {
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply"),
            fx.step("s2", "apply")
                .executeReturns(StepResult.failed(StepFailure.recoverable("half swapped", "x")))
                .compensateReturns(
                    StepResult.failed(StepFailure.recoverable("cannot restore", "manual"))));

    RunOutcome outcome = run(plan);

    assertThat(outcome).isInstanceOf(RunOutcome.Failed.class);
    assertThat(outcome.exitCode()).isEqualTo(4);
    assertThat(((RunOutcome.Failed) outcome).rollbackIncomplete()).isTrue();
    assertThat(fx.trace).containsExactly("exec:s1", "exec:s2", "comp:s2");
    assertThat(journal()).endsWith("s2:FAILED", "s2:ROLLBACK_FAILED");
  }

  @Test
  void should_skip_the_failing_step_and_compensate_the_rest_when_it_is_irreversible() {
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply"),
            fx.step("s2", "apply")
                .markIrreversible()
                .executeReturns(StepResult.failed(StepFailure.recoverable("boom", "retry"))));

    RunOutcome outcome = run(plan);

    assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(fx.trace).containsExactly("exec:s1", "exec:s2", "comp:s1");
    assertThat(journal()).endsWith("s2:FAILED", "s2:SKIPPED", "s1:ROLLED_BACK");
  }

  @Test
  void should_leave_earlier_steps_uncompensated_when_failure_is_fatal() {
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply"),
            fx.step("s2", "apply"),
            fx.step("s3", "apply")
                .executeReturns(
                    StepResult.failed(StepFailure.fatal("keystore corrupt", "restore manually"))));

    RunOutcome outcome = run(plan);

    assertThat(outcome)
        .isEqualTo(new RunOutcome.Failed("keystore corrupt", true, "restore manually", List.of()));
    assertThat(outcome.exitCode()).isEqualTo(4);
    assertThat(fx.trace).containsExactly("exec:s1", "exec:s2", "exec:s3");
    assertThat(fx.sink.of(Event.StepRolledBack.class)).isEmpty();
    assertThat(fx.sink.types()).endsWith("StepFailed", "RunFailed");
    assertThat(fx.store.run(RUN).orElseThrow().terminalState()).contains(TerminalState.FAILED);
  }

  @Test
  void should_return_exit_2_when_fatal_failure_happens_before_any_mutation() {
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "check")
                .nonMutating()
                .executeReturns(StepResult.failed(StepFailure.fatal("unsupported", "upgrade"))));

    RunOutcome outcome = run(plan);

    assertThat(outcome)
        .isEqualTo(new RunOutcome.Failed("unsupported", false, "upgrade", List.of()));
    assertThat(outcome.exitCode()).isEqualTo(2);
  }

  @Test
  void should_skip_irreversible_steps_when_rolling_back() {
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply"),
            // irreversible for the test: models a DB migration without rollback SQL
            fx.step("s2", "apply").markIrreversible(),
            fx.step("s3", "apply"),
            fx.step("s4", "apply")
                .executeReturns(StepResult.failed(StepFailure.recoverable("boom", "retry"))));

    RunOutcome outcome = run(plan);

    assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(fx.trace)
        .containsExactly(
            "exec:s1", "exec:s2", "exec:s3", "exec:s4", "comp:s4", "comp:s3", "comp:s1");
    assertThat(fx.sink.of(Event.StepSkipped.class))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.stepId()).contains("s2");
              assertThat(e.reason()).isEqualTo("irreversible");
            });
    assertThat(journal())
        .endsWith("s4:ROLLED_BACK", "s3:ROLLED_BACK", "s2:SKIPPED", "s1:ROLLED_BACK");
  }

  @Test
  void should_not_compensate_read_only_steps_when_rolling_back() {
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply").nonMutating(),
            fx.step("s2", "apply"),
            fx.step("s3", "apply")
                .executeReturns(StepResult.failed(StepFailure.recoverable("boom", "retry"))));

    run(plan);

    assertThat(fx.trace).containsExactly("exec:s1", "exec:s2", "exec:s3", "comp:s3", "comp:s2");
  }

  @Test
  void should_treat_an_escaping_exception_as_recoverable_when_execute_throws() {
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply"),
            fx.step("s2", "apply")
                .onExecute(
                    (ctx, out) -> {
                      throw new IllegalStateException("boom");
                    }));

    RunOutcome outcome = run(plan);

    assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(((RunOutcome.RolledBack) outcome).cause())
        .contains("IllegalStateException")
        .contains("boom");
    assertThat(fx.sink.of(Event.StepFailed.class))
        .singleElement()
        .extracting(e -> e.failure().getClass())
        .isEqualTo(StepFailure.Recoverable.class);
    assertThat(fx.trace).containsExactly("exec:s1", "exec:s2", "comp:s2", "comp:s1");
  }

  @Test
  void should_treat_postcheck_failure_as_recoverable_when_execute_succeeded() {
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply"),
            fx.step("s2", "apply").postcheck(ctx -> CheckResult.fail("hash mismatch", "re-run")));

    RunOutcome outcome = run(plan);

    assertThat(outcome)
        .isEqualTo(new RunOutcome.RolledBack("apply", "postcheck failed: hash mismatch"));
    assertThat(fx.trace).containsExactly("exec:s1", "exec:s2", "comp:s2", "comp:s1");
    assertThat(journal()).contains("s2:FAILED", "s2:ROLLED_BACK").doesNotContain("s2:SUCCEEDED");
  }

  @Test
  void should_treat_compensation_exception_as_rollback_failure_when_compensate_throws() {
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply")
                .onCompensate(
                    (ctx, out) -> {
                      throw new IllegalStateException("cannot restore");
                    }),
            fx.step("s2", "apply")
                .executeReturns(StepResult.failed(StepFailure.recoverable("boom", "retry"))));

    RunOutcome outcome = run(plan);

    assertThat(outcome.exitCode()).isEqualTo(4);
    assertThat(fx.sink.of(Event.StepRollbackFailed.class))
        .singleElement()
        .extracting(Event.StepRollbackFailed::cause)
        .asString()
        .contains("cannot restore");
  }

  @Test
  void should_redact_every_emitted_event_when_a_step_leaks_a_registered_secret() {
    String secret = "s3cr3t-pw-91";
    Redactor redactor = new Redactor();
    redactor.register(secret);
    Runner runner = new Runner(fx.store, fx.bus, fx.clock, fx.sleeps::add, redactor);
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply")
                .onExecute(
                    (ctx, out) -> {
                      out.emit(
                          new Event.Log(
                              EngineFixture.NOW,
                              ctx.runId(),
                              Optional.of("s1"),
                              "apply",
                              Event.Log.Level.INFO,
                              "connecting with password=" + secret));
                      return StepResult.failed(
                          new StepFailure.Recoverable(
                              "login failed for " + secret,
                              List.of(),
                              List.of(URI.create("http://admin:" + secret + "@jrs/rest_v2")),
                              List.of(),
                              "retry with " + secret));
                    }));

    RunOutcome outcome =
        runner.run(plan, fx.context(RUN), EngineFixture.fingerprint(), RunOptions.DEFAULT);

    assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(fx.sink.events()).isNotEmpty();
    assertThat(fx.sink.events())
        .as("no subscriber ever sees the raw secret")
        .allSatisfy(e -> assertThat(e.toString()).doesNotContain(secret));
    assertThat(fx.sink.of(Event.StepFailed.class))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.failure().cause()).isEqualTo("login failed for [redacted]");
              assertThat(e.failure().affectedUris())
                  .containsExactly(URI.create("http://jrs/rest_v2"));
            });
    assertThat(fx.sink.of(Event.Log.class))
        .anyMatch(l -> l.message().equals("connecting with password=[redacted]"));
  }

  @Test
  void should_cancel_during_retry_backoff_when_the_token_is_cancelled_while_sleeping() {
    Context ctx = fx.context(RUN);
    // The operator presses Ctrl-C while the runner is waiting to retry: the sleeper is where the
    // cancellation lands, and the wait must end there, not after the whole backoff and another
    // attempt.
    Sleeper cancelling = duration -> ctx.cancel().cancel("operator pressed Ctrl-C");
    Runner runner = new Runner(fx.store, fx.bus, fx.clock, cancelling);
    RetryPolicy policy = new RetryPolicy(5, Duration.ofSeconds(2), 1.0, Duration.ofSeconds(2), 0);
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply"),
            fx.step("s2", "apply")
                .retry(policy)
                .executeReturns(StepResult.failed(StepFailure.retryable("503", "wait"))));

    RunOutcome outcome = runner.run(plan, ctx, EngineFixture.fingerprint(), RunOptions.DEFAULT);

    assertThat(outcome).isInstanceOf(RunOutcome.Cancelled.class);
    assertThat(outcome.exitCode()).isEqualTo(5);
    assertThat(fx.trace)
        .as("one attempt, then the in-flight step and its predecessor are compensated")
        .containsExactly("exec:s1", "exec:s2", "comp:s2", "comp:s1");
    assertThat(fx.sink.of(Event.StepRetry.class)).hasSize(1);
    assertThat(fx.sink.types()).endsWith("RunCancelled");
  }

  /**
   * Review finding 2.2: a server that answers 429 or 503 with {@code Retry-After} says when to come
   * back; a retryable failure may carry that delay and the runner waits at least that long.
   */
  @Test
  void should_wait_at_least_the_retry_after_delay_when_a_retryable_failure_carries_one() {
    Context ctx = fx.context(RUN);
    List<Duration> slept = new ArrayList<>();
    Sleeper recording = slept::add;
    Runner runner = new Runner(fx.store, fx.bus, fx.clock, recording);
    RetryPolicy policy = new RetryPolicy(3, Duration.ZERO, 1.0, Duration.ZERO, 0);
    AtomicInteger calls = new AtomicInteger();
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply")
                .retry(policy)
                .onExecute(
                    (c, out) ->
                        calls.incrementAndGet() == 1
                            ? StepResult.failed(
                                new StepFailure.Retryable(
                                    "503",
                                    List.of(),
                                    List.of(),
                                    List.of(),
                                    "wait",
                                    Optional.of(Duration.ofSeconds(3))))
                            : StepResult.ok()));

    RunOutcome outcome = runner.run(plan, ctx, EngineFixture.fingerprint(), RunOptions.DEFAULT);

    assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
    Duration total = slept.stream().reduce(Duration.ZERO, Duration::plus);
    assertThat(total).isGreaterThanOrEqualTo(Duration.ofSeconds(3));
    assertThat(fx.sink.of(Event.StepRetry.class))
        .singleElement()
        .satisfies(r -> assertThat(r.delayMillis()).isGreaterThanOrEqualTo(3000));
  }

  @Test
  void should_end_the_run_as_failed_when_the_journal_cannot_be_written() {
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply"),
            fx.step("s2", "apply")
                .onExecute(
                    (ctx, out) -> {
                      // the disk fills, the file is deleted, the database is corrupted: the next
                      // transition cannot be written
                      fx.store.close();
                      return StepResult.ok();
                    }));

    RunOutcome outcome = run(plan);

    assertThat(outcome).isInstanceOf(RunOutcome.Failed.class);
    RunOutcome.Failed failed = (RunOutcome.Failed) outcome;
    assertThat(failed.exitCode()).isEqualTo(4);
    assertThat(failed.cause()).contains("journal");
    assertThat(failed.rollbackIncomplete()).as("s1 mutated and nothing was undone").isTrue();
    assertThat(failed.nextAction()).contains("runs recover");
    assertThat(fx.sink.types()).endsWith("RunFailed");
    assertThat(fx.sink.of(Event.RunFailed.class))
        .singleElement()
        .satisfies(e -> assertThat(e.cause()).contains("journal"));
  }

  @Test
  void should_succeed_with_exit_0_when_every_step_passes() {
    Plan plan =
        EngineFixture.plan("p1", fx.step("s1", "apply"), fx.step("s2", "verify").nonMutating());

    RunOutcome outcome = run(plan);

    assertThat(outcome).isEqualTo(new RunOutcome.Succeeded());
    assertThat(fx.sink.types())
        .containsExactly(
            "PlanCreated",
            "StepPending",
            "StepRunning",
            "StepSucceeded",
            "StepPending",
            "StepRunning",
            "StepSucceeded",
            "RunSucceeded");
    assertThat(fx.store.run(RUN).orElseThrow().exitCode()).contains(0);
    assertThat(fx.store.pendingRuns()).isEmpty();
  }

  @Test
  void should_propagate_lock_held_when_another_run_holds_the_lock() {
    Plan plan = EngineFixture.plan("p1", fx.step("s1", "apply"));
    try (RunLock unusedHolder = new RunLock(fx.home, "r-other", EngineFixture.NOW)) {
      assertThatThrownBy(() -> run(plan))
          .isInstanceOf(LockHeldException.class)
          .satisfies(e -> assertThat(((LockHeldException) e).holderRunId()).isEqualTo("r-other"));
    }
    assertThat(fx.trace).isEmpty();
    assertThat(fx.store.runs(10)).isEmpty();
  }

  @Test
  void should_release_the_lock_when_the_run_ends() {
    run(EngineFixture.plan("p1", fx.step("s1", "apply")));
    try (RunLock lock = new RunLock(fx.home, "r-next", EngineFixture.NOW)) {
      assertThat(lock.runId()).isEqualTo("r-next");
    }
  }

  /** Verifies that, at the moment each step event is emitted, the journal already holds it. */
  private static final class JournalCheckingSink implements EventSink {
    private final StateStore store;
    final List<String> violations = new ArrayList<>();

    JournalCheckingSink(StateStore store) {
      this.store = store;
    }

    @Override
    public void emit(Event event) {
      Optional<String> expected =
          switch (event) {
            case Event.StepPending e -> Optional.of("PENDING");
            case Event.StepRunning e -> Optional.of("RUNNING");
            case Event.StepRetry e -> Optional.of("RUNNING");
            case Event.StepSucceeded e -> Optional.of("SUCCEEDED");
            case Event.StepFailed e -> Optional.of("FAILED");
            case Event.StepSkipped e -> Optional.of("SKIPPED");
            case Event.StepRolledBack e -> Optional.of("ROLLED_BACK");
            case Event.StepRollbackFailed e -> Optional.of("ROLLBACK_FAILED");
            case Event.PlanCreated e -> Optional.empty();
            case Event.Log e -> Optional.empty();
            case Event.RunSucceeded e -> Optional.empty();
            case Event.RunFailed e -> Optional.empty();
            case Event.RunCancelled e -> Optional.empty();
            case Event.RunRolledBack e -> Optional.empty();
          };
      if (expected.isEmpty()) {
        return;
      }
      List<Transition> journal = store.transitions(event.runId());
      if (journal.isEmpty()) {
        violations.add(event.type() + " emitted before any transition");
        return;
      }
      Transition last = journal.get(journal.size() - 1);
      if (!last.stepId().equals(event.stepId().orElse(""))
          || !last.toState().equals(expected.get())) {
        violations.add(
            event.type()
                + " for "
                + event.stepId().orElse("?")
                + " emitted but last journal row is "
                + last.stepId()
                + ":"
                + last.toState());
      }
    }
  }
}
