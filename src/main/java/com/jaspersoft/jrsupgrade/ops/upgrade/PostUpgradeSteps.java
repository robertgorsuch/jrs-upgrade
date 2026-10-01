package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.core.secrets.SecretException;
import com.jaspersoft.jrsupgrade.ops.db.JdbcConnector;
import com.jaspersoft.jrsupgrade.ops.db.JdbcException;
import com.jaspersoft.jrsupgrade.ops.db.JdbcSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The vendor's "Additional tasks" after {@code js-upgrade-*} and before the server starts (upgrade
 * guide 10.1 pp.34-36, 47-48, 60-61; review §2.2): empty {@code <tomcat>/work} and {@code
 * <tomcat>/temp}, and clear the repository cache table, whose stale entries otherwise surface as
 * {@code local class incompatible}. Both steps are {@code irreversible()} by design: the caches
 * hold nothing the server cannot regenerate, so there is nothing to snapshot or put back, and a
 * failure to clear them must not roll a finished vendor upgrade back to point B. Both are
 * idempotent: emptying an empty directory and re-sending the two cache statements change nothing.
 */
final class PostUpgradeSteps {

  static final String CLEAR_TOMCAT_CACHES = "clear-tomcat-caches";
  static final String CLEAR_REPOSITORY_CACHE = "clear-repository-cache";

  /** Upgrade guide 10.1 p.35, verbatim; unquoted so each database folds the name its own way. */
  static final String CACHE_UPDATE = "update JIRepositoryCache set item_reference = null";

  static final String CACHE_DELETE = "delete from JIRepositoryCache";

  private PostUpgradeSteps() {}

  /**
   * Empties {@code <tomcat>/work} (compiled JSPs, which the deploy target "should automatically
   * clear ... but double-check") and {@code <tomcat>/temp} ({@code java.io.tmpdir}). The
   * directories themselves stay; a missing one is skipped.
   */
  static final class ClearTomcatCaches implements Step {

    private final UpgradeRuntime rt;
    private final UpgradeInput in;

    ClearTomcatCaches(UpgradeRuntime rt, UpgradeInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    private List<Path> dirs() {
      Path tomcat = in.hostTomcatDir();
      return List.of(tomcat.resolve("work"), tomcat.resolve("temp"));
    }

    @Override
    public String id() {
      return CLEAR_TOMCAT_CACHES;
    }

    @Override
    public String title() {
      return "empty Tomcat's work and temp directories";
    }

    @Override
    public String phase() {
      return Phases.VENDOR_UPGRADE;
    }

    @Override
    public String detail() {
      return dirs().get(0) + ", " + dirs().get(1) + " (contents only; regenerated on start)";
    }

    // Compiled JSPs and temporary files: Tomcat rebuilds them on start, so there is nothing to
    // keep and nothing a rollback could restore (upgrade guide 10.1 p.34).
    @Override
    public boolean irreversible() {
      return true;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      Path tomcat = in.hostTomcatDir();
      return Files.isDirectory(tomcat)
          ? CheckResult.pass()
          : CheckResult.fail(tomcat + " is not a directory", "check server.tomcatDir");
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      for (Path dir : dirs()) {
        ctx.cancel().checkpoint();
        if (!Files.isDirectory(dir)) {
          Logs.info(rt, ctx, out, this, dir + " does not exist; nothing to clear");
          continue;
        }
        long removed = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
          List<Path> entries =
              walk.filter(p -> !p.equals(dir)).sorted(Comparator.reverseOrder()).toList();
          for (Path entry : entries) {
            Files.delete(entry);
            removed++;
          }
        } catch (IOException e) {
          return Failures.recoverable(
              "cannot empty " + dir + ": " + e.getMessage(),
              "stop anything holding files there and run again, or empty it by hand");
        }
        Logs.info(rt, ctx, out, this, "emptied " + dir + " (" + removed + " entries)");
      }
      return StepResult.ok();
    }

    @Override
    public CheckResult postcheck(Context ctx) {
      for (Path dir : dirs()) {
        if (Files.isDirectory(dir)) {
          try (Stream<Path> children = Files.list(dir)) {
            if (children.findAny().isPresent()) {
              return CheckResult.fail(dir + " is not empty", "empty it by hand");
            }
          } catch (IOException e) {
            return CheckResult.fail("cannot list " + dir + ": " + e.getMessage(), "run again");
          }
        }
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      Logs.info(rt, ctx, out, this, "nothing to restore: Tomcat regenerates work and temp");
      return StepResult.ok();
    }
  }

  /**
   * Clears {@code JIRepositoryCache} through the configured database. Best effort by design: with
   * no {@code database} section the step warns and prints the two statements to run by hand, and a
   * JDBC failure is a warning too, because the cache is rebuilt on demand and a finished vendor
   * upgrade must not be rolled back over it.
   */
  static final class ClearRepositoryCache implements Step {

    private final UpgradeRuntime rt;

    ClearRepositoryCache(UpgradeRuntime rt) {
      this.rt = Objects.requireNonNull(rt, "rt");
    }

    @Override
    public String id() {
      return CLEAR_REPOSITORY_CACHE;
    }

    @Override
    public String title() {
      return "clear the repository cache table";
    }

    @Override
    public String phase() {
      return Phases.VENDOR_UPGRADE;
    }

    @Override
    public String detail() {
      return CACHE_UPDATE + "; " + CACHE_DELETE + " (best effort: a failure is a warning)";
    }

    // The table caches compiled report resources and is repopulated on demand; the vendor clears
    // it after every upgrade and never restores it (upgrade guide 10.1 p.35).
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
      Optional<JdbcSettings> settings = JdbcSettings.from(rt.config(), rt.services().platform());
      if (settings.isEmpty()) {
        Logs.warn(
            rt,
            ctx,
            out,
            this,
            "database.type and database.url are not configured; clear the repository cache by"
                + " hand before the first login: "
                + CACHE_UPDATE
                + "; "
                + CACHE_DELETE);
        return StepResult.ok();
      }
      try (JdbcConnector.Session session =
          settings.get().open(rt.jdbc(), rt.services().secrets())) {
        session.executeStatement(CACHE_UPDATE);
        session.executeStatement(CACHE_DELETE);
        Logs.info(rt, ctx, out, this, "repository cache cleared (" + session.product() + ")");
        return StepResult.ok();
      } catch (JdbcException | SecretException e) {
        Logs.warn(
            rt,
            ctx,
            out,
            this,
            "could not clear the repository cache ("
                + e.getMessage()
                + "); run by hand before the first login: "
                + CACHE_UPDATE
                + "; "
                + CACHE_DELETE);
        return StepResult.ok();
      }
    }

    @Override
    public CheckResult postcheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      Logs.info(
          rt, ctx, out, this, "nothing to restore: the repository cache is rebuilt on demand");
      return StepResult.ok();
    }
  }
}
