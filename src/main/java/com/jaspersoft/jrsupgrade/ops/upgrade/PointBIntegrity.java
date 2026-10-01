package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.platform.FileOps;
import com.jaspersoft.jrsupgrade.core.snapshot.Snapshot;
import com.jaspersoft.jrsupgrade.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsupgrade.core.state.SnapshotRecord;
import com.jaspersoft.jrsupgrade.core.state.StateStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Whether an upgrade run's rollback point B is still whole (spec §10.1, §10.4). Rollback restores
 * four things together, and they only mean anything together: the webapp archive, the buildomatic
 * archive, the configuration snapshot and the keystore snapshot. Restoring the old webapp while
 * leaving the new {@code default_master.properties} in place points yesterday's application at
 * today's database, which is worse than not rolling back at all, so a partial point B is refused
 * rather than half-applied.
 *
 * <p>Invariants: what the run was supposed to keep is read from the {@code snapshots} rows it wrote
 * at backup time, so a step that legitimately had nothing to save (no keystore on the server, no
 * configuration files found) is not mistaken for one whose snapshot was pruned; every archive is
 * checked against the SHA-256 recorded beside it and every snapshot against the manifest hash in
 * its row, so silent corruption counts as missing; nothing here mutates or repairs anything.
 */
final class PointBIntegrity {

  /** What is wrong with a run's point B; empty when it can be rolled back to. */
  record Report(List<String> problems) {
    Report {
      problems = List.copyOf(problems);
    }

    boolean whole() {
      return problems.isEmpty();
    }
  }

  private PointBIntegrity() {}

  static Report check(
      StateStore store, SnapshotStore snapshots, FileOps files, SnapshotSet set, String runId) {
    List<String> problems = new ArrayList<>();
    archive(files, set.webappArchive(), "webapp", true, problems);
    archive(files, set.buildomaticArchive(), "buildomatic", false, problems);
    for (SnapshotRecord row : store.snapshots(runId)) {
      if (!BackupSteps.REFERENCED_BY.equals(row.referencedBy().orElse(""))) {
        continue;
      }
      if (row.stepId().equals(BackupSteps.REFERENCED_BY)) {
        manifest(files, set, row, problems);
      } else {
        snapshot(snapshots, files, row, problems);
      }
    }
    return new Report(problems);
  }

  /**
   * The run-level {@code upgrade.json} that names what point B holds. It is not a {@link
   * SnapshotStore} entry, so it is checked directly against the hash recorded in its row.
   */
  private static void manifest(
      FileOps files, SnapshotSet set, SnapshotRecord row, List<String> problems) {
    if (!Files.isRegularFile(set.manifest())) {
      problems.add("the upgrade manifest " + set.manifest() + " is gone");
      return;
    }
    try {
      String actual = files.sha256(set.manifest());
      if (!actual.equals(row.manifestSha256())) {
        problems.add(
            set.manifest()
                + " hashes to "
                + actual
                + ", not the "
                + row.manifestSha256()
                + " the run recorded");
      }
    } catch (IOException e) {
      problems.add("cannot read " + set.manifest() + ": " + e.getMessage());
    }
  }

  /**
   * Checks one archive. {@code required} archives must exist; an optional one is only checked when
   * something says it was written, which is the {@code .sha256} beside it.
   */
  private static void archive(
      FileOps files, Path archive, String what, boolean required, List<String> problems) {
    boolean present = Files.isRegularFile(archive);
    boolean recorded;
    try {
      recorded = PointB.readSha(archive).isPresent();
    } catch (IOException e) {
      problems.add(what + ": cannot read the checksum beside " + archive + ": " + e.getMessage());
      return;
    }
    if (!present) {
      if (required) {
        problems.add(what + " archive " + archive + " is missing; the run did not reach point B");
      } else if (recorded) {
        problems.add(
            what + " archive " + archive + " was written by this run but is no longer there");
      }
      return;
    }
    try {
      PointB.verifyArchive(archive, files.sha256(archive));
    } catch (IOException e) {
      problems.add(what + ": " + e.getMessage());
    }
  }

  /** Checks one recorded snapshot: still on disk, still the manifest the run wrote, still whole. */
  private static void snapshot(
      SnapshotStore snapshots, FileOps files, SnapshotRecord row, List<String> problems) {
    Optional<Snapshot> found;
    try {
      found = snapshots.find(row.runId(), row.stepId());
    } catch (IOException e) {
      problems.add(row.stepId() + ": cannot read " + row.path() + ": " + e.getMessage());
      return;
    }
    if (found.isEmpty()) {
      problems.add(
          row.stepId()
              + " snapshot recorded at "
              + row.path()
              + " is gone; retention pruning or a manual clean-up removed it");
      return;
    }
    try {
      String manifest = files.sha256(found.get().manifestFile());
      if (!manifest.equals(row.manifestSha256())) {
        problems.add(
            row.stepId() + " snapshot manifest is " + manifest + ", not " + row.manifestSha256());
        return;
      }
      snapshots.verify(found.get());
    } catch (IOException | RuntimeException e) {
      problems.add(row.stepId() + ": " + e.getMessage());
    }
  }
}
