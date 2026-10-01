package com.jaspersoft.jrsupgrade.ops.retention;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.engine.LockHeldException;
import com.jaspersoft.jrsupgrade.core.engine.RunLock;
import com.jaspersoft.jrsupgrade.core.engine.RunRecord;
import com.jaspersoft.jrsupgrade.core.platform.Trees;
import com.jaspersoft.jrsupgrade.core.snapshot.Snapshot;
import com.jaspersoft.jrsupgrade.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsupgrade.core.state.AuditActor;
import com.jaspersoft.jrsupgrade.core.state.SnapshotRecord;
import com.jaspersoft.jrsupgrade.core.state.StateStore;
import com.jaspersoft.jrsupgrade.ops.Services;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retention pruning of the snapshot tree and the run directories (spec §5.6, §14 phase 8): applies
 * {@code backups.retentionDays} and {@code backups.maxSnapshots} through {@link
 * SnapshotStore#prune}, removes the {@code runs/<runId>/} directories of ended runs older than the
 * retention (issue #53), never touching a run named by {@link RetentionProtection}, then drops the
 * {@code snapshots} rows of every directory it deleted and writes one {@code runs.prune} audit row.
 * Invariants: pruning is not a journaled run, so {@code step_transitions} is never written; a dry
 * run computes the same candidates and mutates nothing, not even the audit trail; {@link
 * #afterSuccessfulRun} is the best-effort automatic form, which additionally protects the run that
 * just finished, skips when the run lock is held and never throws; {@link #prune} is the operator's
 * explicit form and lets I/O failures surface so the command can report them.
 */
public final class RetentionPruner {

  public static final String AUDIT_ACTION = "runs.prune";

  private static final Logger LOG = LoggerFactory.getLogger(RetentionPruner.class);

  /** Where {@code import} keeps its pre-import zips: {@code snapshots/pre-import/}. */
  static final String PRE_IMPORT_DIR = "pre-import";

  /** Working directories {@code BundleWorkspace} leaves under {@code runs/} when cleanup fails. */
  static final String VERIFY_PREFIX = "hotfix-verify-";

  /** Converted official packages under {@code runs/}: a cache rebuilt from the download. */
  static final String CONVERTED_PREFIX = "hotfix-official-";

  /** jrsctl re-applied hotfixes as {@code <runId>-hf-<slug>} sub-runs; old homes may hold them. */
  static final String SUB_RUN_SUFFIX = "-hf-";

  /** One snapshot the pruner removed (or, in a dry run, would remove). */
  public record Removed(String id, String runId, String stepId, Path path) {}

  /**
   * Outcome of one pruning pass: {@code kept} snapshots remain on disk, of which {@code
   * protectedCount} belong to a protected run.
   */
  public record Result(boolean dryRun, List<Removed> removed, int kept, int protectedCount) {
    public Result {
      removed = List.copyOf(removed);
    }
  }

  private final Services services;
  private final SnapshotStore snapshots;
  private final String actor;

  public RetentionPruner(Services services, SnapshotStore snapshots, String actor) {
    this.services = Objects.requireNonNull(services, "services");
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    this.actor = Objects.requireNonNull(actor, "actor");
  }

  /** A pruner over the home's snapshot tree, auditing as the current OS user. */
  public static RetentionPruner of(Services services) {
    return new RetentionPruner(
        services,
        new SnapshotStore(services.home(), services.platform().files(), services.clock()),
        AuditActor.current());
  }

  /**
   * The operator's {@code runs prune}: prunes per {@code backups.*}, always writing an audit row
   * unless {@code dryRun}, in which case nothing changes and the result lists what would go.
   */
  public Result prune(boolean dryRun) throws IOException {
    return prune(dryRun, Set.of(), true);
  }

  /**
   * Automatic pruning at the end of the successful run {@code runId}: that run's own snapshots are
   * protected explicitly, the run lock is taken for the duration (and the pass is skipped when it
   * is held), an audit row is written only when something was removed, and any failure is logged as
   * a warning instead of thrown, so it can never change the run's outcome.
   */
  public Optional<Result> afterSuccessfulRun(String runId) {
    Objects.requireNonNull(runId, "runId");
    try (RunLock unusedLock =
        new RunLock(services.home(), runId + "-prune", services.clock().instant())) {
      return Optional.of(prune(false, Set.of(runId), false));
    } catch (LockHeldException held) {
      LOG.info(
          "retention pruning after run {} skipped: run lock held by {}", runId, held.holderRunId());
      return Optional.empty();
    } catch (IOException | RuntimeException e) {
      LOG.warn("retention pruning after run {} failed: {}", runId, e.toString());
      return Optional.empty();
    }
  }

  private Result prune(boolean dryRun, Set<String> alsoProtected, boolean auditAlways)
      throws IOException {
    StateStore store = services.stateStore().get();
    Config.Backups backups = services.config().backups();
    Set<String> protectedRuns = new HashSet<>(RetentionProtection.compute(store).runIds());
    protectedRuns.addAll(alsoProtected);
    Duration retention = Duration.ofDays(backups.retentionDays());
    int max = backups.maxSnapshots();

    List<Snapshot> all = snapshots.list();
    List<Snapshot> gone =
        dryRun
            ? snapshots.pruneCandidates(retention, max, protectedRuns)
            : snapshots.prune(retention, max, protectedRuns);
    Set<Path> goneDirs = new HashSet<>();
    Set<String> prunedRuns = new HashSet<>();
    List<Removed> removed = new ArrayList<>();
    for (Snapshot s : gone) {
      goneDirs.add(s.dir());
      prunedRuns.add(s.runId());
      removed.add(new Removed(s.runId() + "/" + s.stepId(), s.runId(), s.stepId(), s.dir()));
      if (!dryRun) {
        store.deleteSnapshot(s.runId(), s.stepId());
      }
    }
    int kept = 0;
    int protectedKept = 0;
    Set<String> runsWithSnapshots = new HashSet<>();
    for (Snapshot s : all) {
      if (goneDirs.contains(s.dir())) {
        continue;
      }
      runsWithSnapshots.add(s.runId());
      kept++;
      if (protectedRuns.contains(s.runId())) {
        protectedKept++;
      }
    }
    Set<Path> snapshotDirs = new HashSet<>();
    for (Snapshot s : all) {
      snapshotDirs.add(s.dir());
    }
    Loose loose =
        looseArtefacts(
            store, protectedRuns, prunedRuns, runsWithSnapshots, snapshotDirs, retention, dryRun);
    removed.addAll(loose.removed());
    kept += loose.kept();
    protectedKept += loose.protectedKept();
    // run directories are listed with what was removed; kept and protected stay snapshot counts
    removed.addAll(runDirectories(store, protectedRuns, retention, dryRun));
    removed.addAll(convertedPackages(retention, dryRun));
    if (!dryRun) {
      sweepStaleRows(store, protectedRuns);
    }
    if (!dryRun && (auditAlways || !removed.isEmpty())) {
      store.audit(
          actor,
          AUDIT_ACTION,
          "removed "
              + removed.size()
              + " snapshot(s); kept "
              + kept
              + " ("
              + protectedKept
              + " protected); retentionDays="
              + backups.retentionDays()
              + " maxSnapshots="
              + max);
    }
    return new Result(dryRun, removed, kept, protectedKept);
  }

  private record Loose(List<Removed> removed, int kept, int protectedKept) {}

  /**
   * Review finding 1.19: two kinds of backup live under the snapshots root without a manifest and
   * so were invisible to {@link SnapshotStore#list}: upgrade sets ({@code <runId>/backup-webapp},
   * the full export and {@code upgrade.json}) and pre-import zips ({@code pre-import/*.zip}). An
   * upgrade set follows its run: it goes when the run's step snapshots went in this pass, or when
   * the run is unprotected, holds no step snapshot any more and started before the retention
   * cut-off. A pre-import zip names no run, so it goes by age alone and never while any run is
   * pending recovery, whose rollback may re-import it.
   */
  private Loose looseArtefacts(
      StateStore store,
      Set<String> protectedRuns,
      Set<String> prunedRuns,
      Set<String> runsWithSnapshots,
      Set<Path> snapshotDirs,
      Duration retention,
      boolean dryRun)
      throws IOException {
    Path root = services.home().snapshots();
    if (!Files.isDirectory(root)) {
      return new Loose(List.of(), 0, 0);
    }
    Optional<Instant> cutoff =
        retention.isZero() || retention.isNegative()
            ? Optional.empty()
            : Optional.of(services.clock().instant().minus(retention));
    List<Removed> removed = new ArrayList<>();
    int kept = 0;
    int protectedKept = 0;
    boolean pending = !store.pendingRuns().isEmpty();
    List<Path> entries;
    try (Stream<Path> listing = Files.list(root)) {
      entries = listing.sorted().toList();
    }
    for (Path entry : entries) {
      String name = entry.getFileName().toString();
      if (name.equals(PRE_IMPORT_DIR) && Files.isDirectory(entry)) {
        List<Path> zips;
        try (Stream<Path> listing = Files.list(entry)) {
          zips = listing.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path zip : zips) {
          boolean expired =
              cutoff.isPresent()
                  && Files.getLastModifiedTime(zip).toInstant().isBefore(cutoff.get());
          if (expired && !pending) {
            removed.add(
                new Removed(
                    PRE_IMPORT_DIR + "/" + zip.getFileName(),
                    PRE_IMPORT_DIR,
                    zip.getFileName().toString(),
                    zip));
            if (!dryRun) {
              LOG.info("pruning pre-import snapshot {}", zip);
              Files.delete(zip);
            }
          } else {
            kept++;
            if (expired) {
              protectedKept++;
            }
          }
        }
        continue;
      }
      if (!Files.isDirectory(entry)
          || runsWithSnapshots.contains(name)
          || !holdsLooseContent(entry, snapshotDirs)) {
        continue;
      }
      if (protectedRuns.contains(name)) {
        kept++;
        protectedKept++;
        continue;
      }
      boolean expired = prunedRuns.contains(name);
      if (!expired && cutoff.isPresent()) {
        Instant started =
            store
                .run(name)
                .map(RunRecord::startedAt)
                .orElse(Files.getLastModifiedTime(entry).toInstant());
        expired = started.isBefore(cutoff.get());
      }
      if (!expired) {
        kept++;
        continue;
      }
      removed.add(new Removed(name + "/*", name, "*", entry));
      if (!dryRun) {
        LOG.info("pruning the backups of run {} under {}", name, entry);
        Trees.deleteRecursively(entry);
      }
    }
    return new Loose(removed, kept, protectedKept);
  }

  /**
   * Issue #53: run directories ({@code runs/<runId>/}: bundle copies, staging, stop markers) follow
   * the protection that keeps their snapshots. A directory goes only when its name is a run the
   * state store knows, that run has ended and started before the retention cut-off, and neither it
   * nor the run it is a {@code -hf-} sub-run of is protected. A leftover {@code hotfix-verify-*}
   * directory goes by age. Any other name under {@code runs/} is never touched. Only what goes is
   * returned: {@link Result#kept} and {@link Result#protectedCount} stay snapshot counts.
   */
  private List<Removed> runDirectories(
      StateStore store, Set<String> protectedRuns, Duration retention, boolean dryRun)
      throws IOException {
    Path root = services.home().runs();
    if (retention.isZero() || retention.isNegative() || !Files.isDirectory(root)) {
      return List.of();
    }
    Instant cutoff = services.clock().instant().minus(retention);
    List<Removed> removed = new ArrayList<>();
    List<Path> entries;
    try (Stream<Path> listing = Files.list(root)) {
      entries = listing.filter(Files::isDirectory).sorted().toList();
    }
    for (Path entry : entries) {
      String name = entry.getFileName().toString();
      boolean expired;
      if (name.startsWith(VERIFY_PREFIX)) {
        expired = Files.getLastModifiedTime(entry).toInstant().isBefore(cutoff);
      } else {
        Optional<RunRecord> run = store.run(name);
        if (run.isEmpty() || isProtected(name, protectedRuns)) {
          continue;
        }
        expired = run.get().terminalState().isPresent() && run.get().startedAt().isBefore(cutoff);
      }
      if (!expired) {
        continue;
      }
      removed.add(new Removed("runs/" + name, name, "*", entry));
      if (!dryRun) {
        LOG.info("pruning run directory {}", entry);
        Trees.deleteRecursively(entry);
      }
    }
    return removed;
  }

  /**
   * Field test 3: {@code runs/hotfix-official-<sha>.jrs-upgrade.zip} and its {@code .notes.json}
   * are what planning converts an official package into. Nothing refers to them after planning: a
   * run keeps its own bundle copy under its run directory, and a later command converts the
   * download again. So they go by age, like the {@code hotfix-verify-*} leftovers, and never by
   * run.
   */
  private List<Removed> convertedPackages(Duration retention, boolean dryRun) throws IOException {
    Path root = services.home().runs();
    if (retention.isZero() || retention.isNegative() || !Files.isDirectory(root)) {
      return List.of();
    }
    Instant cutoff = services.clock().instant().minus(retention);
    List<Path> entries;
    try (Stream<Path> listing = Files.list(root)) {
      entries =
          listing
              .filter(Files::isRegularFile)
              .filter(p -> p.getFileName().toString().startsWith(CONVERTED_PREFIX))
              .sorted()
              .toList();
    }
    List<Removed> removed = new ArrayList<>();
    for (Path entry : entries) {
      if (!Files.getLastModifiedTime(entry).toInstant().isBefore(cutoff)) {
        continue;
      }
      String name = entry.getFileName().toString();
      removed.add(new Removed("runs/" + name, name, "*", entry));
      if (!dryRun) {
        LOG.info("pruning converted hotfix package {}", entry);
        Files.deleteIfExists(entry);
      }
    }
    return removed;
  }

  private static boolean isProtected(String runId, Set<String> protectedRuns) {
    for (String p : protectedRuns) {
      if (runId.equals(p) || runId.startsWith(p + SUB_RUN_SUFFIX)) {
        return true;
      }
    }
    return false;
  }

  /** True when {@code runDir} holds anything besides manifest snapshot directories. */
  private static boolean holdsLooseContent(Path runDir, Set<Path> snapshotDirs) throws IOException {
    try (Stream<Path> children = Files.list(runDir)) {
      return children.anyMatch(child -> !snapshotDirs.contains(child.toAbsolutePath().normalize()));
    }
  }

  /**
   * Drops {@code snapshots} rows of unprotected runs whose directory no longer exists (an earlier
   * pass that deleted the directory but died before the row went, or an operator's manual
   * clean-up).
   */
  private void sweepStaleRows(StateStore store, Set<String> protectedRuns) {
    for (SnapshotRecord row : store.snapshots()) {
      if (protectedRuns.contains(row.runId()) || Files.exists(row.path())) {
        continue;
      }
      LOG.info("dropping stale snapshot row {} ({} is gone)", row.id(), row.path());
      store.deleteSnapshot(row.runId(), row.stepId());
    }
  }
}
