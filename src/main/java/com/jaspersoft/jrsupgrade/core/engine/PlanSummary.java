package com.jaspersoft.jrsupgrade.core.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * What the operator sees before confirming (spec §6.2): what changes, whether the server goes down,
 * where the backups are, how to undo, and any plain-language warnings such as "database rollback is
 * the operator's responsibility". Invariant: lists are immutable copies.
 */
public record PlanSummary(
    String operation,
    String target,
    List<Path> filesTouched,
    List<String> resourcesTouched,
    boolean serviceRestart,
    List<Path> backupLocations,
    Map<String, String> rollbackPointsByPhase,
    String strategy,
    List<String> warnings) {

  public PlanSummary {
    filesTouched = List.copyOf(filesTouched);
    resourcesTouched = List.copyOf(resourcesTouched);
    backupLocations = List.copyOf(backupLocations);
    rollbackPointsByPhase = Map.copyOf(rollbackPointsByPhase);
    warnings = List.copyOf(warnings);
  }
}
