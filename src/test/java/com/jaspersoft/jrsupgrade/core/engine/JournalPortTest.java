package com.jaspersoft.jrsupgrade.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.event.Event;
import com.jaspersoft.jrsupgrade.core.event.RecordingSink;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Roadmap item 16: the engine writes through {@link Journal}, so a run can be driven against a
 * journal that is not a database at all. That is what makes review finding 1.10, a journal write
 * that fails mid-run, testable without SQLite anywhere near it.
 */
class JournalPortTest {

  @Test
  void should_run_a_plan_against_a_journal_that_is_not_a_database(@TempDir Path home) {
    RecordingJournal journal = new RecordingJournal(0);

    RunOutcome outcome = run(journal, home).outcome();

    assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
    assertThat(journal.calls).first().isEqualTo("recordRunStart");
    assertThat(journal.calls).last().isEqualTo("recordRunEnd");
    assertThat(journal.calls).contains("appendTransition");
  }

  @Test
  void should_end_the_run_when_the_journal_fails_on_its_nth_write(@TempDir Path home) {
    RecordingJournal journal = new RecordingJournal(3);

    Result result = run(journal, home);

    assertThat(result.outcome()).isInstanceOf(RunOutcome.Failed.class);
    assertThat(result.sink().events())
        .filteredOn(e -> e instanceof Event.RunFailed)
        .extracting(e -> ((Event.RunFailed) e).nextAction())
        .anySatisfy(next -> assertThat(next).contains("runs recover"));
  }

  /**
   * The first write is the run's own row. When it fails nothing has been mutated and there is no
   * row for {@code runs recover} to find, so the outcome is a refusal (exit 2), not the
   * rollback-incomplete failure a mid-run journal loss gets (assessment item E5).
   */
  @Test
  void should_refuse_with_nothing_changed_when_the_journal_fails_on_run_start(@TempDir Path home) {
    RecordingJournal journal = new RecordingJournal(1);

    Result result = run(journal, home);

    assertThat(result.outcome())
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(RunOutcome.Failed.class))
        .satisfies(
            f -> {
              assertThat(f.rollbackIncomplete()).isFalse();
              assertThat(f.exitCode()).isEqualTo(2);
              assertThat(f.nextAction()).contains("nothing was changed");
            });
    assertThat(journal.calls).containsExactly("recordRunStart");
  }

  private record Result(RunOutcome outcome, RecordingSink sink) {}

  private static Result run(Journal journal, Path home) {
    RecordingSink sink = new RecordingSink();
    Runner runner = new Runner(journal, sink, Clock.systemUTC(), Sleeper.system());
    List<String> trace = new ArrayList<>();
    Plan plan = EngineFixture.plan("p-journal", new FakeStep("noop", "apply", trace));
    Context ctx =
        new Context(
            "r-journal",
            new JrsUpgradeHome(home),
            new FakePlatform(home),
            new CancellationToken(),
            Map.of());
    return new Result(runner.run(plan, ctx, EngineFixture.fingerprint(), RunOptions.DEFAULT), sink);
  }

  /** A journal that remembers what it was asked to do and fails on the Nth write. */
  private static final class RecordingJournal implements Journal {
    private final int failOnWrite;
    private final List<String> calls = new ArrayList<>();
    private int writes;

    RecordingJournal(int failOnWrite) {
      this.failOnWrite = failOnWrite;
    }

    private void record(String name) {
      calls.add(name);
      if (++writes == failOnWrite) {
        throw new JournalException("simulated journal failure on write " + writes);
      }
    }

    @Override
    public void recordRunStart(
        String runId, String operation, Optional<String> planId, Instant startedAt) {
      record("recordRunStart");
    }

    @Override
    public void recordRunEnd(String runId, Instant endedAt, TerminalState state, int exitCode) {
      record("recordRunEnd");
    }

    @Override
    public Transition appendTransition(
        String runId,
        String stepId,
        String phase,
        Optional<String> fromState,
        String toState,
        Optional<String> detail) {
      record("appendTransition");
      return new Transition(
          writes, Instant.EPOCH, runId, stepId, phase, fromState, toState, detail);
    }

    @Override
    public Optional<RunRecord> run(String runId) {
      return Optional.empty();
    }

    @Override
    public List<RunRecord> pendingRuns() {
      return List.of();
    }

    @Override
    public List<Transition> transitions(String runId) {
      return List.of();
    }
  }
}
