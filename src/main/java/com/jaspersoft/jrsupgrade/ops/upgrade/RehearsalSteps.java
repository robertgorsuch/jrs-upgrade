package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsupgrade.jrs.vendor.VendorRun;
import com.jaspersoft.jrsupgrade.jrs.vendor.VendorTools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code jrs-upgrade upgrade --test} (spec §10.2 "Rehearsal"): the vendor's own validation of the
 * staged properties, the database connection and the package, run before anything is touched.
 * {@code js-upgrade-newdb test} and {@code js-upgrade-samedb test} run {@code
 * pre-upgrade-test-<ce|pro>} and, by the vendor's own word, modify no instance and no resource;
 * with {@code test} the newdb wrapper needs no export file (buildomatic {@code bin/do-js-upgrade}).
 * Invariants: {@link RunVendorTest} is read-only ({@code mutating() == false}) and judged like the
 * real run (Ant's {@code BUILD FAILED} or the keystore banner fail it); the two staging steps that
 * precede it are undone by {@link UnstageTargetPackage} at the end of a successful rehearsal,
 * exactly as the engine undoes them after a failure, so the package is left as it was found; the
 * service is never stopped and nothing is backed up.
 */
final class RehearsalSteps {

  static final String RUN_VENDOR_TEST = "run-vendor-test";
  static final String UNSTAGE_TARGET_PACKAGE = "unstage-target-package";
  static final String TEST_OPTION = "test";
  static final String TEST_TARGET_PREFIX = "pre-upgrade-test-";

  private RehearsalSteps() {}

  /** {@code js-upgrade-<mode> test} if the package ships the wrapper, else what it would run. */
  static final class RunVendorTest implements Step {
    private final UpgradeRuntime rt;
    private final UpgradeInput in;

    RunVendorTest(UpgradeRuntime rt, UpgradeInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    String scriptName() {
      return VendorSteps.SCRIPT_PREFIX + in.options().mode().vendorSuffix();
    }

    String antTarget() {
      return TEST_TARGET_PREFIX + VendorSteps.edition(in);
    }

    String strategy() {
      return VendorSteps.strategy(in.options().mode());
    }

    @Override
    public String id() {
      return in.scoped(RUN_VENDOR_TEST);
    }

    @Override
    public String title() {
      return "rehearse with the vendor's validation (" + scriptName() + " " + TEST_OPTION + ")";
    }

    @Override
    public String phase() {
      return Phases.VENDOR_UPGRADE;
    }

    @Override
    public String detail() {
      return scriptName()
          + " "
          + TEST_OPTION
          + " if shipped, else js-ant "
          + antTarget()
          + " -Dstrategy="
          + strategy()
          + "; validates the properties, the database connection and the package; changes"
          + " nothing; JAVA_HOME="
          + rt.config().vendor().javaHome().map(Path::toString).orElse("<unset>");
    }

    @Override
    public boolean mutating() {
      return false;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (in.targetBuildomatic().isEmpty()) {
        return CheckResult.fail(
            "no buildomatic directory in " + in.target().dir(), "check the target package");
      }
      if (rt.config().vendor().javaHome().isEmpty()) {
        return CheckResult.fail(
            "vendor.javaHome is not set", "set vendor.javaHome to the JDK the target needs");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      Buildomatic b = in.target().buildomatic().orElseThrow();
      Optional<Path> javaHome = rt.config().vendor().javaHome();
      Path wrapper = b.dir().resolve(scriptName() + rt.locator().scriptExtension());
      VendorRun run;
      if (Files.isRegularFile(wrapper)) {
        Map<String, Path> scripts = new LinkedHashMap<>(b.scripts());
        scripts.put(scriptName(), wrapper);
        Buildomatic withWrapper =
            new Buildomatic(b.dir(), scripts, b.masterPropertiesFile(), b.masterProperties());
        run =
            rt.tools()
                .run(
                    new VendorTools.Invocation(
                        withWrapper, scriptName(), List.of(TEST_OPTION), javaHome),
                    out,
                    Logs.scope(ctx, this));
      } else {
        List<String> antArgs = List.of("-Dstrategy=" + strategy());
        Logs.info(
            rt,
            ctx,
            out,
            this,
            "no " + wrapper.getFileName() + " in the package; using js-ant " + antTarget());
        run = rt.tools().ant(b, antTarget(), antArgs, javaHome, out, Logs.scope(ctx, this));
      }
      return switch (run) {
        case VendorRun.Completed c -> {
          if (c.ok()) {
            Logs.info(rt, ctx, out, this, "vendor validation passed; nothing was changed");
            yield StepResult.ok();
          }
          yield Failures.recoverable(
              "vendor validation " + c.summary() + ": " + String.join(" | ", c.tail()),
              "fix what the validation reports (the staged default_master.properties, the"
                  + " database, the package), then rehearse again; nothing was changed");
        }
        case VendorRun.TimedOut t ->
            Failures.recoverable(
                "vendor validation did not finish within " + t.timeout().toMinutes() + " minutes",
                "check for a hung buildomatic process; nothing was changed");
        case VendorRun.NotStarted n -> Failures.recoverable(n.reason(), n.remediation());
      };
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return StepResult.ok();
    }
  }

  /**
   * Puts the target package back as it was: the staging steps' own compensations, run at the end of
   * a successful rehearsal, since the engine only runs them after a failure.
   */
  static final class UnstageTargetPackage implements Step {
    private final UpgradeRuntime rt;
    private final VendorSteps.WriteMasterProperties master;
    private final VendorSteps.StageKeystoreInit keystore;

    UnstageTargetPackage(
        UpgradeRuntime rt,
        VendorSteps.WriteMasterProperties master,
        VendorSteps.StageKeystoreInit keystore) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.master = Objects.requireNonNull(master, "master");
      this.keystore = Objects.requireNonNull(keystore, "keystore");
    }

    @Override
    public String id() {
      // the hop of the staging it undoes (issue #1): a transit hop's carries its version
      return master.id().replace(VendorSteps.WRITE_MASTER_PROPERTIES, UNSTAGE_TARGET_PACKAGE);
    }

    @Override
    public String title() {
      return "leave the target package as it was found";
    }

    @Override
    public String phase() {
      return Phases.VENDOR_UPGRADE;
    }

    @Override
    public String detail() {
      return "removes the staged default_master.properties and keystore.init.properties, or puts"
          + " the package's own back";
    }

    // The end state of a rehearsal is the package untouched; undoing this step would mean
    // re-staging files nobody asked to keep. Both compensations it runs are marker-guarded, so
    // running it again converges.
    @Override
    public boolean irreversible() {
      return true;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      StepResult first = keystore.compensate(ctx, out);
      if (!(first instanceof StepResult.Ok)) {
        return first;
      }
      StepResult second = master.compensate(ctx, out);
      if (!(second instanceof StepResult.Ok)) {
        return second;
      }
      Logs.info(rt, ctx, out, this, "target package left as it was");
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return StepResult.ok();
    }
  }
}
