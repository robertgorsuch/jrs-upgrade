package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.compat.CompatMatrix;
import com.jaspersoft.jrsupgrade.core.compat.UnsupportedVersionException;
import com.jaspersoft.jrsupgrade.core.config.ConfigException;
import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.Event;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.core.platform.DiskSpace;
import com.jaspersoft.jrsupgrade.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsupgrade.jrs.api.ServerIdentity;
import com.jaspersoft.jrsupgrade.jrs.rest.RestException;
import com.jaspersoft.jrsupgrade.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsupgrade.jrs.vendor.VendorJava;
import com.jaspersoft.jrsupgrade.ops.ReportItem;
import com.jaspersoft.jrsupgrade.ops.TomcatVersion;
import com.jaspersoft.jrsupgrade.ops.doctor.DoctorOperation;
import com.jaspersoft.jrsupgrade.ops.doctor.DoctorOptions;
import com.jaspersoft.jrsupgrade.ops.doctor.DoctorReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Phase A of spec §10.2: doctor, target package verification and the database backup gate, which
 * both modes pass through (ADR-0012). Invariants: none of these steps mutates anything ({@code
 * mutating() == false}); a failing condition is reported from {@code precheck}, so the run ends
 * with exit code 2 and the "nothing changed" outcome; {@code execute} only logs what was verified.
 */
final class PreflightSteps {

  static final String DOCTOR = "doctor";
  static final String VERIFY_TARGET_PACKAGE = "verify-target-package";
  static final String CONFIRM_DB_BACKUP = "confirm-db-backup";
  static final String AUDIT_DB_BACKUP_CONFIRMED = "upgrade.db-backup-confirmed";

  /**
   * Gate message of spec §10.1 (ADR-0012); the CLI prints the same sentence when the flag is
   * missing.
   */
  static final String DB_BACKUP_GATE =
      "samedb migrates the repository database's schema in place; an export cannot undo that, so"
          + " back up the database yourself and pass --db-backup-confirmed";

  /** The same sentence under the name the field-test-2 plan gives it (ADR-0029). */
  static final String DB_BACKUP_GATE_SAMEDB = DB_BACKUP_GATE;

  /**
   * Doctor items whose FAIL is judged against the current server, not the target: the compat and
   * vendor-java checks are repeated against the target version by verify-target-package.
   */
  static final Set<String> JUDGED_AGAINST_TARGET = Set.of(DoctorReport.COMPAT, "vendor-java");

  /** Doctor items that, inside a run, report the run's own pending row and lock. */
  static final Set<String> SELF_REFERENTIAL = Set.of("runs", "lock");

  /** Doctor items an upgrade needs as PASS, not SKIP: the vendor run and the export log in. */
  static final Set<String> REQUIRED_PASS = Set.of("auth");

  /** Room kept for the run's staging and markers under the home beyond the archives. */
  static final long STAGING_HEADROOM_BYTES = 512L << 20;

  private PreflightSteps() {}

  /** Read-only step skeleton: nothing to compensate. */
  abstract static class ReadOnly implements Step {
    final UpgradeRuntime rt;
    final UpgradeInput in;

    ReadOnly(UpgradeRuntime rt, UpgradeInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    @Override
    public String phase() {
      return Phases.PREFLIGHT;
    }

    @Override
    public boolean mutating() {
      return false;
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return StepResult.ok();
    }
  }

  static final class Doctor extends ReadOnly {
    private Optional<DoctorReport> last = Optional.empty();

    Doctor(UpgradeRuntime rt, UpgradeInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return DOCTOR;
    }

    @Override
    public String title() {
      return "run doctor against the current server";
    }

    @Override
    public String detail() {
      return "every check must pass; compat and vendor-java are re-judged against the target";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      DoctorReport report;
      try {
        report = new DoctorOperation(rt.services()).run(DoctorOptions.DEFAULT);
      } catch (RuntimeException e) {
        return CheckResult.fail(
            "doctor could not run: " + Failures.describe(e),
            "run jrs-upgrade doctor and fix it first");
      }
      last = Optional.of(report);
      // doctor itself runs without the admin password (field test 2, D1); an upgrade cannot, so a
      // login the doctor skipped is a failure here
      for (ReportItem item : report.items()) {
        if (REQUIRED_PASS.contains(item.name()) && item.status() == ReportItem.Status.SKIP) {
          return CheckResult.fail(
              "doctor could not log in: " + item.detail(),
              item.remediation().isEmpty()
                  ? "make the admin password available and run jrs-upgrade doctor"
                  : item.remediation());
        }
      }
      List<String> failing = new ArrayList<>();
      for (ReportItem item : report.items()) {
        if (item.status() == ReportItem.Status.FAIL
            && !JUDGED_AGAINST_TARGET.contains(item.name())
            && !aboutThisRun(item, ctx)) {
          failing.add(item.name() + ": " + item.detail());
        }
      }
      if (!failing.isEmpty()) {
        return CheckResult.fail(
            "doctor reports " + failing.size() + " failing check(s): " + String.join("; ", failing),
            "run jrs-upgrade doctor and fix every FAIL before upgrading");
      }
      return CheckResult.pass();
    }

    /**
     * The doctor runs inside this very run: its "runs" and "lock" checks see the current run as
     * pending and the lock as held by it. Those two findings are about us, not about the host.
     */
    private static boolean aboutThisRun(ReportItem item, Context ctx) {
      return SELF_REFERENTIAL.contains(item.name()) && item.detail().contains(ctx.runId());
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      DoctorReport report =
          last.orElseGet(() -> new DoctorOperation(rt.services()).run(DoctorOptions.DEFAULT));
      for (ReportItem item : report.items()) {
        Event.Log.Level level =
            switch (item.status()) {
              case PASS, SKIP -> Event.Log.Level.INFO;
              case WARN -> Event.Log.Level.WARN;
              case FAIL -> Event.Log.Level.WARN;
            };
        String suffix =
            item.status() == ReportItem.Status.FAIL
                ? " (judged against the target by " + VERIFY_TARGET_PACKAGE + ")"
                : "";
        Logs.emit(
            rt,
            ctx,
            out,
            this,
            level,
            item.status() + " " + item.name() + ": " + item.detail() + suffix);
      }
      Logs.info(rt, ctx, out, this, "doctor: " + report.counts().summary());
      return StepResult.ok();
    }
  }

  static final class VerifyTargetPackage extends ReadOnly {

    VerifyTargetPackage(UpgradeRuntime rt, UpgradeInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return in.scoped(VERIFY_TARGET_PACKAGE);
    }

    @Override
    public String title() {
      return "verify the target package for " + in.options().toVersion();
    }

    @Override
    public String detail() {
      return in.target().dir()
          + ": vendor scripts, webapp, default_master.properties keeps the installation type (and"
          + " names dbVersion for Oracle from 10.1), upgrade path in the compat matrix,"
          + " vendor.javaHome, host Tomcat";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      List<String> problems = in.target().problems(rt.locator().scriptExtension());
      if (!problems.isEmpty()) {
        return CheckResult.fail(
            "target package is not usable: " + String.join("; ", problems),
            "point --package at the unpacked JasperReports Server distribution for this OS");
      }
      String to = in.options().toVersion();
      Optional<String> discovered = in.target().discoveredVersion();
      if (discovered.isPresent() && !discovered.get().equals(to)) {
        return CheckResult.fail(
            "the package names version " + discovered.get() + " but --to says " + to,
            "pass --to " + discovered.get() + " or point --package at the right distribution");
      }
      // field test 2, U3: the whole run's backups must fit before anything starts; the tester's
      // 800 MB /home held the jrs-upgrade home and the upgrade died part-way
      Optional<CheckResult> space = spaceProblem();
      if (space.isPresent()) {
        return space.get();
      }
      // review §1.3: what write-master-properties will stage must keep the installation type
      // (upgrade guide 10.1 p.14) and, for Oracle from 10.1 on, name dbVersion (p.43)
      Map<String, String> targetMaster = in.targetMasterProperties(rt.locator());
      Optional<String> crossing =
          MasterInvariants.installTypeProblem(in.masterOverrides(), targetMaster);
      if (crossing.isPresent()) {
        return CheckResult.fail(
            crossing.get(),
            "make installType and the audit.* keys in the target buildomatic's"
                + " default_master.properties match the installed ones, or remove them there");
      }
      Optional<String> dbVersion =
          MasterInvariants.dbVersionProblem(in.masterOverrides(), targetMaster, to);
      if (dbVersion.isPresent()) {
        return CheckResult.fail(
            dbVersion.get(),
            "add dbVersion=<your Oracle version> to "
                + in.installedBuildomatic().resolve(Buildomatic.MASTER_PROPERTIES)
                + " (it is carried over) or to the target buildomatic's copy");
      }
      String current;
      try {
        current = rt.identity().version();
      } catch (JrsUnreachableException | RestException | ConfigException e) {
        return CheckResult.fail(
            "server unreachable, current version unknown: " + e.getMessage(),
            "start the server; the upgrade path is checked against its reported version");
      }
      // a later hop of a route starts from the version the hop before it reached (issue #1)
      String from = in.hop().from().orElse(current);
      Optional<String> pathProblem =
          UpgradePaths.problem(rt.services().matrix(), from, to, in.options().mode());
      if (pathProblem.isPresent()) {
        return CheckResult.fail(
            pathProblem.get(),
            "choose a supported target version or mode; see the compat matrix in the operator"
                + " guide");
      }
      Set<Integer> allowed;
      try {
        allowed = rt.services().matrix().javaRequiredFor(to);
      } catch (UnsupportedVersionException e) {
        return CheckResult.fail(
            "no compatibility matrix entry for " + to, "choose a supported target version");
      }
      String required = CompatMatrix.describeJava(allowed);
      Optional<Path> javaHome = rt.config().vendor().javaHome();
      if (javaHome.isEmpty()) {
        return CheckResult.fail(
            "vendor.javaHome is not set; the vendor upgrade scripts need a " + required + " JDK",
            "set vendor.javaHome in config.yaml to a " + required + " JDK");
      }
      if (!Files.isDirectory(javaHome.get())) {
        return CheckResult.fail(
            "vendor.javaHome " + javaHome.get() + " is not a directory",
            "point vendor.javaHome at an installed " + required + " JDK");
      }
      Optional<Integer> found;
      try {
        found = VendorJava.detect(javaHome.get(), rt.services().platform().processes());
      } catch (RuntimeException e) {
        found = Optional.empty();
      }
      if (found.isEmpty()) {
        return CheckResult.fail(
            "cannot determine the Java version of vendor.javaHome " + javaHome.get(),
            "point vendor.javaHome at a working " + required + " JDK");
      }
      if (!allowed.contains(found.get())) {
        return CheckResult.fail(
            "vendor.javaHome "
                + javaHome.get()
                + " is Java "
                + found.get()
                + "; JasperReports Server "
                + to
                + " needs "
                + required,
            "set vendor.javaHome to a " + required + " JDK");
      }
      if (in.transit()) {
        // ADR-0002: a transit hop deploys nothing, so no Tomcat ever hosts its version
        return CheckResult.pass();
      }
      // review §2.1: 10.0 moved to Jakarta EE, so the Tomcat that will host the target must be
      // one the platform sheet certifies for it
      Path host = in.hostTomcatDir();
      if (in.options().tomcatDir().isPresent()) {
        if (!Files.isDirectory(host.resolve("webapps"))) {
          return CheckResult.fail(
              "--tomcat-dir " + host + " has no webapps directory",
              "point --tomcat-dir at an unpacked Apache Tomcat");
        }
        if (host.equals(in.tomcatDir().toAbsolutePath().normalize())) {
          return CheckResult.fail(
              "--tomcat-dir names the Tomcat the server already runs in",
              "leave --tomcat-dir out, or point it at the new Tomcat");
        }
      }
      Optional<String> tomcat = TomcatVersion.detect(host);
      if (tomcat.isPresent() && !rt.services().matrix().tomcatSupported(to, tomcat.get())) {
        return CheckResult.fail(
            "Tomcat "
                + tomcat.get()
                + " at "
                + host
                + " is not certified for JasperReports Server "
                + to
                + " (needs "
                + DefaultUpgradeOperations.tomcatRanges(rt.services().matrix(), to)
                + ")",
            "install a certified Tomcat and pass --tomcat-dir <its directory>");
      }
      return CheckResult.pass();
    }

    /**
     * The run's need under the jrs-upgrade home: the webapp and buildomatic archives, a full export
     * (its size is unknown before it is taken, so the larger of 1 GB and the webapp tree stands
     * in), and headroom for the run's staging; empty when the volume holds it.
     */
    private Optional<CheckResult> spaceProblem() {
      long trees;
      try {
        trees = DiskSpace.treeBytes(in.webappDir());
        if (Files.isDirectory(in.installedBuildomatic())) {
          trees += DiskSpace.treeBytes(in.installedBuildomatic());
        }
      } catch (IOException e) {
        return Optional.of(
            CheckResult.fail(
                "cannot size the trees to back up: " + e.getMessage(),
                "check read access under " + in.webappDir()));
      }
      long export = BackupSteps.exportEstimate(trees);
      Path under = rt.home().snapshots();
      List<DiskSpace.Need> needs =
          List.of(
              new DiskSpace.Need("webapp and buildomatic backup", under, trees),
              new DiskSpace.Need("full export (estimate)", under, export),
              new DiskSpace.Need("staging headroom", under, STAGING_HEADROOM_BYTES));
      List<String> problems = DiskSpace.problems(rt.files(), needs);
      if (problems.isEmpty()) {
        return Optional.empty();
      }
      long total = trees + export + STAGING_HEADROOM_BYTES + DiskSpace.MARGIN_BYTES;
      String free;
      try {
        free = DiskSpace.human(rt.files().freeSpaceBytes(under));
      } catch (IOException e) {
        free = "an unknown amount";
      }
      return Optional.of(
          CheckResult.fail(
              "the upgrade needs about "
                  + DiskSpace.human(total)
                  + " free under "
                  + rt.home().root()
                  + " for the backups and the full export; "
                  + free
                  + " is free",
              "free space there, prune old runs with jrs-upgrade runs prune, or run with --home <dir>"
                  + " or JRS_UPGRADE_HOME pointing at a directory on a larger volume"));
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      TargetPackage t = in.target();
      Logs.info(
          rt,
          ctx,
          out,
          this,
          "target "
              + t.dir()
              + ": buildomatic "
              + t.buildomatic().map(b -> b.dir().toString()).orElse("-")
              + ", webapp "
              + t.webappDir()
                  .map(Path::toString)
                  .or(() -> t.warFile().map(Path::toString))
                  .orElse("-")
              + ", version "
              + t.discoveredVersion().orElse(in.options().toVersion() + " (from --to)"));
      return StepResult.ok();
    }
  }

  static final class ConfirmDbBackup extends ReadOnly {

    ConfirmDbBackup(UpgradeRuntime rt, UpgradeInput in) {
      super(rt, in);
    }

    @Override
    public String id() {
      return CONFIRM_DB_BACKUP;
    }

    @Override
    public String title() {
      return "confirm the operator backed up the repository database";
    }

    @Override
    public String detail() {
      return "samedb only: requires --db-backup-confirmed (audited; ADR-0012, ADR-0029); newdb's"
          + " own full export is what its rollback rebuilds the database from";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (!in.options().dbBackupConfirmed()) {
        return CheckResult.fail(DB_BACKUP_GATE, "re-run with --db-backup-confirmed");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      rt.store()
          .audit(
              rt.actor(),
              AUDIT_DB_BACKUP_CONFIRMED,
              in.options().mode().vendorSuffix()
                  + " upgrade to "
                  + in.options().toVersion()
                  + " in run "
                  + ctx.runId()
                  + "; the operator confirmed a database backup exists");
      Logs.info(rt, ctx, out, this, "database backup confirmed by the operator (audited)");
      return StepResult.ok();
    }
  }

  static ServerIdentity targetIdentity(ServerIdentity current, String toVersion) {
    return new ServerIdentity(
        current.baseUrl(),
        toVersion,
        current.edition(),
        current.tenancy(),
        current.features(),
        current.build(),
        current.dateFormat());
  }
}
