package com.jaspersoft.jrsupgrade.core.snapshot;

import java.io.IOException;
import java.util.List;

/**
 * Thrown when a snapshot's payload no longer matches its manifest. Invariant: {@link #mismatches()}
 * is non-empty and names every failing entry with the reason (missing, size or hash mismatch), so
 * the operator sees the complete damage rather than the first problem.
 */
public final class SnapshotCorruptException extends IOException {

  private static final long serialVersionUID = 1L;

  private final List<String> mismatches;

  public SnapshotCorruptException(String snapshotId, List<String> mismatches) {
    super("snapshot " + snapshotId + " is corrupt: " + String.join("; ", mismatches));
    if (mismatches.isEmpty()) {
      throw new IllegalArgumentException("at least one mismatch is required");
    }
    this.mismatches = List.copyOf(mismatches);
  }

  public List<String> mismatches() {
    return mismatches;
  }
}
