package com.jaspersoft.jrsupgrade.core.snapshot;

import static java.util.Objects.requireNonNull;

import com.jaspersoft.jrsupgrade.core.platform.FileOps;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Content of {@code manifest.json} in a snapshot directory (spec §5.6). Invariants: every entry
 * path is relative to {@link #baseDir()} and uses {@code /} as separator regardless of the OS that
 * wrote it; {@code sha256} and {@code size} describe both the original file at capture time and the
 * payload copy, which were verified equal; {@code permissions} is the platform-specific set
 * captured from the original so a restore can re-apply it; the entry list is immutable.
 */
public record SnapshotManifest(
    String runId, String stepId, Instant createdAt, Path baseDir, List<Entry> entries) {

  public SnapshotManifest {
    requireNonNull(runId, "runId");
    requireNonNull(stepId, "stepId");
    requireNonNull(createdAt, "createdAt");
    requireNonNull(baseDir, "baseDir");
    entries = List.copyOf(requireNonNull(entries, "entries"));
  }

  /** Sum of every entry's size in bytes. */
  public long totalSize() {
    return entries.stream().mapToLong(Entry::size).sum();
  }

  /** One captured file. */
  public record Entry(String path, String sha256, long size, FileOps.Permissions permissions) {
    public Entry {
      requireNonNull(path, "path");
      requireNonNull(sha256, "sha256");
      requireNonNull(permissions, "permissions");
    }
  }
}
