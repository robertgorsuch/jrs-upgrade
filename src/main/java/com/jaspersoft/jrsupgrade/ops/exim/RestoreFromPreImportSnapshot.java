package com.jaspersoft.jrsupgrade.ops.exim;

import com.jaspersoft.jrsupgrade.core.engine.CancellationToken;
import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepFailure;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsupgrade.jrs.api.ImportRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The rollback anchor of the {@code import} phase (spec §9.4). It is the first step of the phase;
 * its {@link #execute} only records that the pre-import snapshot is in place, and its {@link
 * #compensate} is what undoes the phase: it re-imports the snapshot with {@code update=true} by
 * running the same strategy's import steps (precheck, execute, postcheck, in order) inside a nested
 * {@link Context} whose run id carries the {@value #RESTORE_SUFFIX} suffix, so the strategy's
 * run-scoped files (task handles, service markers) never collide with those of the failed import.
 * Invariants: the step declares itself mutating although it writes nothing, because the Runner
 * compensates mutating steps only and this compensation must run whenever any later step of the
 * phase fails; the compensation first deletes the resources the failed import created, judged
 * against the listing {@link RecordRepositoryListing} took just before the import (issue #100,
 * ADR-0031), then re-imports the snapshot, which puts back what the import overwrote; a listing
 * that is missing or cannot be compared leaves the additions behind with a warning saying so; a
 * restore that fails reports the snapshot path as the backup to re-import by hand; a snapshot that
 * is a readable archive with entries but no {@value #INDEX} holds none of the resources the import
 * targets, so its compensation is a logged no-op (issue #41): there is nothing to put back, and the
 * vendor importer throws on such an archive.
 */
final class RestoreFromPreImportSnapshot implements Step {

  static final String ID = "import.snapshot-rollback";
  static final String RESTORE_SUFFIX = "-restore";
  static final String INDEX = "index.xml";

  private final ExportImportStrategy strategy;
  private final Path snapshot;
  private final ImportRequest restore;
  private final String phase;
  private final List<String> roots;

  RestoreFromPreImportSnapshot(
      String phase,
      ExportImportStrategy strategy,
      Path snapshot,
      ImportRequest restore,
      List<String> roots) {
    this.phase = Objects.requireNonNull(phase, "phase");
    this.strategy = Objects.requireNonNull(strategy, "strategy");
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    this.restore = Objects.requireNonNull(restore, "restore");
    this.roots = List.copyOf(Objects.requireNonNull(roots, "roots"));
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Enable snapshot rollback";
  }

  @Override
  public String phase() {
    return phase;
  }

  @Override
  public String detail() {
    return "re-imports " + snapshot + " on failure";
  }

  /**
   * True although nothing is written: see the class comment, this is the phase's rollback anchor.
   */
  @Override
  public boolean mutating() {
    return true;
  }

  @Override
  public CheckResult precheck(Context ctx) {
    try {
      if (!Files.isRegularFile(snapshot) || Files.size(snapshot) <= 0) {
        return CheckResult.fail(
            "pre-import snapshot " + snapshot + " is missing or empty",
            "run the import again; the backup phase writes the snapshot before anything is"
                + " imported");
      }
    } catch (IOException e) {
      return CheckResult.fail(
          "cannot inspect " + snapshot + ": " + e.getMessage(), "check the snapshot directory");
    }
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    EximLogs.info(out, ctx, this, "rollback point: " + snapshot + " (best effort re-import)");
    return StepResult.ok();
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    RecordRepositoryListing.deleteAdditions(ctx, out, this, roots);
    if (holdsNoResources(snapshot)) {
      EximLogs.info(
          out,
          ctx,
          this,
          "nothing to restore: "
              + snapshot
              + " has no "
              + INDEX
              + ", so none of the resources the import targets existed before it");
      return StepResult.ok();
    }
    Context nested =
        new Context(
            ctx.runId() + RESTORE_SUFFIX, ctx.home(), ctx.platform(), ctx.cancel(), ctx.services());
    EximLogs.info(out, ctx, this, "re-importing pre-import snapshot " + snapshot + " (update)");
    for (Step step : strategy.importSteps(restore)) {
      Optional<String> problem = runNested(step, nested, out);
      if (problem.isPresent()) {
        return StepResult.failed(
            new StepFailure.Recoverable(
                "restore of the pre-import snapshot failed at " + step.id() + ": " + problem.get(),
                List.of(snapshot),
                List.of(),
                List.of(snapshot),
                "re-import the snapshot by hand with `jrs-upgrade import "
                    + snapshot
                    + " --update`, then verify the repository"));
      }
    }
    EximLogs.info(out, ctx, this, "pre-import snapshot re-imported from " + snapshot);
    return StepResult.ok();
  }

  /** Runs one strategy step to completion; the message of the first failed check or result. */
  private Optional<String> runNested(Step step, Context nested, EventSink out) {
    CheckResult pre = step.precheck(nested);
    switch (pre) {
      case CheckResult.Pass p -> {}
      case CheckResult.Warn w -> EximLogs.warn(out, nested, step, w.message());
      case CheckResult.Fail f -> {
        return Optional.of("precheck failed: " + f.message() + " (" + f.remediation() + ")");
      }
    }
    StepResult result;
    try {
      result = step.execute(nested, out);
    } catch (CancellationToken.CancelledException e) {
      throw e;
    } catch (RuntimeException e) {
      result = StepResult.failed(StepFailure.recoverable(describe(e), "retry the restore"));
    }
    switch (result) {
      case StepResult.Ok ok -> {}
      case StepResult.Failed f -> {
        return Optional.of(f.failure().cause());
      }
    }
    CheckResult post = step.postcheck(nested);
    return switch (post) {
      case CheckResult.Pass p -> Optional.empty();
      case CheckResult.Warn w -> {
        EximLogs.warn(out, nested, step, w.message());
        yield Optional.empty();
      }
      case CheckResult.Fail f -> Optional.of("postcheck failed: " + f.message());
    };
  }

  /**
   * True only for a readable archive that has entries and no {@value #INDEX}, which is what an
   * export of URIs that do not exist writes (a lone {@code resources/} entry). An archive that
   * cannot be read, or reads as no entries at all, is not judged here: the import reports it.
   */
  static boolean holdsNoResources(Path archive) {
    try (InputStream raw = Files.newInputStream(archive);
        ZipInputStream in = new ZipInputStream(raw, StandardCharsets.UTF_8)) {
      boolean any = false;
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        if (entry.getName().equals(INDEX)) {
          return false;
        }
        any = true;
      }
      return any;
    } catch (IOException | IllegalArgumentException e) {
      return false;
    }
  }

  private static String describe(RuntimeException e) {
    String msg = e.getMessage();
    return msg == null || msg.isBlank()
        ? e.getClass().getSimpleName()
        : e.getClass().getSimpleName() + ": " + msg;
  }
}
