package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.core.snapshot.Snapshot;
import com.jaspersoft.jrsupgrade.core.snapshot.SnapshotManifest;
import com.jaspersoft.jrsupgrade.core.state.Customization;
import com.jaspersoft.jrsupgrade.ops.customizations.DefaultCustomizationOperations;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase D of the upgrade plan, report-first: reconcile registered customizations by 3-way
 * comparison. (The hotfix classification jrsctl ran here is gone with the hotfix subsystem; hotfix
 * state belongs to jrs-hotfix, ADR-0001.) Invariant: {@code plan-customization-reapply} copies a
 * customized file over the new one only when the new file still equals the registered original,
 * snapshots the new file first, and otherwise reports a CONFLICT with a unified diff and never
 * blind-copies.
 */
final class ReconcileSteps {

  static final String PLAN_CUSTOMIZATION_REAPPLY = "plan-customization-reapply";
  static final String REAPPLY_SNAPSHOT = "reapply-customizations";
  static final String AUDIT_CUSTOMIZATION_APPLIED = "customizations.reapplied";
  static final String CONFLICT = "CONFLICT";

  private ReconcileSteps() {}

  static final class PlanCustomizationReapply implements Step {
    private final UpgradeRuntime rt;
    private final UpgradeInput in;

    PlanCustomizationReapply(UpgradeRuntime rt, UpgradeInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    @Override
    public String id() {
      return PLAN_CUSTOMIZATION_REAPPLY;
    }

    @Override
    public String title() {
      return "reconcile registered customizations (3-way)";
    }

    @Override
    public String phase() {
      return Phases.RECONCILE;
    }

    @Override
    public String detail() {
      return "new == original -> re-applied (snapshotted first); otherwise CONFLICT with a diff";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      List<Customization> registered = rt.store().customizations();
      if (registered.isEmpty()) {
        Logs.info(rt, ctx, out, this, "no registered customizations");
        return StepResult.ok();
      }
      List<Path> autoApply = new ArrayList<>();
      List<Path> sources = new ArrayList<>();
      int conflicts = 0;
      for (Customization c : registered) {
        ctx.cancel().checkpoint();
        Path file = c.path().toAbsolutePath().normalize();
        Optional<Snapshot> snapshot;
        try {
          snapshot =
              rt.snapshots()
                  .find(
                      DefaultCustomizationOperations.runIdFor(file),
                      DefaultCustomizationOperations.STEP);
          if (snapshot.isPresent()) {
            rt.snapshots().verify(snapshot.get());
          }
        } catch (IOException | RuntimeException e) {
          Logs.warn(
              rt,
              ctx,
              out,
              this,
              CONFLICT + " " + file + ": snapshot unreadable: " + Failures.describe(e));
          conflicts++;
          continue;
        }
        if (snapshot.isEmpty() || snapshot.get().manifest().entries().isEmpty()) {
          Logs.warn(
              rt,
              ctx,
              out,
              this,
              CONFLICT + " " + file + ": registered snapshot is missing; re-register it");
          conflicts++;
          continue;
        }
        SnapshotManifest.Entry entry = snapshot.get().manifest().entries().get(0);
        Path customized = snapshot.get().payloadFile(entry);
        if (!Files.isRegularFile(file)) {
          Logs.warn(
              rt,
              ctx,
              out,
              this,
              CONFLICT
                  + " "
                  + file
                  + ": absent after the upgrade; customized copy at "
                  + customized);
          conflicts++;
          continue;
        }
        String current;
        try {
          current = rt.files().sha256(file);
        } catch (IOException e) {
          Logs.warn(rt, ctx, out, this, CONFLICT + " " + file + ": unreadable: " + e.getMessage());
          conflicts++;
          continue;
        }
        if (current.equals(entry.sha256())) {
          Logs.info(rt, ctx, out, this, file + ": customization already in place");
          continue;
        }
        if (current.equals(c.originalSha256())) {
          autoApply.add(file);
          sources.add(customized);
          continue;
        }
        conflicts++;
        Logs.warn(
            rt,
            ctx,
            out,
            this,
            CONFLICT
                + " "
                + file
                + ": the upgrade changed this file (original "
                + c.originalSha256()
                + ", now "
                + current
                + "); customized copy at "
                + customized);
        try {
          for (String line :
              DefaultCustomizationOperations.diffLines(
                  customized, file, "customized:" + file, "upgraded:" + file)) {
            Logs.warn(rt, ctx, out, this, "  " + line);
          }
        } catch (IOException e) {
          Logs.warn(rt, ctx, out, this, "  (diff unavailable: " + e.getMessage() + ")");
        }
      }
      if (!autoApply.isEmpty()) {
        try {
          Snapshot before =
              rt.snapshots()
                  .create(ctx.runId(), REAPPLY_SNAPSHOT, autoApply, in.paths().commonBase());
          for (int i = 0; i < autoApply.size(); i++) {
            ctx.cancel().checkpoint();
            copyOver(sources.get(i), autoApply.get(i));
            rt.store()
                .audit(
                    rt.actor(),
                    AUDIT_CUSTOMIZATION_APPLIED,
                    autoApply.get(i) + " in run " + ctx.runId());
            Logs.info(
                rt,
                ctx,
                out,
                this,
                autoApply.get(i)
                    + ": customization re-applied (new file equalled the original; saved to "
                    + before.dir()
                    + ")");
          }
        } catch (IOException | RuntimeException e) {
          return Failures.recoverable(
              "cannot re-apply customizations: " + Failures.describe(e),
              "re-apply them by hand from " + rt.home().snapshots(),
              autoApply,
              List.of(rt.home().snapshots().resolve(ctx.runId()).resolve(REAPPLY_SNAPSHOT)));
        }
      }
      Logs.info(
          rt,
          ctx,
          out,
          this,
          registered.size()
              + " customization(s): "
              + autoApply.size()
              + " re-applied, "
              + conflicts
              + " conflict(s) left for the operator");
      return StepResult.ok();
    }

    private void copyOver(Path source, Path target) throws IOException {
      Path staged = target.resolveSibling("." + target.getFileName() + ".jrs-upgrade-reapply");
      byte[] buffer = new byte[64 * 1024];
      try (InputStream inStream = Files.newInputStream(source);
          OutputStream outStream = Files.newOutputStream(staged)) {
        int read;
        while ((read = inStream.read(buffer)) != -1) {
          outStream.write(buffer, 0, read);
        }
      }
      rt.files().atomicReplace(staged, target);
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      try {
        Optional<Snapshot> before = rt.snapshots().find(ctx.runId(), REAPPLY_SNAPSHOT);
        if (before.isPresent()) {
          rt.snapshots().restore(before.get());
          Logs.info(rt, ctx, out, this, "upgraded files restored from " + before.get().dir());
        }
        return StepResult.ok();
      } catch (IOException | RuntimeException e) {
        return Failures.recoverable(
            "cannot restore the upgraded files: " + Failures.describe(e),
            "restore by hand from "
                + rt.home().snapshots().resolve(ctx.runId()).resolve(REAPPLY_SNAPSHOT));
      }
    }
  }
}
