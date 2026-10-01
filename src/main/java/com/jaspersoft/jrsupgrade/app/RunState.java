package com.jaspersoft.jrsupgrade.app;

/**
 * Whether this process has started a mutating run (review finding 4.3). An exception nobody mapped
 * is exit 2 ("nothing mutated") until a run starts and exit 4 ("rollback incomplete") afterwards,
 * because only then could something be half done. One flag per process; tests reset it.
 */
final class RunState {

  private static volatile boolean started;

  private RunState() {}

  static void markStarted() {
    started = true;
  }

  static boolean started() {
    return started;
  }

  static void reset() {
    started = false;
  }
}
