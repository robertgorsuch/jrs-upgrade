package com.jaspersoft.jrsupgrade.core.engine;

import java.time.Instant;
import java.util.Optional;

/**
 * A row of the append-only {@code step_transitions} journal (spec §5.4). Invariant: {@code seq}
 * strictly increases in write order within a store, so ordering by it reproduces the run history.
 */
public record Transition(
    long seq,
    Instant ts,
    String runId,
    String stepId,
    String phase,
    Optional<String> fromState,
    String toState,
    Optional<String> detail) {}
