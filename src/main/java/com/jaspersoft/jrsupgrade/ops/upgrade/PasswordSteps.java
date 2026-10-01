package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsupgrade.jrs.vendor.VendorRun;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The vendor's password migration (installation guide 10.1 pp.194-199): {@code js-ant
 * migrate-passwords-dry-run}, then {@code js-ant migrate-passwords}, through the target buildomatic
 * after the vendor run, while the server is still down (issue #108). Invariants: runs only on a
 * samedb upgrade to 10.1 or later and only when the operator asked with {@code
 * --migrate-passwords}; the dry run goes first and a failed dry run changes nothing; idempotent
 * through a once-per-run marker and because the vendor utility skips users already in the modern
 * format; {@code irreversible()} because the guide says the only way back is a database restore,
 * which is what {@code --db-backup-confirmed} vouched for; a failure is recoverable and names the
 * buildomatic log.
 */
final class PasswordSteps {

  static final String MIGRATE_PASSWORDS = "migrate-passwords";
  static final String DRY_RUN_TARGET = "migrate-passwords-dry-run";
  static final String MIGRATE_TARGET = "migrate-passwords";

  private PasswordSteps() {}

  static final class MigratePasswords implements Step {

    private final UpgradeRuntime rt;
    private final UpgradeInput in;

    MigratePasswords(UpgradeRuntime rt, UpgradeInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    @Override
    public String id() {
      return MIGRATE_PASSWORDS;
    }

    @Override
    public String title() {
      return "migrate stored passwords to the modern format (js-ant " + MIGRATE_TARGET + ")";
    }

    @Override
    public String phase() {
      return Phases.VENDOR_UPGRADE;
    }

    @Override
    public String detail() {
      return "js-ant "
          + DRY_RUN_TARGET
          + ", then js-ant "
          + MIGRATE_TARGET
          + ", through the target buildomatic; reads WEB-INF/"
          + VendorPreconditions.PASSWORD_STORAGE_CONFIG
          + " of the deployed webapp";
    }

    // installation guide 10.1 p.195: "the only way to undo this migration and return to the old
    // format is to restore your database from a backup", the backup --db-backup-confirmed vouches
    // for; nothing in the files changes
    @Override
    public boolean irreversible() {
      return true;
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      // nothing to put back; see irreversible()
      return StepResult.ok();
    }

    private Path marker(Context ctx) {
      return ctx.home().runDir(ctx.runId()).resolve(id() + ".done");
    }

    @Override
    public CheckResult precheck(Context ctx) {
      Optional<Buildomatic> b = in.target().buildomatic();
      if (b.isEmpty() || b.get().scriptFor(Buildomatic.ANT_SCRIPT).isEmpty()) {
        return CheckResult.fail(
            "no js-ant in the target package " + in.target().dir(),
            "unpack the full distribution; the password migration is its js-ant target");
      }
      if (rt.config().vendor().javaHome().isEmpty()) {
        return CheckResult.fail(
            "vendor.javaHome is not set; js-ant needs a JDK", "set vendor.javaHome in config.yaml");
      }
      Optional<Path> config =
          in.target()
              .webappDir()
              .map(d -> d.resolve("WEB-INF").resolve(VendorPreconditions.PASSWORD_STORAGE_CONFIG));
      if (config.isPresent() && !Files.isRegularFile(config.get())) {
        return CheckResult.fail(
            "no " + config.get() + "; the migration utility reads its settings from that file",
            "upgrade to a version that ships it (10.1 or later) or drop --migrate-passwords");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      if (Files.isRegularFile(marker(ctx))) {
        Logs.info(rt, ctx, out, this, "passwords already migrated in this run; skipping");
        return StepResult.ok();
      }
      Buildomatic b = in.target().buildomatic().orElseThrow();
      Optional<StepResult> dryRun = run(ctx, out, b, DRY_RUN_TARGET);
      if (dryRun.isPresent()) {
        return dryRun.get();
      }
      Optional<StepResult> migration = run(ctx, out, b, MIGRATE_TARGET);
      if (migration.isPresent()) {
        return migration.get();
      }
      try {
        Files.createDirectories(marker(ctx).getParent());
        Files.writeString(marker(ctx), "done", StandardCharsets.UTF_8);
      } catch (IOException e) {
        Logs.warn(rt, ctx, out, this, "cannot write " + marker(ctx) + ": " + e.getMessage());
      }
      Logs.info(rt, ctx, out, this, "stored passwords migrated to the modern format");
      return StepResult.ok();
    }

    /** Runs one js-ant target; empty when it succeeded, else the failure to return. */
    private Optional<StepResult> run(Context ctx, EventSink out, Buildomatic b, String target) {
      VendorRun run =
          rt.tools()
              .ant(
                  b,
                  target,
                  List.of(),
                  rt.config().vendor().javaHome(),
                  out,
                  Logs.scope(ctx, this));
      return switch (run) {
        case VendorRun.Completed c ->
            // a zero exit with no BUILD FAILED banner; the vendor wrappers stay silent on success
            c.ok()
                ? Optional.empty()
                : Optional.of(
                    Failures.recoverable(
                        "js-ant "
                            + target
                            + " "
                            + c.summary()
                            + ": "
                            + String.join(" | ", c.tail()),
                        "read the buildomatic log under "
                            + b.dir()
                            + "; js-ant "
                            + DRY_RUN_TARGET
                            + " shows what is left, and js-ant "
                            + MIGRATE_TARGET
                            + " can be run again (users already migrated are skipped)"));
        case VendorRun.TimedOut t ->
            Optional.of(
                Failures.recoverable(
                    "js-ant "
                        + target
                        + " did not finish within "
                        + t.timeout().toMinutes()
                        + " minutes",
                    "check for a hung buildomatic process, then run again"));
        case VendorRun.NotStarted n ->
            Optional.of(Failures.recoverable(n.reason(), n.remediation()));
      };
    }
  }
}
