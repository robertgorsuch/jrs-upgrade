package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.engine.CancellationToken;
import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.Event;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.core.json.Json;
import com.jaspersoft.jrsupgrade.core.state.HotfixInstalled;
import com.jaspersoft.jrsupgrade.core.state.HotfixState;
import com.jaspersoft.jrsupgrade.core.state.SnapshotRecord;
import com.jaspersoft.jrsupgrade.core.state.StateStore;
import com.jaspersoft.jrsupgrade.ops.ReportItem;
import com.jaspersoft.jrsupgrade.ops.smoke.SmokeOperation;
import com.jaspersoft.jrsupgrade.ops.smoke.SmokeOptions;
import com.jaspersoft.jrsupgrade.ops.smoke.SmokeReport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Phase E of spec §10.2: the non-mutating smoke test and the final record. Invariants: a smoke FAIL
 * is a {@code Recoverable} failure whose next action names the point-B rollback command;
 * record-upgrade marks every hotfix that was installed before this run and not re-applied by it as
 * {@code SUPERSEDED}, writes {@code upgrade.json} next to the backups and registers the snapshot
 * set as retention-protected ({@code referenced_by = 'upgrade'}); the marker that lists the
 * superseded ids is merged, never overwritten, so a re-execution after a crash keeps them and the
 * compensation that puts the hotfix states back stays complete.
 */
final class VerifySteps {

  static final String SMOKE = "smoke";
  static final String RECORD_UPGRADE = "record-upgrade";
  static final String AUDIT_COMPLETED = "upgrade.completed";
  static final String AUDIT_SUPERSEDED = "hotfix.superseded";
  static final String AUDIT_REVERTED = "upgrade.record-reverted";
  static final String SUPERSEDED_MARKER = RECORD_UPGRADE + ".superseded";

  private VerifySteps() {}

  static String rollbackCommand(String runId) {
    return "jrs-upgrade upgrade rollback " + runId + " --to-point B";
  }

  static final class Smoke implements Step {
    private final UpgradeRuntime rt;
    private final UpgradeInput in;

    Smoke(UpgradeRuntime rt, UpgradeInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    @Override
    public String id() {
      return SMOKE;
    }

    @Override
    public String title() {
      return "smoke test the upgraded server";
    }

    @Override
    public String phase() {
      return Phases.VERIFY;
    }

    @Override
    public String detail() {
      return "login, serverInfo, repository, report, scheduler, export; failure offers rollback to point B";
    }

    @Override
    public boolean mutating() {
      return false;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      SmokeReport report;
      try {
        report =
            new SmokeOperation(rt.services(), out, rt.sleeper())
                .run(SmokeOptions.DEFAULT, ctx.cancel());
      } catch (CancellationToken.CancelledException e) {
        throw e;
      } catch (RuntimeException e) {
        return failure(ctx, "smoke could not run: " + Failures.describe(e));
      }
      for (ReportItem item : report.items()) {
        Event.Log.Level level =
            switch (item.status()) {
              case PASS, SKIP -> Event.Log.Level.INFO;
              case WARN -> Event.Log.Level.WARN;
              case FAIL -> Event.Log.Level.ERROR;
            };
        Logs.emit(
            rt, ctx, out, this, level, item.status() + " " + item.name() + ": " + item.detail());
      }
      if (!report.ok()) {
        List<String> failing = new ArrayList<>();
        for (ReportItem item : report.items()) {
          if (item.status() == ReportItem.Status.FAIL) {
            failing.add(item.name() + ": " + item.detail());
          }
        }
        return failure(ctx, "smoke failed: " + String.join("; ", failing));
      }
      Logs.info(rt, ctx, out, this, "smoke: " + report.counts().summary());
      return StepResult.ok();
    }

    private StepResult failure(Context ctx, String cause) {
      return Failures.recoverable(
          cause,
          "inspect the server log; "
              + rollbackCommand(ctx.runId())
              + " restores the files of point B",
          List.of(in.webappDir()),
          List.of(in.snapshots(ctx).dir()));
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return StepResult.ok();
    }
  }

  static final class RecordUpgrade implements Step {
    private final UpgradeRuntime rt;
    private final UpgradeInput in;
    private final Map<String, Object> facts;

    RecordUpgrade(UpgradeRuntime rt, UpgradeInput in) {
      this(rt, in, Map.of());
    }

    /**
     * {@code facts} are what the plan knew that the input alone does not say (issue #1: the route
     * and its packages; issue #9: the patched WAR's checksum and build), written into the point-B
     * manifest after the keys this step always writes, whose values they may replace.
     */
    RecordUpgrade(UpgradeRuntime rt, UpgradeInput in, Map<String, Object> facts) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
      this.facts = Map.copyOf(facts);
    }

    private Path marker(Context ctx) {
      return ctx.home().runDir(ctx.runId()).resolve(SUPERSEDED_MARKER);
    }

    @Override
    public String id() {
      return RECORD_UPGRADE;
    }

    @Override
    public String title() {
      return "record the upgrade (hotfix states, protected snapshot set, audit)";
    }

    @Override
    public String phase() {
      return Phases.VERIFY;
    }

    @Override
    public String detail() {
      return "hotfixes not re-applied -> SUPERSEDED; snapshots row referenced_by=upgrade";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      StateStore store = rt.store();
      List<String> superseded = new ArrayList<>();
      for (HotfixInstalled h : store.installedHotfixes()) {
        if (h.installedRunId().startsWith(ctx.runId())) {
          continue;
        }
        store.updateHotfixState(h.id(), HotfixState.SUPERSEDED);
        store.audit(
            rt.actor(), AUDIT_SUPERSEDED, h.id() + " superseded by upgrade run " + ctx.runId());
        superseded.add(h.id());
        Logs.info(rt, ctx, out, this, h.id() + " marked SUPERSEDED");
      }
      SnapshotSet set = in.snapshots(ctx);
      try {
        Files.createDirectories(marker(ctx).getParent());
        // A re-execution after a crash finds the hotfixes already SUPERSEDED and would otherwise
        // overwrite the marker with an empty list, leaving compensation nothing to put back.
        Set<String> recorded = new LinkedHashSet<>();
        if (Files.isRegularFile(marker(ctx))) {
          for (String id : Files.readAllLines(marker(ctx), StandardCharsets.UTF_8)) {
            if (!id.isBlank()) {
              recorded.add(id.strip());
            }
          }
        }
        recorded.addAll(superseded);
        Files.writeString(marker(ctx), String.join("\n", recorded), StandardCharsets.UTF_8);
        Files.createDirectories(set.dir());
        Files.writeString(
            set.manifest(), Json.writePretty(manifest(ctx, set)), StandardCharsets.UTF_8);
        store.recordSnapshot(
            new SnapshotRecord(
                ctx.runId() + "/" + BackupSteps.REFERENCED_BY,
                ctx.runId(),
                BackupSteps.REFERENCED_BY,
                set.dir(),
                rt.files().sha256(set.manifest()),
                Optional.of(BackupSteps.REFERENCED_BY)));
      } catch (IOException | RuntimeException e) {
        return Failures.recoverable(
            "cannot record the upgrade: " + Failures.describe(e),
            "check " + set.dir() + " and the state store");
      }
      store.audit(
          rt.actor(),
          AUDIT_COMPLETED,
          in.currentVersion().orElse("?")
              + " -> "
              + in.options().toVersion()
              + " ("
              + in.options().mode()
              + ") in run "
              + ctx.runId()
              + "; backups at "
              + set.dir());
      Logs.info(rt, ctx, out, this, "upgrade recorded; backups protected at " + set.dir());
      return StepResult.ok();
    }

    private Map<String, Object> manifest(Context ctx, SnapshotSet set) throws IOException {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("runId", ctx.runId());
      m.put("from", in.currentVersion().orElse("unknown"));
      m.put("to", in.options().toVersion());
      m.put("mode", in.options().mode().name());
      m.put("webapp", in.webappDir().toString());
      m.put("buildomatic", in.installedBuildomatic().toString());
      Map<String, String> artefacts = new LinkedHashMap<>();
      for (Path p : List.of(set.fullExport(), set.webappArchive(), set.buildomaticArchive())) {
        if (Files.isRegularFile(p)) {
          artefacts.put(
              set.dir().relativize(p).toString().replace('\\', '/'), rt.files().sha256(p));
        }
      }
      // an export adopted from outside the home (ADR-0028) is recorded by its own path
      set.externalExport().ifPresent(e -> artefacts.put("external:" + e.path(), e.sha256()));
      m.put("artefacts", artefacts);
      m.put("recordedAt", rt.clock().instant().toString());
      new java.util.TreeMap<>(facts).forEach(m::put);
      return m;
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      StateStore store = rt.store();
      try {
        if (Files.isRegularFile(marker(ctx))) {
          for (String id : Files.readAllLines(marker(ctx), StandardCharsets.UTF_8)) {
            if (!id.isBlank()) {
              store.updateHotfixState(id.strip(), HotfixState.INSTALLED);
              store.audit(
                  rt.actor(),
                  AUDIT_REVERTED,
                  id.strip() + " back to INSTALLED (run " + ctx.runId() + ")");
            }
          }
          Files.deleteIfExists(marker(ctx));
        }
        return StepResult.ok();
      } catch (IOException | RuntimeException e) {
        return Failures.recoverable(
            "cannot revert the upgrade record: " + Failures.describe(e),
            "check hotfix states with jrs-upgrade hotfix list");
      }
    }
  }
}
