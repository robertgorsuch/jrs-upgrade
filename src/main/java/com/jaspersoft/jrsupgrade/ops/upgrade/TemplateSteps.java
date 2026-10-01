package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsupgrade.jrs.vendor.VendorRun;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.semver4j.Semver;

/**
 * Issue #10: an export from before 9.0 carries {@code /public/templates}, and since 9.0 the vendor
 * ships its Ad Hoc Component templates under the same names, so a newdb import silently overwrites
 * them and Ad Hoc reports fall back to legacy templates; the vendor's remedy is {@code js-ant
 * import-minimal-pro} (upgrade guide 10.1 p.92). Invariants: both steps follow a newdb hop to 9.0
 * or later from a source below 9.0 (or an unknown one); {@link CheckAdhocTemplates} only reads the
 * point-B export and the target package's minimal catalog, and names the vendor templates the
 * import overwrote and the customer templates it left alone; {@link RestoreVendorTemplates} runs
 * only when the operator passed {@code --restore-vendor-templates} (never on {@code --yes} alone),
 * once per run, with the server still down.
 */
final class TemplateSteps {

  static final String CHECK_ADHOC_TEMPLATES = "check-adhoc-templates";
  static final String RESTORE_VENDOR_TEMPLATES = "restore-vendor-templates";
  static final String TEMPLATES = "resources/public/templates/";
  static final Semver ADHOC_COMPONENTS_FROM = new Semver("9.0.0");

  private TemplateSteps() {}

  /** True for a hop to 9.0 or later from a version below 9.0, or from an unknown one. */
  static boolean applies(Optional<String> source, String hopVersion) {
    Semver to = Semver.coerce(hopVersion);
    if (to == null || to.isLowerThan(ADHOC_COMPONENTS_FROM)) {
      return false;
    }
    return source.map(Semver::coerce).map(v -> v.isLowerThan(ADHOC_COMPONENTS_FROM)).orElse(true);
  }

  /** The {@code /public/templates} resources an export or catalog holds: name to descriptor. */
  static Map<String, byte[]> templates(Path zip) throws IOException {
    Map<String, byte[]> out = new TreeMap<>();
    try (InputStream in = Files.newInputStream(zip);
        ZipInputStream z = new ZipInputStream(in)) {
      ZipEntry e;
      while ((e = z.getNextEntry()) != null) {
        String name = e.getName();
        if (name.startsWith(TEMPLATES) && name.endsWith(".xml")) {
          String rest = name.substring(TEMPLATES.length());
          if (rest.indexOf('/') < 0) {
            out.put(rest.substring(0, rest.length() - ".xml".length()), z.readAllBytes());
          }
        }
      }
    }
    return out;
  }

  /** The target buildomatic's minimal catalog for {@code edition}, as import-minimal reads it. */
  static Optional<Path> minimalCatalog(Path buildomatic, String edition) throws IOException {
    Path export = buildomatic.resolve("install_resources").resolve("export");
    if (!Files.isDirectory(export)) {
      return Optional.empty();
    }
    try (Stream<Path> walk = Files.walk(export)) {
      List<Path> zips =
          walk.filter(Files::isRegularFile)
              .filter(
                  p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return n.endsWith(".zip") && n.contains("minimal");
                  })
              .sorted()
              .toList();
      return zips.stream()
          .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).contains("-" + edition))
          .findFirst()
          .or(() -> zips.stream().findFirst());
    }
  }

  /** The vendor templates the import overwrote, and the site's own it left alone. */
  record Comparison(List<String> overwritten, List<String> untouched) {}

  static Comparison compare(Map<String, byte[]> exported, Map<String, byte[]> vendor) {
    List<String> overwritten =
        exported.keySet().stream()
            .filter(n -> vendor.containsKey(n) && !Arrays.equals(vendor.get(n), exported.get(n)))
            .toList();
    List<String> untouched =
        exported.keySet().stream().filter(n -> !vendor.containsKey(n)).toList();
    return new Comparison(overwritten, untouched);
  }

  static final class CheckAdhocTemplates implements Step {
    private final UpgradeRuntime rt;
    private final UpgradeInput in;

    CheckAdhocTemplates(UpgradeRuntime rt, UpgradeInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    @Override
    public String id() {
      return in.scoped(CHECK_ADHOC_TEMPLATES);
    }

    @Override
    public String title() {
      return "check the Ad Hoc templates the import overwrote";
    }

    @Override
    public String phase() {
      return Phases.VENDOR_UPGRADE;
    }

    @Override
    public String detail() {
      return "/public/templates of the point-B export against the target's minimal catalog"
          + " (upgrade guide 10.1 p.92); a WARN, not a failure";
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
      Path export = in.snapshots(ctx).resolveFullExport();
      try {
        Optional<Path> catalog =
            in.target().buildomatic().isPresent()
                ? minimalCatalog(in.target().buildomatic().get().dir(), VendorSteps.edition(in))
                : Optional.empty();
        if (catalog.isEmpty()) {
          Logs.warn(
              rt,
              ctx,
              out,
              this,
              "no minimal catalog under the target buildomatic's install_resources/export; check"
                  + " /public/templates by hand after the start: an export from before 9.0"
                  + " overwrites the vendor's Ad Hoc templates of the same names");
          return StepResult.ok();
        }
        Comparison c = compare(templates(export), templates(catalog.get()));
        if (!c.untouched().isEmpty()) {
          Logs.info(
              rt,
              ctx,
              out,
              this,
              "your own templates, left as they are: " + String.join(", ", c.untouched()));
        }
        if (c.overwritten().isEmpty()) {
          Logs.info(rt, ctx, out, this, "no vendor Ad Hoc template was overwritten by the import");
          return StepResult.ok();
        }
        Logs.warn(
            rt,
            ctx,
            out,
            this,
            "the import of "
                + export.getFileName()
                + " overwrote the vendor's Ad Hoc templates "
                + String.join(", ", c.overwritten())
                + " with their pre-9.0 versions, so Ad Hoc reports fall back to legacy templates"
                + (in.options().restoreVendorTemplates()
                    ? "; restore-vendor-templates puts the vendor's back"
                    : "; run js-ant import-minimal-"
                        + VendorSteps.edition(in)
                        + " from "
                        + in.target().dir()
                        + " (upgrade guide 10.1 p.92), or upgrade with"
                        + " --restore-vendor-templates"));
        return StepResult.ok();
      } catch (IOException e) {
        Logs.warn(
            rt,
            ctx,
            out,
            this,
            "cannot compare the templates: " + e.getMessage() + "; check them by hand");
        return StepResult.ok();
      }
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return StepResult.ok();
    }
  }

  static final class RestoreVendorTemplates implements Step {
    private final UpgradeRuntime rt;
    private final UpgradeInput in;

    RestoreVendorTemplates(UpgradeRuntime rt, UpgradeInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    String antTarget() {
      return "import-minimal-" + VendorSteps.edition(in);
    }

    private Path marker(Context ctx) {
      return in.runDir(ctx).resolve(RESTORE_VENDOR_TEMPLATES + ".done");
    }

    @Override
    public String id() {
      return in.scoped(RESTORE_VENDOR_TEMPLATES);
    }

    @Override
    public String title() {
      return "put the vendor's Ad Hoc templates back (js-ant " + antTarget() + ")";
    }

    @Override
    public String phase() {
      return Phases.VENDOR_UPGRADE;
    }

    @Override
    public String detail() {
      return "js-ant "
          + antTarget()
          + " through "
          + in.target().dir()
          + ", once per run, before the server starts (upgrade guide 10.1 p.92)";
    }

    /*
     * Not irreversible, and no compensation of its own: the import changes the database
     * js-upgrade-newdb has just created, which no step can give back; the vendor step's
     * compensation restores point B and "upgrade rollback --restore-database" rebuilds the old
     * database from the point-B export, which undoes this with the rest (ADR-0029).
     */
    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return StepResult.ok();
    }

    @Override
    public CheckResult precheck(Context ctx) {
      Optional<Buildomatic> b = in.target().buildomatic();
      if (b.isEmpty() || b.get().scriptFor(Buildomatic.ANT_SCRIPT).isEmpty()) {
        return CheckResult.fail(
            "no js-ant in the target package " + in.target().dir(),
            "unpack the full distribution; " + antTarget() + " is its js-ant target");
      }
      if (rt.config().vendor().javaHome().isEmpty()) {
        return CheckResult.fail(
            "vendor.javaHome is not set; js-ant needs a JDK", "set vendor.javaHome in config.yaml");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      if (Files.isRegularFile(marker(ctx))) {
        Logs.info(rt, ctx, out, this, "vendor templates already restored in this run; skipping");
        return StepResult.ok();
      }
      Buildomatic b = in.target().buildomatic().orElseThrow();
      VendorRun run =
          rt.tools()
              .ant(
                  b,
                  antTarget(),
                  List.of(),
                  rt.config().vendor().javaHome(),
                  out,
                  Logs.scope(ctx, this));
      return switch (run) {
        case VendorRun.Completed c when c.ok() -> {
          try {
            Files.createDirectories(marker(ctx).getParent());
            Files.writeString(marker(ctx), "done", StandardCharsets.UTF_8);
          } catch (IOException e) {
            Logs.warn(rt, ctx, out, this, "cannot write " + marker(ctx) + ": " + e.getMessage());
          }
          Logs.info(rt, ctx, out, this, "vendor Ad Hoc templates restored");
          yield StepResult.ok();
        }
        case VendorRun.Completed c ->
            Failures.recoverable(
                "js-ant " + antTarget() + " " + c.summary() + ": " + String.join(" | ", c.tail()),
                "read the buildomatic log under " + b.dir() + ", then resume");
        case VendorRun.TimedOut t ->
            Failures.recoverable(
                "js-ant "
                    + antTarget()
                    + " did not finish within "
                    + t.timeout().toMinutes()
                    + " minutes",
                "check for a hung buildomatic process, then resume");
        case VendorRun.NotStarted n -> Failures.recoverable(n.reason(), n.remediation());
      };
    }
  }
}
