package com.jaspersoft.jrsupgrade.core.engine;

import java.time.Instant;
import java.util.Optional;

/**
 * A row of {@code runs} (spec §5.4). Invariant: {@code terminalState} and {@code exitCode} are set
 * together by {@code recordRunEnd}; a run with neither is pending recovery (spec §5.5).
 */
public record RunRecord(
    String runId,
    String operation,
    Optional<String> planId,
    Instant startedAt,
    Optional<Instant> endedAt,
    Optional<TerminalState> terminalState,
    Optional<Integer> exitCode) {

  public boolean pending() {
    return terminalState.isEmpty();
  }
}
