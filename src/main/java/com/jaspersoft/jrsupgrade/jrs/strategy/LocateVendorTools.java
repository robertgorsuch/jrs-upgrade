package com.jaspersoft.jrsupgrade.jrs.strategy;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.jrs.vendor.Buildomatic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Verifies the vendor tools before the service is touched (spec §7.4): {@code server.installDir} is
 * set, {@code buildomatic/} is there with the expected scripts, and {@code vendor.javaHome} points
 * at a directory. Non-mutating; everything is in the precheck so a broken installation fails the
 * plan with exit code 2 and nothing changed.
 */
final class LocateVendorTools implements Step {

  private final String phase;
  private final List<String> requiredScripts;
  private final VendorAccess vendor;

  LocateVendorTools(String phase, List<String> requiredScripts, VendorAccess vendor) {
    this.phase = Objects.requireNonNull(phase, "phase");
    this.requiredScripts = List.copyOf(requiredScripts);
    this.vendor = Objects.requireNonNull(vendor, "vendor");
  }

  static String idFor(String phase) {
    return phase + ".locate-vendor-tools";
  }

  @Override
  public String id() {
    return idFor(phase);
  }

  @Override
  public String title() {
    return "Locate vendor tools";
  }

  @Override
  public String phase() {
    return phase;
  }

  @Override
  public String detail() {
    return String.join(", ", requiredScripts);
  }

  @Override
  public boolean mutating() {
    return false;
  }

  @Override
  public CheckResult precheck(Context ctx) {
    Config config = ctx.service(Config.class);
    Optional<Path> installDir = config.server().installDir();
    if (installDir.isEmpty()) {
      return CheckResult.fail(
          "server.installDir is not set",
          "run jrs-upgrade init or set server.installDir to the JasperReports Server installation root");
    }
    Optional<Buildomatic> b = vendor.locate(ctx);
    if (b.isEmpty()) {
      return CheckResult.fail(
          "no buildomatic directory under " + installDir.get(),
          "check server.installDir; the vendor scripts live in <installDir>/buildomatic");
    }
    for (String script : requiredScripts) {
      if (b.get().scriptFor(script).isEmpty()) {
        return CheckResult.fail(
            "vendor script " + script + " not found in " + b.get().dir(),
            "check the installation is complete and matches the host operating system");
      }
    }
    Optional<Path> javaHome = config.vendor().javaHome();
    if (javaHome.isEmpty()) {
      return CheckResult.fail(
          "vendor.javaHome is not set; buildomatic needs a JDK",
          "set vendor.javaHome in config.yaml to the JDK the vendor scripts should use");
    }
    if (!Files.isDirectory(javaHome.get())) {
      return CheckResult.fail(
          "vendor.javaHome " + javaHome.get() + " is not a directory",
          "point vendor.javaHome at an installed JDK");
    }
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    vendor
        .locate(ctx)
        .ifPresent(b -> Logs.info(out, ctx, this, "using vendor tools in " + b.dir()));
    return StepResult.ok();
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    return StepResult.ok();
  }
}
