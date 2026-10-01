package com.jaspersoft.jrsupgrade.ops.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.engine.RunLock;
import com.jaspersoft.jrsupgrade.core.engine.TerminalState;
import com.jaspersoft.jrsupgrade.core.snapshot.Snapshot;
import com.jaspersoft.jrsupgrade.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsupgrade.core.state.HotfixInstalled;
import com.jaspersoft.jrsupgrade.core.state.HotfixState;
import com.jaspersoft.jrsupgrade.core.state.SnapshotRecord;
import com.jaspersoft.jrsupgrade.core.state.StateStore;
import com.jaspersoft.jrsupgrade.ops.FakeServices;
import com.jaspersoft.jrsupgrade.ops.Services;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetentionPrunerTest {

  @TempDir Path tmp;

  private FakeServices fake;
  private Services services;
  private Instant now;

  @AfterEach
  void tearDown() {
    if (fake != null) {
      fake.close();
    }
  }

  private void services(int retentionDays, int maxSnapshots) throws IOException {
    fake = FakeServices.in(tmp.resolve("home"));
    fake.platform.realFiles = true;
    fake.yaml(
        """
        backups:
          retentionDays: %d
          maxSnapshots: %d
        """
            .formatted(retentionDays, maxSnapshots));
    services = fake.build();
    now = fake.clock.instant();
  }

  private RetentionPruner pruner() {
    return new RetentionPruner(
        services, new SnapshotStore(fake.home, fake.platform.files(), fake.clock), "tester");
  }

  /** A real snapshot of one small file, created {@code age} before now, with its state row. */
  private Snapshot snapshot(
      String runId, String stepId, Duration age, Optional<String> referencedBy) throws IOException {
    Path src = Files.createDirectories(tmp.resolve("src"));
    Path file = src.resolve(runId + "-" + stepId + ".txt");
    Files.writeString(file, "content of " + runId, StandardCharsets.UTF_8);
    SnapshotStore at =
        new SnapshotStore(
            fake.home, fake.platform.files(), Clock.fixed(now.minus(age), ZoneOffset.UTC));
    Snapshot s = at.create(runId, stepId, List.of(file), src);
    fake.stateStore()
        .recordSnapshot(
            new SnapshotRecord(
                runId + "/" + stepId,
                runId,
                stepId,
                s.dir(),
                fake.platform.files().sha256(s.manifestFile()),
                referencedBy));
    return s;
  }

  private static List<String> ids(RetentionPruner.Result result) {
    return result.removed().stream().map(RetentionPruner.Removed::id).toList();
  }

  @Test
  void should_remove_snapshots_older_than_retention_when_count_is_under_cap() throws Exception {
    services(30, 20);
    Snapshot old = snapshot("r-old", "snapshot", Duration.ofDays(40), Optional.empty());
    Snapshot fresh = snapshot("r-new", "snapshot", Duration.ofDays(1), Optional.empty());

    RetentionPruner.Result result = pruner().prune(false);

    assertThat(result.dryRun()).isFalse();
    assertThat(ids(result)).containsExactly("r-old/snapshot");
    assertThat(result.removed().get(0).path()).isEqualTo(old.dir());
    assertThat(result.kept()).isEqualTo(1);
    assertThat(result.protectedCount()).isZero();
    assertThat(old.dir()).doesNotExist();
    assertThat(fresh.manifestFile()).exists();
    StateStore store = fake.stateStore();
    assertThat(store.snapshot("r-old/snapshot")).isEmpty();
    assertThat(store.snapshot("r-new/snapshot")).isPresent();
    assertThat(store.auditRows(5))
        .anyMatch(
            a ->
                a.action().equals(RetentionPruner.AUDIT_ACTION)
                    && a.actor().equals("tester")
                    && a.detail().orElse("").contains("removed 1 snapshot(s)"));
  }

  @Test
  void should_remove_oldest_unprotected_when_count_exceeds_max_snapshots() throws Exception {
    services(0, 1);
    snapshot("r-a", "snapshot", Duration.ofDays(3), Optional.empty());
    snapshot("r-b", "snapshot", Duration.ofDays(2), Optional.empty());
    Snapshot c = snapshot("r-c", "snapshot", Duration.ofDays(1), Optional.empty());

    RetentionPruner.Result result = pruner().prune(false);

    assertThat(ids(result)).containsExactly("r-a/snapshot", "r-b/snapshot");
    assertThat(result.kept()).isEqualTo(1);
    assertThat(c.manifestFile()).exists();
    assertThat(fake.home.snapshots().resolve("r-a")).doesNotExist();
    assertThat(fake.stateStore().snapshots())
        .extracting(SnapshotRecord::id)
        .containsExactly("r-c/snapshot");
  }

  @Test
  void should_keep_referenced_snapshots_when_pruning_by_count() throws Exception {
    services(0, 1);
    Snapshot hotfixed = snapshot("r-hf", "snapshot", Duration.ofDays(5), Optional.of("HF-1"));
    fake.stateStore()
        .recordHotfixInstalled(
            new HotfixInstalled(
                "HF-1", "1", "t", "r-hf", Optional.of("r-hf/snapshot"), HotfixState.INSTALLED, now),
            List.of());
    snapshot("r-b", "snapshot", Duration.ofDays(2), Optional.empty());
    snapshot("r-c", "snapshot", Duration.ofDays(1), Optional.empty());

    RetentionPruner.Result result = pruner().prune(false);

    assertThat(ids(result)).containsExactly("r-b/snapshot", "r-c/snapshot");
    assertThat(result.kept()).isEqualTo(1);
    assertThat(result.protectedCount()).isEqualTo(1);
    assertThat(hotfixed.manifestFile()).exists();
    assertThat(fake.stateStore().snapshot("r-hf/snapshot")).isPresent();
  }

  @Test
  void should_change_nothing_when_dry_run() throws Exception {
    services(0, 1);
    Snapshot a = snapshot("r-a", "snapshot", Duration.ofDays(2), Optional.empty());
    Snapshot b = snapshot("r-b", "snapshot", Duration.ofDays(1), Optional.empty());

    RetentionPruner.Result result = pruner().prune(true);

    assertThat(result.dryRun()).isTrue();
    assertThat(ids(result)).containsExactly("r-a/snapshot");
    assertThat(result.kept()).isEqualTo(1);
    assertThat(a.manifestFile()).exists();
    assertThat(b.manifestFile()).exists();
    assertThat(fake.stateStore().snapshots()).hasSize(2);
    assertThat(fake.stateStore().auditRows(5))
        .noneMatch(row -> row.action().equals(RetentionPruner.AUDIT_ACTION));
  }

  @Test
  void should_keep_the_finished_runs_snapshots_when_pruning_after_success() throws Exception {
    services(0, 1);
    Snapshot own = snapshot("r-done", "snapshot", Duration.ofDays(3), Optional.empty());
    snapshot("r-b", "snapshot", Duration.ofDays(2), Optional.empty());
    snapshot("r-c", "snapshot", Duration.ofDays(1), Optional.empty());

    Optional<RetentionPruner.Result> result = pruner().afterSuccessfulRun("r-done");

    assertThat(result).isPresent();
    assertThat(ids(result.get())).containsExactly("r-b/snapshot", "r-c/snapshot");
    assertThat(result.get().protectedCount()).isEqualTo(1);
    assertThat(own.manifestFile()).exists();
    assertThat(fake.stateStore().snapshot("r-done/snapshot")).isPresent();
    assertThat(fake.stateStore().auditRows(5))
        .anyMatch(row -> row.action().equals(RetentionPruner.AUDIT_ACTION));
  }

  @Test
  void should_skip_without_failing_when_run_lock_is_held_during_automatic_prune() throws Exception {
    services(0, 1);
    Snapshot a = snapshot("r-a", "snapshot", Duration.ofDays(2), Optional.empty());
    snapshot("r-b", "snapshot", Duration.ofDays(1), Optional.empty());

    Optional<RetentionPruner.Result> result;
    try (RunLock held = new RunLock(fake.home, "r-other", now)) {
      result = pruner().afterSuccessfulRun("r-b");
    }

    assertThat(result).isEmpty();
    assertThat(a.manifestFile()).exists();
    assertThat(fake.stateStore().snapshots()).hasSize(2);
  }

  @Test
  void should_not_audit_when_automatic_prune_removes_nothing() throws Exception {
    services(30, 20);
    snapshot("r-a", "snapshot", Duration.ofDays(1), Optional.empty());

    Optional<RetentionPruner.Result> result = pruner().afterSuccessfulRun("r-a");

    assertThat(result).isPresent();
    assertThat(result.get().removed()).isEmpty();
    assertThat(fake.stateStore().auditRows(5))
        .noneMatch(row -> row.action().equals(RetentionPruner.AUDIT_ACTION));
  }

  /**
   * Review finding 1.19: pre-import snapshots are plain zips with no manifest, so the pruner never
   * saw them. They expire by age like any snapshot.
   */
  @Test
  void should_remove_expired_pre_import_snapshots_when_no_run_is_pending() throws Exception {
    services(30, 20);
    Path old = preImportZip("pre-import-old-abc123.zip", Duration.ofDays(40));
    Path fresh = preImportZip("pre-import-new-def456.zip", Duration.ofDays(1));

    RetentionPruner.Result result = pruner().prune(false);

    assertThat(ids(result)).containsExactly("pre-import/pre-import-old-abc123.zip");
    assertThat(result.removed().get(0).path()).isEqualTo(old);
    assertThat(old).doesNotExist();
    assertThat(fresh).exists();
    assertThat(result.kept()).isEqualTo(1);
  }

  /** A pending run's rollback may still need its pre-import snapshot, and the zip names no run. */
  @Test
  void should_keep_expired_pre_import_snapshots_while_a_run_is_pending_recovery() throws Exception {
    services(30, 20);
    Path old = preImportZip("pre-import-old-abc123.zip", Duration.ofDays(40));
    fake.stateStore()
        .recordRunStart("r-pending", "import", Optional.empty(), now.minus(Duration.ofHours(1)));

    RetentionPruner.Result result = pruner().prune(false);

    assertThat(ids(result)).isEmpty();
    assertThat(old).exists();
    assertThat(result.protectedCount()).isEqualTo(1);
  }

  /**
   * Upgrade sets (webapp archives, full export, manifest) live beside the run's step snapshots but
   * carry no snapshot manifest. They follow the run's protection: the most recent successful
   * upgrade keeps its set, an older unprotected run loses it once it has passed retention.
   */
  @Test
  void should_remove_the_upgrade_set_when_its_run_is_expired_and_unprotected() throws Exception {
    services(30, 20);
    Path oldSet = upgradeSet("r-up-old", Duration.ofDays(40));
    Path newSet = upgradeSet("r-up-new", Duration.ofDays(1));
    upgradeRun("r-up-old", Duration.ofDays(40));
    upgradeRun("r-up-new", Duration.ofDays(1));

    RetentionPruner.Result result = pruner().prune(false);

    assertThat(ids(result)).containsExactly("r-up-old/*");
    assertThat(oldSet).doesNotExist();
    assertThat(newSet).exists();
    assertThat(result.kept()).isEqualTo(1);
    assertThat(result.protectedCount()).isEqualTo(1);
  }

  @Test
  void should_report_but_keep_loose_artefacts_when_dry_run() throws Exception {
    services(30, 20);
    Path old = preImportZip("pre-import-old-abc123.zip", Duration.ofDays(40));
    Path oldSet = upgradeSet("r-up-old", Duration.ofDays(40));
    upgradeRun("r-up-old", Duration.ofDays(40));
    upgradeRun("r-up-new", Duration.ofDays(1));

    RetentionPruner.Result result = pruner().prune(true);

    assertThat(ids(result))
        .containsExactlyInAnyOrder("pre-import/pre-import-old-abc123.zip", "r-up-old/*");
    assertThat(old).exists();
    assertThat(oldSet).exists();
  }

  /** A run started {@code age} ago, ended or not, with a bundle copy in its run directory. */
  private Path runDir(String runId, Duration age, boolean ended) throws IOException {
    Instant started = now.minus(age);
    fake.stateStore().recordRunStart(runId, "hotfix-apply", Optional.empty(), started);
    if (ended) {
      fake.stateStore().recordRunEnd(runId, started.plusSeconds(60), TerminalState.SUCCEEDED, 0);
    }
    Path bundle = Files.createDirectories(fake.home.runDir(runId).resolve("bundle"));
    Files.writeString(bundle.resolve("manifest.json"), "{}", StandardCharsets.UTF_8);
    return fake.home.runDir(runId);
  }

  /** Issue #53: run directories follow retention like the run's snapshots. */
  @Test
  void should_remove_an_ended_unprotected_run_directory_when_it_is_older_than_retention()
      throws Exception {
    services(30, 20);
    Path old = runDir("r-old", Duration.ofDays(40), true);
    Path fresh = runDir("r-new", Duration.ofDays(1), true);

    RetentionPruner.Result result = pruner().prune(false);

    assertThat(ids(result)).contains("runs/r-old").doesNotContain("runs/r-new");
    assertThat(old).doesNotExist();
    assertThat(fresh).exists();
  }

  /**
   * Field test 3: a converted official package ({@code runs/hotfix-official-*.jrs-upgrade.zip} and
   * its notes) is a cache, rebuilt from the downloaded package when needed, so it goes by age.
   */
  @Test
  void should_remove_an_old_converted_package_when_it_is_older_than_retention() throws Exception {
    services(30, 20);
    Files.createDirectories(fake.home.runs());
    Path old = fake.home.runs().resolve("hotfix-official-0123456789abcdef.jrs-upgrade.zip");
    Path oldNotes = fake.home.runs().resolve(old.getFileName() + ".notes.json");
    Path fresh = fake.home.runs().resolve("hotfix-official-fedcba9876543210.jrs-upgrade.zip");
    for (Path p : List.of(old, oldNotes, fresh)) {
      Files.writeString(p, "x", StandardCharsets.UTF_8);
    }
    for (Path p : List.of(old, oldNotes)) {
      Files.setLastModifiedTime(p, FileTime.from(now.minus(Duration.ofDays(40))));
    }
    Files.setLastModifiedTime(fresh, FileTime.from(now.minus(Duration.ofDays(1))));

    RetentionPruner.Result result = pruner().prune(false);

    assertThat(ids(result)).contains("runs/" + old.getFileName());
    assertThat(old).doesNotExist();
    assertThat(oldNotes).doesNotExist();
    assertThat(fresh).exists();
  }

  @Test
  void should_keep_the_bundle_copy_of_an_installed_hotfix_when_its_run_is_expired()
      throws Exception {
    services(30, 20);
    Path dir = runDir("r-hf", Duration.ofDays(40), true);
    fake.stateStore()
        .recordHotfixInstalled(
            new HotfixInstalled(
                "HF-1",
                "1",
                "t",
                "r-hf",
                Optional.empty(),
                HotfixState.INSTALLED,
                now.minus(Duration.ofDays(40))),
            List.of());

    RetentionPruner.Result result = pruner().prune(false);

    assertThat(ids(result)).doesNotContain("runs/r-hf");
    assertThat(dir.resolve("bundle").resolve("manifest.json")).exists();
  }

  @Test
  void should_keep_unended_runs_and_directories_that_are_not_runs_when_pruning() throws Exception {
    services(30, 20);
    Path pending = runDir("r-pending", Duration.ofDays(40), false);
    Path reapply = Files.createDirectories(fake.home.runs().resolve("upgrade-reapply"));
    Files.setLastModifiedTime(reapply, FileTime.from(now.minus(Duration.ofDays(40))));
    Path subRunOfPending = runDir("r-pending-hf-hf-1", Duration.ofDays(40), true);

    pruner().prune(false);

    assertThat(pending).exists();
    assertThat(reapply).exists();
    assertThat(subRunOfPending).exists();
  }

  @Test
  void should_report_but_keep_run_directories_when_dry_run() throws Exception {
    services(30, 20);
    Path old = runDir("r-old", Duration.ofDays(40), true);

    RetentionPruner.Result result = pruner().prune(true);

    assertThat(ids(result)).contains("runs/r-old");
    assertThat(old).exists();
  }

  private Path preImportZip(String name, Duration age) throws IOException {
    Path dir = Files.createDirectories(fake.home.snapshots().resolve("pre-import"));
    Path zip = dir.resolve(name);
    Files.writeString(zip, "not really a zip", StandardCharsets.UTF_8);
    Files.setLastModifiedTime(zip, FileTime.from(now.minus(age)));
    return zip;
  }

  private Path upgradeSet(String runId, Duration age) throws IOException {
    Path set = fake.home.snapshots().resolve(runId);
    Path archives = Files.createDirectories(set.resolve("backup-webapp"));
    Files.writeString(archives.resolve("webapp.zip"), "archive", StandardCharsets.UTF_8);
    Files.writeString(set.resolve("upgrade.json"), "{}", StandardCharsets.UTF_8);
    FileTime at = FileTime.from(now.minus(age));
    for (Path p :
        List.of(archives.resolve("webapp.zip"), set.resolve("upgrade.json"), archives, set)) {
      Files.setLastModifiedTime(p, at);
    }
    return set;
  }

  private void upgradeRun(String runId, Duration age) {
    Instant started = now.minus(age);
    fake.stateStore()
        .recordRunStart(runId, UpgradeOperations.UPGRADE_OPERATION, Optional.empty(), started);
    fake.stateStore().recordRunEnd(runId, started.plusSeconds(60), TerminalState.SUCCEEDED, 0);
  }

  @Test
  void should_drop_stale_rows_when_their_directory_is_gone() throws Exception {
    services(30, 20);
    snapshot("r-a", "snapshot", Duration.ofDays(1), Optional.empty());
    fake.stateStore()
        .recordSnapshot(
            new SnapshotRecord(
                "r-ghost/snapshot",
                "r-ghost",
                "snapshot",
                fake.home.snapshots().resolve("r-ghost").resolve("snapshot"),
                "0000",
                Optional.empty()));

    RetentionPruner.Result result = pruner().prune(false);

    assertThat(result.removed()).isEmpty();
    assertThat(fake.stateStore().snapshot("r-ghost/snapshot")).isEmpty();
    assertThat(fake.stateStore().snapshot("r-a/snapshot")).isPresent();
  }
}
