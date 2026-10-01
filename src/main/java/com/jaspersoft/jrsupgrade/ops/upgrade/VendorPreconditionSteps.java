package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The read-only preflight step for the vendor preconditions of review §2.5 (issue #108): the
 * licence file for a 10.0+ commercial target, the JDBC driver jar in the target buildomatic for
 * Oracle, SQL Server and DB2, and the password storage settings of the target webapp. Invariants:
 * runs right after {@code verify-target-package}, before anything is stopped or backed up; a FAIL
 * ends the run with exit 2 and names the file to place or copy; a WARN is logged and the run goes
 * on; {@code execute} changes nothing.
 */
final class VendorPreconditionSteps {

  static final String VERIFY_VENDOR_PRECONDITIONS = "verify-vendor-preconditions";

  private VendorPreconditionSteps() {}

  static final class Verify extends PreflightSteps.ReadOnly {

    private final Path userHome;

    Verify(UpgradeRuntime rt, UpgradeInput in, Path userHome) {
      super(rt, in);
      this.userHome = Objects.requireNonNull(userHome, "userHome").toAbsolutePath().normalize();
    }

    @Override
    public String id() {
      return VERIFY_VENDOR_PRECONDITIONS;
    }

    @Override
    public String title() {
      return "verify the vendor's preconditions for " + in.options().toVersion();
    }

    @Override
    public String detail() {
      return VendorPreconditions.LICENSE_FILE
          + " in "
          + userHome
          + " for a 10.0+ target; the JDBC driver jar under the target buildomatic's"
          + " conf_source/db/<dbType>/jdbc for Oracle, SQL Server and DB2; the target webapp's "
          + VendorPreconditions.PASSWORD_STORAGE_CONFIG
          + " against the running one";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      List<String> warnings = new ArrayList<>();
      CheckResult license =
          VendorPreconditions.license(
              in.options().toVersion(),
              in.webappName(),
              userHome,
              in.installDir(),
              in.hostTomcatDir(),
              rt.services().platform().os());
      switch (license) {
        case CheckResult.Fail f -> {
          return f;
        }
        case CheckResult.Warn w -> warnings.add(w.message());
        case CheckResult.Pass p -> {}
      }
      CheckResult driver =
          VendorPreconditions.jdbcDriver(
              in.masterOverrides(),
              in.targetMasterProperties(rt.locator()),
              in.targetBuildomatic().orElse(in.target().dir().resolve(UpgradeInput.BUILDOMATIC)),
              in.installedBuildomatic());
      switch (driver) {
        case CheckResult.Fail f -> {
          return f;
        }
        case CheckResult.Warn w -> warnings.add(w.message());
        case CheckResult.Pass p -> {}
      }
      VendorPreconditions.passwordStorageWarning(
              in.webappDir(), in.target().webappDir(), in.options().migratePasswords())
          .ifPresent(warnings::add);
      return warnings.isEmpty()
          ? CheckResult.pass()
          : CheckResult.warn(String.join(" | ", warnings));
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      Logs.info(rt, ctx, out, this, "vendor preconditions checked; nothing to change");
      return StepResult.ok();
    }
  }
}
