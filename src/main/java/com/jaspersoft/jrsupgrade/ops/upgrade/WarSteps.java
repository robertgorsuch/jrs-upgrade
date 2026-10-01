package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * Issue #9: deploy a patched WAR instead of the package's own by putting it where the package's
 * buildomatic takes the webapp from, the top-level {@code jasperserver[-pro].war}, before the
 * vendor script runs. Invariants: the package's own WAR is moved into the run directory first and
 * put back by compensation, and a WAR this step placed where none was is removed by it; staging the
 * same WAR twice changes nothing; the patched WAR must still hash to what the plan recorded, so a
 * file swapped after planning is refused rather than deployed; the planner refuses a package that
 * also holds an exploded webapp directory, which buildomatic could deploy instead.
 */
final class WarSteps {

  static final String STAGE_PATCHED_WAR = "stage-patched-war";
  static final String ORIGINAL = "package-war.orig";
  static final String STAGED_MARKER = STAGE_PATCHED_WAR + ".staged";

  private WarSteps() {}

  /** Where the package's buildomatic takes the WAR from: its own WAR, else the edition's name. */
  static Path packageWar(UpgradeInput in) {
    return in.target()
        .warFile()
        .orElse(
            in.target()
                .dir()
                .resolve(
                    (VendorSteps.edition(in).equals("pro") ? "jasperserver-pro" : "jasperserver")
                        + ".war"));
  }

  static final class StagePatchedWar implements Step {
    private final UpgradeRuntime rt;
    private final UpgradeInput in;
    private final PatchedWar war;

    StagePatchedWar(UpgradeRuntime rt, UpgradeInput in, PatchedWar war) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
      this.war = Objects.requireNonNull(war, "war");
    }

    private Path target() {
      return packageWar(in);
    }

    private Path original(Context ctx) {
      return in.runDir(ctx).resolve(ORIGINAL);
    }

    private Path marker(Context ctx) {
      return in.runDir(ctx).resolve(STAGED_MARKER);
    }

    @Override
    public String id() {
      return in.scoped(STAGE_PATCHED_WAR);
    }

    @Override
    public String title() {
      return "put the patched WAR in the target package";
    }

    @Override
    public String phase() {
      return Phases.VENDOR_UPGRADE;
    }

    @Override
    public String detail() {
      return war.describe() + " -> " + target() + " (the package's own WAR is kept aside)";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (!rt.files().isWritable(target().getParent())) {
        return CheckResult.fail(
            target().getParent() + " is not writable",
            "grant jrs-upgrade write access to the target package");
      }
      try {
        if (!Files.isRegularFile(war.path())
            || !rt.files().sha256(war.path()).equals(war.sha256())) {
          return CheckResult.fail(
              war.path() + " is not the WAR the plan was built with (sha256 " + war.sha256() + ")",
              "plan the upgrade again with the WAR you mean to deploy");
        }
      } catch (IOException e) {
        return CheckResult.fail(
            "cannot read " + war.path() + ": " + e.getMessage(), "check read access to the WAR");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      Path target = target();
      try {
        if (Files.isRegularFile(target) && rt.files().sha256(target).equals(war.sha256())) {
          Logs.info(rt, ctx, out, this, target + " already is the patched WAR");
          return StepResult.ok();
        }
        Files.createDirectories(in.runDir(ctx));
        if (Files.isRegularFile(target)
            && !Files.exists(original(ctx))
            && !Files.isRegularFile(marker(ctx))) {
          Files.move(target, original(ctx));
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".jrs-upgrade-tmp");
        Files.copy(war.path(), tmp, StandardCopyOption.REPLACE_EXISTING);
        rt.files().atomicReplace(tmp, target);
        Files.writeString(marker(ctx), war.sha256(), StandardCharsets.UTF_8);
        Logs.info(
            rt,
            ctx,
            out,
            this,
            "staged "
                + war.describe()
                + " as "
                + target
                + (Files.exists(original(ctx))
                    ? "; the package's own is at " + original(ctx)
                    : ""));
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot stage " + war.path() + " as " + target + ": " + e.getMessage(),
            "check permissions on the target package and free space there");
      }
    }

    @Override
    public CheckResult postcheck(Context ctx) {
      try {
        return Files.isRegularFile(target()) && rt.files().sha256(target()).equals(war.sha256())
            ? CheckResult.pass()
            : CheckResult.fail(target() + " is not the patched WAR", "run again");
      } catch (IOException e) {
        return CheckResult.fail("cannot read " + target() + ": " + e.getMessage(), "run again");
      }
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      Path target = target();
      try {
        if (Files.isRegularFile(original(ctx))) {
          Files.move(original(ctx), target, StandardCopyOption.REPLACE_EXISTING);
          Files.deleteIfExists(marker(ctx));
          Logs.info(rt, ctx, out, this, "put the package's own WAR back at " + target);
        } else if (Files.isRegularFile(marker(ctx))) {
          Files.deleteIfExists(target);
          Files.deleteIfExists(marker(ctx));
          Logs.info(rt, ctx, out, this, "removed the staged " + target);
        }
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot put the package's WAR back at " + target + ": " + e.getMessage(),
            "move " + original(ctx) + " to " + target + " by hand");
      }
    }
  }
}
