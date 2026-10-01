package com.jaspersoft.jrsupgrade.core.snapshot;

import static java.util.Objects.requireNonNull;

import java.nio.file.Path;

/**
 * A snapshot on disk: {@code snapshots/<runId>/<stepId>/} holding {@code manifest.json} and the
 * {@code payload/} tree (spec §5.6). Invariants: {@code runId} and {@code stepId} equal the
 * manifest's; {@link #dir()} is absolute; the snapshot is complete, because the store writes the
 * manifest last and never lists a directory without one.
 */
public record Snapshot(String runId, String stepId, Path dir, SnapshotManifest manifest) {

  static final String MANIFEST_FILE = "manifest.json";
  static final String PAYLOAD_DIR = "payload";

  public Snapshot {
    requireNonNull(runId, "runId");
    requireNonNull(stepId, "stepId");
    requireNonNull(dir, "dir");
    requireNonNull(manifest, "manifest");
    if (!runId.equals(manifest.runId()) || !stepId.equals(manifest.stepId())) {
      throw new IllegalArgumentException(
          "manifest belongs to " + manifest.runId() + "/" + manifest.stepId());
    }
  }

  public Path manifestFile() {
    return dir.resolve(MANIFEST_FILE);
  }

  public Path payloadDir() {
    return dir.resolve(PAYLOAD_DIR);
  }

  /** Payload copy of the given manifest entry. */
  public Path payloadFile(SnapshotManifest.Entry entry) {
    return payloadDir().resolve(entry.path());
  }
}
