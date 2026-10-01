package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.config.ConfigLoader;
import com.jaspersoft.jrsupgrade.core.config.ConfigWriter;
import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.core.platform.Durability;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps jrs-upgrade's own {@code config.yaml} in step with an upgrade (#69). Invariants: after a
 * successful vendor upgrade {@code server.buildomaticDir} names the target package's buildomatic,
 * which now holds the upgraded server's {@code default_master.properties}, so later exports,
 * imports and upgrades use the new version's tools without the operator editing anything; the file
 * as it was before the upgrade is copied once into the upgrade's protected snapshot set ({@code
 * jrs-upgrade-config.yaml}) and never overwritten, so re-execution keeps the pre-upgrade copy;
 * compensation and {@code upgrade rollback} put that copy back byte for byte; every write goes
 * through a temporary file and an atomic move, so a crash leaves either the old or the new file;
 * only the jrs-upgrade home is touched, never the server; a home without {@code config.yaml}
 * (settings from the environment only) is left alone with a warning naming the value to set.
 */
final class JrsUpgradeConfigSteps {

  static final String POINT_CONFIG_AT_TARGET = "point-config-at-target";
  static final String RESTORE_JRS_UPGRADE_CONFIG = "restore-jrs-upgrade-config";
  static final String SAVED_CONFIG = "jrs-upgrade-config.yaml";
  private static final String AUDIT_POINTED = "upgrade.config";

  private JrsUpgradeConfigSteps() {}

  static Path saved(SnapshotSet set) {
    return set.dir().resolve(SAVED_CONFIG);
  }

  /** Copies {@code source} over {@code target} through a sibling temporary file. */
  static void replace(Path source, Path target) throws IOException {
    Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
    Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
    Durability.sync(tmp);
    Durability.move(
        tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  /** Verify phase, after {@code record-upgrade}: point the configuration at the new buildomatic. */
  static final class PointConfigAtTarget implements Step {
    private final UpgradeRuntime rt;
    private final UpgradeInput in;

    PointConfigAtTarget(UpgradeRuntime rt, UpgradeInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    private Path target() {
      return in.targetBuildomatic()
          .orElse(in.target().dir().resolve(UpgradeInput.BUILDOMATIC))
          .toAbsolutePath()
          .normalize();
    }

    @Override
    public String id() {
      return POINT_CONFIG_AT_TARGET;
    }

    @Override
    public String title() {
      return "point jrs-upgrade at the upgraded buildomatic (server.buildomaticDir)";
    }

    @Override
    public String phase() {
      return Phases.VERIFY;
    }

    @Override
    public String detail() {
      return "server.buildomaticDir -> "
          + target()
          + in.options().tomcatDir().map(t -> "; server.tomcatDir -> " + t).orElse("")
          + "; previous config.yaml kept with the backups";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      Path file = ctx.home().configFile();
      if (!Files.isRegularFile(file)) {
        Logs.warn(
            rt,
            ctx,
            out,
            this,
            "no config.yaml in the jrs-upgrade home; set server.buildomaticDir to " + target());
        return StepResult.ok();
      }
      Path saved = saved(in.snapshots(ctx));
      try {
        if (!Files.isRegularFile(saved)) {
          Files.createDirectories(saved.getParent());
          replace(file, saved);
        }
        ConfigLoader loader = new ConfigLoader();
        Config current = loader.load(file, Map.of(), Map.of());
        Optional<Path> was = current.server().buildomaticDir();
        Optional<Path> newTomcat =
            in.options().tomcatDir().map(p -> p.toAbsolutePath().normalize());
        boolean tomcatDone =
            newTomcat.isEmpty()
                || current
                    .server()
                    .tomcatDir()
                    .map(p -> p.toAbsolutePath().normalize())
                    .equals(newTomcat);
        if (was.map(p -> p.toAbsolutePath().normalize()).equals(Optional.of(target()))
            && tomcatDone) {
          Logs.info(rt, ctx, out, this, "server.buildomaticDir already names " + target());
          return StepResult.ok();
        }
        Config updated = loader.fileWith(file, "server.buildomaticDir", target().toString());
        if (newTomcat.isPresent()) {
          // review §2.1: the upgraded server runs in the new Tomcat from now on
          Path tmpTomcat = file.resolveSibling(file.getFileName() + ".tomcat");
          ConfigWriter.write(updated, tmpTomcat);
          updated = loader.fileWith(tmpTomcat, "server.tomcatDir", newTomcat.get().toString());
          Files.deleteIfExists(tmpTomcat);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".new");
        ConfigWriter.write(updated, tmp);
        Durability.sync(tmp);
        Durability.move(
            tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        rt.store()
            .audit(
                rt.actor(),
                AUDIT_POINTED,
                "server.buildomaticDir "
                    + was.map(Path::toString).orElse("(unset)")
                    + " -> "
                    + target());
        Logs.info(
            rt,
            ctx,
            out,
            this,
            "server.buildomaticDir now "
                + target()
                + " (was "
                + was.map(Path::toString).orElse("not set")
                + "); the previous config.yaml is kept at "
                + saved);
        return StepResult.ok();
      } catch (IOException | RuntimeException e) {
        return Failures.recoverable(
            "cannot update config.yaml: " + Failures.describe(e),
            "set server.buildomaticDir to " + target() + " with: jrs-upgrade config set");
      }
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      Path saved = saved(in.snapshots(ctx));
      if (!Files.isRegularFile(saved)) {
        return StepResult.ok();
      }
      try {
        replace(saved, ctx.home().configFile());
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot restore config.yaml: " + Failures.describe(e),
            "copy " + saved + " to " + ctx.home().configFile());
      }
    }
  }

  /** Rollback plan: put back the configuration the upgrade run saved. */
  static final class RestoreJrsUpgradeConfig implements Step {
    private final RestoreSteps.Input in;

    RestoreJrsUpgradeConfig(RestoreSteps.Input in) {
      this.in = Objects.requireNonNull(in, "in");
    }

    private static Path aside(Context ctx) {
      return ctx.home().runDir(ctx.runId()).resolve("aside").resolve("config.yaml");
    }

    @Override
    public String id() {
      return RESTORE_JRS_UPGRADE_CONFIG;
    }

    @Override
    public String title() {
      return "restore jrs-upgrade's config.yaml as it was before the upgrade";
    }

    @Override
    public String phase() {
      return Phases.ROLLBACK;
    }

    @Override
    public String detail() {
      return saved(in.set()) + " -> config.yaml (none for upgrades made before 1.6.0)";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      Path saved = saved(in.set());
      Path file = ctx.home().configFile();
      if (!Files.isRegularFile(saved)) {
        return StepResult.ok();
      }
      try {
        if (Files.isRegularFile(file) && !Files.isRegularFile(aside(ctx))) {
          Files.createDirectories(aside(ctx).getParent());
          replace(file, aside(ctx));
        }
        replace(saved, file);
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot restore config.yaml: " + Failures.describe(e), "copy " + saved + " to " + file);
      }
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      if (!Files.isRegularFile(aside(ctx))) {
        return StepResult.ok();
      }
      try {
        replace(aside(ctx), ctx.home().configFile());
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot put config.yaml back: " + Failures.describe(e),
            "copy " + aside(ctx) + " to " + ctx.home().configFile());
      }
    }
  }
}
