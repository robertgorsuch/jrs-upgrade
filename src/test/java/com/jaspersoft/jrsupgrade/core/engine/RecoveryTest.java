package com.jaspersoft.jrsupgrade.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.event.Event;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryTest {

  private static final String RUN = "r-20260908-101500-dead";

  @TempDir Path tmp;
  private EngineFixture fx;
  private Recovery recovery;

  @BeforeEach
  void setUp() {
    fx = new EngineFixture(tmp);
    recovery = new Recovery(fx.store, fx.runner);
  }

  @AfterEach
  void tearDown() {
    fx.close();
  }

  /** Simulates a run that crashed with s1 done and s2 in flight. */
  private void journalInterruptedAt(String... stepIds) {
    fx.store.recordRunStart(RUN, "hotfix apply", Optional.of("p1"), EngineFixture.NOW);
    for (int i = 0; i < stepIds.length; i++) {
      String id = stepIds[i];
      fx.store.appendTransition(RUN, id, "apply", Optional.empty(), "PENDING", Optional.empty());
      fx.store.appendTransition(
          RUN, id, "apply", Optional.of("PENDING"), "RUNNING", Optional.empty());
      if (i < stepIds.length - 1) {
        fx.store.appendTransition(
            RUN, id, "apply", Optional.of("RUNNING"), "SUCCEEDED", Optional.empty());
      }
    }
  }

  @Test
  void should_list_pending_runs_when_a_run_has_no_terminal_state() {
    fx.store.recordRunStart(RUN, "hotfix apply", Optional.empty(), EngineFixture.NOW);
    fx.store.recordRunStart("r-done", "export", Optional.empty(), EngineFixture.NOW);
    fx.store.recordRunEnd("r-done", EngineFixture.NOW, TerminalState.SUCCEEDED, 0);

    assertThat(Recovery.pendingRuns(fx.store)).extracting(RunRecord::runId).containsExactly(RUN);
    assertThat(recovery.pendingRuns()).extracting(RunRecord::runId).containsExactly(RUN);
  }

  @Test
  void should_rebuild_last_step_states_when_reading_the_journal() {
    journalInterruptedAt("s1", "s2");

    Map<String, StepState> journal = recovery.journal(RUN);

    assertThat(journal)
        .containsExactly(Map.entry("s1", StepState.SUCCEEDED), Map.entry("s2", StepState.RUNNING));
    assertThat(recovery.journal("unknown")).isEmpty();
  }

  @Test
  void should_resume_from_the_interrupted_step_when_resuming() {
    journalInterruptedAt("s1", "s2");
    Plan plan =
        EngineFixture.plan(
            "p1", fx.step("s1", "apply"), fx.step("s2", "apply"), fx.step("s3", "apply"));

    RunOutcome outcome = recovery.resume(plan, RUN, fx.context("r-cli"), RunOptions.DEFAULT);

    assertThat(outcome).isEqualTo(new RunOutcome.Succeeded());
    assertThat(fx.trace).containsExactly("exec:s2", "exec:s3");
    assertThat(fx.store.run(RUN).orElseThrow().terminalState()).contains(TerminalState.SUCCEEDED);
    assertThat(fx.store.pendingRuns()).isEmpty();
    List<String> tail =
        fx.store.transitions(RUN).stream()
            .skip(5)
            .map(t -> t.stepId() + ":" + t.fromState().orElse("-") + ">" + t.toState())
            .toList();
    assertThat(tail)
        .containsExactly(
            "s2:RUNNING>PENDING",
            "s2:PENDING>RUNNING",
            "s2:RUNNING>SUCCEEDED",
            "s3:->PENDING",
            "s3:PENDING>RUNNING",
            "s3:RUNNING>SUCCEEDED");
    assertThat(fx.sink.events()).allSatisfy(e -> assertThat(e.runId()).isEqualTo(RUN));
  }

  @Test
  void should_resume_after_the_last_succeeded_step_when_the_run_stopped_between_steps() {
    fx.store.recordRunStart(RUN, "hotfix apply", Optional.empty(), EngineFixture.NOW);
    fx.store.appendTransition(RUN, "s1", "apply", Optional.empty(), "PENDING", Optional.empty());
    fx.store.appendTransition(
        RUN, "s1", "apply", Optional.of("PENDING"), "RUNNING", Optional.empty());
    fx.store.appendTransition(
        RUN, "s1", "apply", Optional.of("RUNNING"), "SUCCEEDED", Optional.empty());
    Plan plan = EngineFixture.plan("p1", fx.step("s1", "apply"), fx.step("s2", "apply"));

    RunOutcome outcome = recovery.resume(plan, RUN, fx.context(RUN), RunOptions.DEFAULT);

    assertThat(outcome).isEqualTo(new RunOutcome.Succeeded());
    assertThat(fx.trace).containsExactly("exec:s2");
  }

  @Test
  void should_roll_back_journalled_steps_too_when_a_resumed_run_fails_later() {
    journalInterruptedAt("s1", "s2");
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply"),
            fx.step("s2", "apply"),
            fx.step("s3", "apply")
                .executeReturns(StepResult.failed(StepFailure.recoverable("boom", "retry"))));

    RunOutcome outcome = recovery.resume(plan, RUN, fx.context(RUN), RunOptions.DEFAULT);

    assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(fx.trace).containsExactly("exec:s2", "exec:s3", "comp:s3", "comp:s2", "comp:s1");
  }

  @Test
  void should_offer_only_rollback_when_the_interrupted_step_precheck_fails() {
    journalInterruptedAt("s1", "s2");
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply"),
            fx.step("s2", "apply").precheck(ctx -> CheckResult.fail("jar locked", "stop tomcat")));

    RunOutcome outcome = recovery.resume(plan, RUN, fx.context(RUN), RunOptions.DEFAULT);

    assertThat(outcome).isInstanceOf(RunOutcome.PrecheckFailed.class);
    RunOutcome.PrecheckFailed pf = (RunOutcome.PrecheckFailed) outcome;
    assertThat(pf.stepId()).isEqualTo("s2");
    assertThat(pf.remediation()).contains("--rollback").contains("stop tomcat");
    assertThat(pf.exitCode()).isEqualTo(2);
    assertThat(fx.trace).isEmpty();
    assertThat(fx.store.run(RUN).orElseThrow().pending()).isTrue();
    assertThat(fx.store.transitions(RUN)).hasSize(5);
  }

  @Test
  void should_refuse_resume_when_the_run_already_began_rolling_back() {
    journalInterruptedAt("s1", "s2");
    fx.store.appendTransition(
        RUN, "s2", "apply", Optional.of("RUNNING"), "FAILED", Optional.empty());
    fx.store.appendTransition(
        RUN, "s1", "apply", Optional.of("SUCCEEDED"), "ROLLED_BACK", Optional.empty());
    Plan plan = EngineFixture.plan("p1", fx.step("s1", "apply"), fx.step("s2", "apply"));

    RunOutcome outcome = recovery.resume(plan, RUN, fx.context(RUN), RunOptions.DEFAULT);

    assertThat(outcome).isInstanceOf(RunOutcome.PrecheckFailed.class);
    assertThat(((RunOutcome.PrecheckFailed) outcome).remediation()).contains("--rollback");
    assertThat(fx.trace).isEmpty();
  }

  @Test
  void should_compensate_succeeded_steps_in_reverse_when_rolling_back() {
    journalInterruptedAt("s1", "s2", "s3");
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply"),
            fx.step("s2", "apply"),
            fx.step("s3", "apply"),
            fx.step("s4", "apply"));

    RunOutcome outcome = recovery.rollback(plan, RUN, fx.context(RUN));

    assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(outcome.exitCode()).isEqualTo(3);
    assertThat(fx.trace).containsExactly("comp:s3", "comp:s2", "comp:s1");
    assertThat(fx.sink.of(Event.StepRolledBack.class))
        .extracting(e -> e.stepId().orElseThrow())
        .containsExactly("s3", "s2", "s1");
    assertThat(fx.sink.types()).endsWith("RunRolledBack");
    assertThat(fx.store.run(RUN).orElseThrow().terminalState()).contains(TerminalState.ROLLED_BACK);
    assertThat(fx.store.pendingRuns()).isEmpty();
  }

  @Test
  void should_compensate_the_journaled_failed_step_first_when_rolling_back() {
    journalInterruptedAt("s1", "s2");
    // the process died after journaling the failure but before compensating it
    fx.store.appendTransition(
        RUN, "s2", "apply", Optional.of("RUNNING"), "FAILED", Optional.of("half swapped"));
    Plan plan = EngineFixture.plan("p1", fx.step("s1", "apply"), fx.step("s2", "apply"));

    RunOutcome outcome = recovery.rollback(plan, RUN, fx.context(RUN));

    assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(fx.trace).containsExactly("comp:s2", "comp:s1");
    assertThat(fx.store.transitions(RUN).stream().map(t -> t.stepId() + ":" + t.toState()))
        .endsWith("s2:FAILED", "s2:ROLLED_BACK", "s1:ROLLED_BACK");
  }

  /**
   * The plan is rebuilt from its stored arguments; an installation that changed in between (a
   * customization registered under WEB-INF adds stop/start steps) yields step ids the journal never
   * saw, and resume or rollback would line up against the wrong steps (item E3).
   */
  @Test
  void should_refuse_recovery_when_the_rebuilt_plan_lacks_a_journaled_step() {
    journalInterruptedAt("s1", "s2");
    Plan drifted =
        EngineFixture.plan("p1", fx.step("s1", "apply"), fx.step("stop-service", "apply"));

    RunOutcome resumed = recovery.resume(drifted, RUN, fx.context(RUN), RunOptions.DEFAULT);
    RunOutcome rolledBack = recovery.rollback(drifted, RUN, fx.context(RUN));

    for (RunOutcome outcome : List.of(resumed, rolledBack)) {
      assertThat(outcome).isInstanceOf(RunOutcome.PrecheckFailed.class);
      assertThat(((RunOutcome.PrecheckFailed) outcome).message()).contains("s2");
      assertThat(outcome.exitCode()).isEqualTo(2);
    }
    assertThat(fx.trace).isEmpty();
    assertThat(fx.store.pendingRuns()).hasSize(1);
  }

  /**
   * A step whose compensation failed before the crash is compensated again, not skipped: skipping
   * it ended the run as ROLLED_BACK (exit 3) with its partial change standing (item E4).
   */
  @Test
  void should_retry_a_failed_compensation_when_rolling_back_a_pending_run() {
    journalInterruptedAt("s1", "s2");
    fx.store.appendTransition(
        RUN, "s2", "apply", Optional.of("RUNNING"), "FAILED", Optional.of("half swapped"));
    fx.store.appendTransition(
        RUN, "s2", "apply", Optional.of("FAILED"), "ROLLBACK_FAILED", Optional.of("disk full"));
    Plan plan = EngineFixture.plan("p1", fx.step("s1", "apply"), fx.step("s2", "apply"));

    RunOutcome outcome = recovery.rollback(plan, RUN, fx.context(RUN));

    assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(fx.trace).containsExactly("comp:s2", "comp:s1");
    assertThat(fx.store.transitions(RUN).stream().map(t -> t.stepId() + ":" + t.toState()))
        .endsWith("s2:ROLLBACK_FAILED", "s2:ROLLED_BACK", "s1:ROLLED_BACK");
  }

  @Test
  void should_skip_irreversible_steps_and_report_exit_4_when_a_rollback_compensation_fails() {
    journalInterruptedAt("s1", "s2", "s3", "s4");
    Plan plan =
        EngineFixture.plan(
            "p1",
            fx.step("s1", "apply"),
            // irreversible for the test only
            fx.step("s2", "apply").markIrreversible(),
            fx.step("s3", "apply")
                .compensateReturns(
                    StepResult.failed(StepFailure.recoverable("cannot restore", "manual"))),
            fx.step("s4", "apply"));

    RunOutcome outcome = recovery.rollback(plan, RUN, fx.context(RUN));

    assertThat(outcome).isInstanceOf(RunOutcome.Failed.class);
    assertThat(outcome.exitCode()).isEqualTo(4);
    assertThat(fx.trace).containsExactly("comp:s4", "comp:s3");
    assertThat(fx.store.run(RUN).orElseThrow().terminalState()).contains(TerminalState.FAILED);
  }

  @Test
  void should_throw_when_the_run_is_unknown_or_already_ended() {
    Plan plan = EngineFixture.plan("p1", fx.step("s1", "apply"));
    assertThatThrownBy(() -> recovery.resume(plan, "nope", fx.context("nope"), RunOptions.DEFAULT))
        .isInstanceOf(IllegalArgumentException.class);

    fx.store.recordRunStart(RUN, "hotfix apply", Optional.empty(), EngineFixture.NOW);
    fx.store.recordRunEnd(RUN, EngineFixture.NOW, TerminalState.SUCCEEDED, 0);
    assertThatThrownBy(() -> recovery.rollback(plan, RUN, fx.context(RUN)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SUCCEEDED");
  }
}
