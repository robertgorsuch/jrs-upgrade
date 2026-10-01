package com.jaspersoft.jrsupgrade.ops.retention;

import com.jaspersoft.jrsupgrade.core.engine.RunRecord;
import com.jaspersoft.jrsupgrade.core.engine.TerminalState;
import com.jaspersoft.jrsupgrade.core.state.Customization;
import com.jaspersoft.jrsupgrade.core.state.HotfixInstalled;
import com.jaspersoft.jrsupgrade.core.state.StateStore;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The runs whose snapshots retention pruning must never remove (spec §5.6, §10 step 15), computed
 * read-only from the state store: the run that installed every hotfix currently {@code INSTALLED},
 * the run that snapshotted every registered customization, the most recent {@code SUCCEEDED} {@code
 * upgrade} run (its {@code referenced_by = 'upgrade'} set; older upgrades become prunable), and
 * every run still pending recovery, whose snapshots {@code runs recover --rollback} needs.
 * Invariants: the result maps each protected run id to one human-readable reason; a reference whose
 * {@code snapshot_ref} is {@code runId/stepId} protects the whole run, because pruning works per
 * run; nothing here mutates.
 */
public final class RetentionProtection {

  /** Protected run ids, each with the reason it is kept. */
  public record Protected(Map<String, String> reasons) {
    public Protected {
      reasons = Map.copyOf(Objects.requireNonNull(reasons, "reasons"));
    }

    public Set<String> runIds() {
      return reasons.keySet();
    }
  }

  private RetentionProtection() {}

  public static Protected compute(StateStore store) {
    Objects.requireNonNull(store, "store");
    Map<String, String> reasons = new LinkedHashMap<>();
    for (HotfixInstalled hotfix : store.installedHotfixes()) {
      String reason = "installed hotfix " + hotfix.id();
      reasons.putIfAbsent(hotfix.installedRunId(), reason);
      hotfix
          .snapshotRef()
          .flatMap(RetentionProtection::runIdOf)
          .ifPresent(r -> reasons.putIfAbsent(r, reason));
    }
    for (Customization customization : store.customizations()) {
      customization
          .snapshotRef()
          .flatMap(RetentionProtection::runIdOf)
          .ifPresent(
              r -> reasons.putIfAbsent(r, "registered customization " + customization.path()));
    }
    latestSuccessfulUpgrade(store)
        .ifPresent(run -> reasons.putIfAbsent(run.runId(), "most recent successful upgrade"));
    for (RunRecord pending : store.pendingRuns()) {
      reasons.putIfAbsent(pending.runId(), "run pending recovery");
    }
    return new Protected(reasons);
  }

  /** The most recent {@code upgrade} run that ended {@code SUCCEEDED}, if any. */
  public static Optional<RunRecord> latestSuccessfulUpgrade(StateStore store) {
    for (RunRecord run : store.runs(Integer.MAX_VALUE)) {
      if (UpgradeOperations.UPGRADE_OPERATION.equals(run.operation())
          && run.terminalState().filter(s -> s == TerminalState.SUCCEEDED).isPresent()) {
        return Optional.of(run);
      }
    }
    return Optional.empty();
  }

  /** {@code runId} of a {@code runId/stepId} snapshot reference; empty for any other shape. */
  static Optional<String> runIdOf(String snapshotRef) {
    int slash = snapshotRef.indexOf('/');
    return slash > 0 ? Optional.of(snapshotRef.substring(0, slash)) : Optional.empty();
  }
}
