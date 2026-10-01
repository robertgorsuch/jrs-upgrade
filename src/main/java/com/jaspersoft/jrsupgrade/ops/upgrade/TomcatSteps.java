package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.core.platform.Trees;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The vendor's step for a new Tomcat generation (upgrade guide 10.1 p.50, 10.0 pp.28-52; review
 * §2.1): "Copy ../webapps/jasperserver-pro directory from Tomcat 9.0 to Tomcat 11.0.x folder"
 * before running {@code js-upgrade-*}, whose {@code appServerDir} then names the new Tomcat.
 * Invariants: the old Tomcat's webapp is never touched (point B restores it); the copy exists only
 * while this run owns it, recorded by a marker in the run directory, so a re-execution replaces a
 * partial copy and compensation removes the whole of it; a webapp already under the new Tomcat that
 * this run did not put there is refused rather than overwritten.
 */
final class TomcatSteps {

  static final String COPY_WEBAPP = "copy-webapp-to-tomcat";

  private TomcatSteps() {}

  static final class CopyWebappToTomcat implements Step {

    static final String MARKER = COPY_WEBAPP + ".copied";

    private final UpgradeRuntime rt;
    private final UpgradeInput in;

    CopyWebappToTomcat(UpgradeRuntime rt, UpgradeInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    private Path source() {
      return in.webappDir();
    }

    private Path target() {
      return in.hostTomcatDir().resolve("webapps").resolve(in.webappName());
    }

    private Path marker(Context ctx) {
      return ctx.home().runDir(ctx.runId()).resolve(MARKER);
    }

    @Override
    public String id() {
      return COPY_WEBAPP;
    }

    @Override
    public String title() {
      return "copy the webapp into the new Tomcat";
    }

    @Override
    public String phase() {
      return Phases.VENDOR_UPGRADE;
    }

    @Override
    public String detail() {
      return source() + " -> " + target() + " (the old Tomcat keeps its copy)";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (!Files.isDirectory(source())) {
        return CheckResult.fail(source() + " is not a directory", "check server.tomcatDir");
      }
      Path webapps = in.hostTomcatDir().resolve("webapps");
      if (!Files.isDirectory(webapps)) {
        return CheckResult.fail(
            webapps + " does not exist", "point --tomcat-dir at an unpacked Apache Tomcat");
      }
      if (Files.exists(target()) && !Files.isRegularFile(marker(ctx))) {
        return CheckResult.fail(
            target() + " already exists and this run did not put it there",
            "remove it, or point --tomcat-dir at a Tomcat without a "
                + in.webappName()
                + " webapp");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      Path target = target();
      try {
        if (Files.exists(target)) {
          if (!Files.isRegularFile(marker(ctx))) {
            return Failures.recoverable(
                target + " already exists and this run did not put it there",
                "remove it and run again");
          }
          Logs.info(rt, ctx, out, this, "replacing the partial copy at " + target);
          Trees.deleteRecursively(target);
        }
        Files.createDirectories(marker(ctx).getParent());
        Files.writeString(marker(ctx), target.toString(), StandardCharsets.UTF_8);
        long files = copyTree(source(), target, ctx);
        Logs.info(rt, ctx, out, this, files + " file(s) copied to " + target);
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot copy " + source() + " to " + target + ": " + e.getMessage(),
            "check free space and permissions under " + in.hostTomcatDir());
      }
    }

    private long copyTree(Path from, Path to, Context ctx) throws IOException {
      long files = 0;
      try (Stream<Path> walk = Files.walk(from)) {
        List<Path> entries = walk.toList();
        for (Path entry : entries) {
          ctx.cancel().checkpoint();
          Path dest = to.resolve(from.relativize(entry).toString());
          if (Files.isDirectory(entry)) {
            Files.createDirectories(dest);
          } else {
            Files.createDirectories(dest.getParent());
            Files.copy(entry, dest, StandardCopyOption.REPLACE_EXISTING);
            files++;
          }
        }
      }
      return files;
    }

    @Override
    public CheckResult postcheck(Context ctx) {
      return Files.isDirectory(target().resolve("WEB-INF"))
          ? CheckResult.pass()
          : CheckResult.fail(target() + " has no WEB-INF after the copy", "run again");
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      try {
        if (Files.isRegularFile(marker(ctx))) {
          if (Files.exists(target())) {
            Trees.deleteRecursively(target());
            Logs.info(rt, ctx, out, this, "removed " + target());
          }
          Files.deleteIfExists(marker(ctx));
        } else {
          Logs.info(rt, ctx, out, this, "nothing copied by this run; leaving " + target());
        }
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot remove " + target() + ": " + e.getMessage(), "remove it by hand");
      }
    }
  }
}
