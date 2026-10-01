package com.jaspersoft.jrsupgrade.core.engine;

/**
 * Operator choices that change how the Runner reacts to failure (spec §6.3). {@code rollbackAll}
 * compensates every succeeded step of the plan instead of stopping at the failing step's phase
 * boundary ({@code --rollback-all}).
 */
public record RunOptions(boolean rollbackAll) {

  public static final RunOptions DEFAULT = new RunOptions(false);

  public static RunOptions withRollbackAll() {
    return new RunOptions(true);
  }
}
