package com.jaspersoft.jrsupgrade.core.engine;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The run journal as the engine needs it: the six calls {@link Runner} and {@link Recovery} make,
 * and nothing else. Invariant: this is the engine's port, not the store's API. The engine names
 * what it needs and {@code core.state.StateStore} implements it, so the engine compiles without
 * knowing that the journal is SQLite, and a test can hand the Runner a journal that throws on the
 * third write without a database anywhere near it (review finding 1.10 and roadmap item 16). Every
 * write is durable before it returns and an append is never reordered; a failure is a {@link
 * RuntimeException}, which the Runner turns into a failed run rather than letting it escape.
 */
public interface Journal {

  /** Records that a run began. */
  void recordRunStart(String runId, String operation, Optional<String> planId, Instant startedAt);

  /** Records how a run ended; the row is terminal from here on. */
  void recordRunEnd(String runId, Instant endedAt, TerminalState state, int exitCode);

  /** Appends one step transition in write order and returns the row it wrote. */
  Transition appendTransition(
      String runId,
      String stepId,
      String phase,
      Optional<String> fromState,
      String toState,
      Optional<String> detail);

  /** One run, or empty when the id is unknown. */
  Optional<RunRecord> run(String runId);

  /** Runs without a terminal state, oldest first (spec §5.5). */
  List<RunRecord> pendingRuns();

  /** The journal of one run in write order. */
  List<Transition> transitions(String runId);
}
