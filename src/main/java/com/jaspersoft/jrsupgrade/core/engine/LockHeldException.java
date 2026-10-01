package com.jaspersoft.jrsupgrade.core.engine;

/**
 * Raised when {@code runs.lock} is held by another run (spec §5.5); the CLI maps it to exit code 9.
 * Invariant: the holder fields are never null; {@code "unknown"} when the lock file could not be
 * read.
 */
public final class LockHeldException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String holderRunId;
  private final String holderPid;

  public LockHeldException(String holderRunId, String holderPid) {
    super(
        "run lock is held by run "
            + (holderRunId == null ? "unknown" : holderRunId)
            + " (pid "
            + (holderPid == null ? "unknown" : holderPid)
            + ")");
    this.holderRunId = holderRunId == null ? "unknown" : holderRunId;
    this.holderPid = holderPid == null ? "unknown" : holderPid;
  }

  public String holderRunId() {
    return holderRunId;
  }

  public String holderPid() {
    return holderPid;
  }
}
