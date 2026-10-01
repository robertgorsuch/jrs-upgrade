package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.core.snapshot.Snapshot;
import com.jaspersoft.jrsupgrade.core.snapshot.SnapshotManifest;
import com.jaspersoft.jrsupgrade.ops.hotfix.HotfixPaths;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.RollbackPoint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Steps of the rollback plan (spec §10.4 {@code upgrade rollback <runId> --to-point B|C}). Point C
 * restores the same artefacts as point B (spec §10.2: "rollback point C = restore B"). Invariants:
 * a directory being replaced is moved aside under the rollback run's directory and put back by
 * compensation; configuration and keystore files are snapshotted under the rollback run before they
 * are overwritten, so every restore step can be undone; archives are hash-verified before
 * extraction; record-rollback is the only step that touches the state store.
 */
final class RestoreSteps {

  static final String STOP_SERVICE = "stop-service";
  static final String RESTORE_WEBAPP = "restore-webapp";
  static final String RESTORE_BUILDOMATIC = "restore-buildomatic";
  static final String RESTORE_CONFIG = "restore-config";
  static final String RESTORE_KEYSTORE = "restore-keystore";
  static final String START_SERVICE = "start-service";
  static final String WAIT_FOR_SERVER = "wait-for-server";
  static final String RECORD_ROLLBACK = "record-rollback";
  static final String AUDIT_ROLLED_BACK = "upgrade.rolled-back";
  static final String PRE_RESTORE_PREFIX = "pre-restore-";

  /**
   * What a rollback plan was built from; {@code installedBuildomatic} is the tree the upgrade run
   * recorded in its manifest when it has one, so the archive goes back where it came from.
   */
  record Input(
      String upgradeRunId,
      RollbackPoint point,
      SnapshotSet set,
      HotfixPaths paths,
      String webappName,
      Path installedBuildomatic) {
    Input {
      Objects.requireNonNull(upgradeRunId, "upgradeRunId");
      Objects.requireNonNull(point, "point");
      Objects.requireNonNull(set, "set");
      Objects.requireNonNull(paths, "paths");
      Objects.requireNonNull(webappName, "webappName");
      installedBuildomatic =
          Objects.requireNonNull(installedBuildomatic, "installedBuildomatic")
              .toAbsolutePath()
              .normalize();
    }

    Path webappDir() {
      return paths.tomcatDir().resolve("webapps").resolve(webappName);
    }
  }

  private RestoreSteps() {}

  abstract static class Restore implements Step {
    final UpgradeRuntime rt;
    final Input in;

    Restore(UpgradeRuntime rt, Input in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    @Override
    public String phase() {
      return Phases.ROLLBACK;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }
  }

  /** Restores a directory from an archive, moving the current one aside. */
  abstract static class RestoreDir extends Restore {

    RestoreDir(UpgradeRuntime rt, Input in) {
      super(rt, in);
    }

    abstract Path archive();

    abstract Path target();

    abstract boolean required();

    Path aside(Context ctx) {
      return PointB.asideFor(ctx.home().runDir(ctx.runId()), target());
    }

    @Override
    public String detail() {
      return archive() + " -> " + target();
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (!Files.isRegularFile(archive())) {
        return required()
            ? CheckResult.fail(
                "archive " + archive() + " is missing",
                "the backups of run " + in.upgradeRunId() + " are incomplete; restore by hand")
            : CheckResult.warn("no archive at " + archive() + "; nothing to restore");
      }
      try {
        PointB.verifyArchive(archive(), rt.files().sha256(archive()));
      } catch (IOException e) {
        return CheckResult.fail(
            "archive verification failed: " + e.getMessage(),
            "do not use these backups; restore by hand");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      if (!Files.isRegularFile(archive())) {
        Logs.warn(rt, ctx, out, this, "no archive at " + archive() + "; skipped");
        return StepResult.ok();
      }
      try {
        long entries =
            PointB.restoreDir(in.set().os(), archive(), target(), aside(ctx), ctx.cancel());
        Logs.info(rt, ctx, out, this, entries + " entries restored to " + target());
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot restore " + target() + ": " + e.getMessage(),
            "restore by hand from " + archive() + "; the previous tree is under " + aside(ctx),
            List.of(target()),
            List.of(archive(), aside(ctx)));
      }
    }

    @Override
    public CheckResult postcheck(Context ctx) {
      return !Files.isRegularFile(archive()) || Files.isDirectory(target())
          ? CheckResult.pass()
          : CheckResult.fail(target() + " is missing after restore", "restore by hand");
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      try {
        if (PointB.undoRestore(target(), aside(ctx))) {
          Logs.info(rt, ctx, out, this, "put " + aside(ctx) + " back to " + target());
        }
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot put " + aside(ctx) + " back: " + e.getMessage(),
            "move it back to " + target() + " by hand",
            List.of(target()),
            List.of(aside(ctx)));
      }
    }
  }

  static final class RestoreWebapp extends RestoreDir {
    RestoreWebapp(UpgradeRuntime rt, Input in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return RESTORE_WEBAPP;
    }

    @Override
    public String title() {
      return "restore the webapp from the point-B archive";
    }

    @Override
    Path archive() {
      return in.set().webappArchive();
    }

    @Override
    Path target() {
      return in.webappDir();
    }

    @Override
    boolean required() {
      return true;
    }
  }

  static final class RestoreBuildomatic extends RestoreDir {
    RestoreBuildomatic(UpgradeRuntime rt, Input in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return RESTORE_BUILDOMATIC;
    }

    @Override
    public String title() {
      return "restore the installed buildomatic directory";
    }

    @Override
    Path archive() {
      return in.set().buildomaticArchive();
    }

    @Override
    Path target() {
      return in.installedBuildomatic();
    }

    @Override
    boolean required() {
      return false;
    }
  }

  /** Restores a SnapshotStore entry of the upgrade run after snapshotting the current files. */
  abstract static class RestoreFiles extends Restore {

    RestoreFiles(UpgradeRuntime rt, Input in) {
      super(rt, in);
    }

    abstract String stepId();

    String preRestoreId() {
      return PRE_RESTORE_PREFIX + stepId();
    }

    Optional<Snapshot> source() throws IOException {
      return rt.snapshots().find(in.upgradeRunId(), stepId());
    }

    /** True when the upgrade run journalled a snapshot for this step, whatever is on disk now. */
    boolean wasRecorded() {
      return rt.store().snapshots(in.upgradeRunId()).stream()
          .anyMatch(row -> row.stepId().equals(stepId()));
    }

    @Override
    public String detail() {
      return in.set().dir().resolve(stepId()).toString();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      try {
        Optional<Snapshot> snapshot = source();
        if (snapshot.isEmpty()) {
          if (wasRecorded()) {
            // Between the plan's point-B check and now, something removed it. Carrying on would
            // leave the restored webapp beside files belonging to the version it replaced.
            return Failures.recoverable(
                stepId()
                    + " snapshot of run "
                    + in.upgradeRunId()
                    + " was recorded but is no longer under "
                    + in.set().dir(),
                "restore " + in.set().dir() + " from a backup before rolling back again",
                List.of(),
                List.of(in.set().dir()));
          }
          Logs.info(
              rt,
              ctx,
              out,
              this,
              "run " + in.upgradeRunId() + " saved no " + stepId() + "; nothing to restore");
          return StepResult.ok();
        }
        List<Path> current = new ArrayList<>();
        Path base = snapshot.get().manifest().baseDir();
        for (SnapshotManifest.Entry entry : snapshot.get().manifest().entries()) {
          Path file = base.resolve(entry.path());
          if (Files.isRegularFile(file)) {
            current.add(file);
          }
        }
        if (!current.isEmpty()) {
          rt.snapshots().create(ctx.runId(), preRestoreId(), current, base);
        }
        rt.snapshots().restore(snapshot.get());
        Logs.info(
            rt,
            ctx,
            out,
            this,
            snapshot.get().manifest().entries().size()
                + " file(s) restored from "
                + snapshot.get().dir());
        return StepResult.ok();
      } catch (IOException | RuntimeException e) {
        return Failures.recoverable(
            "cannot restore " + stepId() + ": " + Failures.describe(e),
            "restore by hand from " + in.set().dir().resolve(stepId()),
            List.of(),
            List.of(in.set().dir().resolve(stepId())));
      }
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      try {
        Optional<Snapshot> pre = rt.snapshots().find(ctx.runId(), preRestoreId());
        if (pre.isPresent()) {
          rt.snapshots().restore(pre.get());
        }
        return StepResult.ok();
      } catch (IOException | RuntimeException e) {
        return Failures.recoverable(
            "cannot undo " + stepId() + ": " + Failures.describe(e),
            "restore by hand from "
                + rt.home().snapshots().resolve(ctx.runId()).resolve(preRestoreId()));
      }
    }
  }

  static final class RestoreConfig extends RestoreFiles {
    RestoreConfig(UpgradeRuntime rt, Input in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return RESTORE_CONFIG;
    }

    @Override
    public String title() {
      return "restore configuration files (and the database connection with them)";
    }

    @Override
    String stepId() {
      return SnapshotSet.CONFIG_STEP;
    }
  }

  static final class RestoreKeystore extends RestoreFiles {
    RestoreKeystore(UpgradeRuntime rt, Input in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return RESTORE_KEYSTORE;
    }

    @Override
    public String title() {
      return "restore the keystore";
    }

    @Override
    String stepId() {
      return SnapshotSet.KEYSTORE_STEP;
    }
  }

  static final class RecordRollback extends Restore {
    RecordRollback(UpgradeRuntime rt, Input in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return RECORD_ROLLBACK;
    }

    @Override
    public String title() {
      return "record the rollback";
    }

    @Override
    public String detail() {
      return "audit " + AUDIT_ROLLED_BACK + " for run " + in.upgradeRunId();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      rt.store()
          .audit(
              rt.actor(),
              AUDIT_ROLLED_BACK,
              "upgrade run "
                  + in.upgradeRunId()
                  + " rolled back to point "
                  + in.point()
                  + " by run "
                  + ctx.runId()
                  + " (files only; the database is the operator's responsibility)");
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      rt.store()
          .audit(
              rt.actor(),
              AUDIT_ROLLED_BACK + ".reverted",
              "rollback run " + ctx.runId() + " undone");
      return StepResult.ok();
    }
  }
}
