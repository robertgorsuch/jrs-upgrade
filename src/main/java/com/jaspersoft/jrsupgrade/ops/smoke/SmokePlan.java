package com.jaspersoft.jrsupgrade.ops.smoke;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.PlanFingerprint;
import com.jaspersoft.jrsupgrade.core.engine.PlanSummary;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepFailure;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.api.ServerIdentity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one mutating smoke plan (spec §12.2): create {@code /temp/jrs-upgrade-smoke-<runId>}, upload
 * the bundled minimal JRXML, run it to PDF, delete the folder. Invariants: every step is idempotent
 * (a folder or report that already exists is not an error, a missing one on delete is not either);
 * the folder create and report upload compensate by deleting what they made; the final delete is
 * irreversible only in the sense that it removes what this same plan created, so nothing that
 * pre-existed is lost; all mutations go through the {@link JrsAdapter} found in the run context.
 */
final class SmokePlan {

  static final String PARENT = "/temp";
  static final String REPORT_LABEL = "jrsupgrade_smoke";
  static final String JRXML_RESOURCE = "/smoke/minimal.jrxml";
  static final String PHASE = "smoke";

  private static final Logger LOG = LoggerFactory.getLogger(SmokePlan.class);

  private SmokePlan() {}

  static String folderUri(String runId) {
    return PARENT + "/jrs-upgrade-smoke-" + runId;
  }

  static String reportUri(String runId) {
    return folderUri(runId) + "/" + REPORT_LABEL;
  }

  static Plan build(String runId, ServerIdentity identity) {
    Objects.requireNonNull(runId, "runId");
    String folder = folderUri(runId);
    List<Step> steps =
        List.of(
            new CreateFolder(folder),
            new UploadReport(folder),
            new RunReport(reportUri(runId)),
            new DeleteFolder(folder));
    PlanSummary summary =
        new PlanSummary(
            SmokeOperation.MUTATING_OPERATION,
            identity.baseUrl().toString(),
            List.of(),
            List.of(folder, reportUri(runId)),
            false,
            List.of(),
            Map.of(),
            "rest",
            List.of());
    PlanFingerprint fingerprint =
        PlanFingerprint.of(Map.of("server", identity.fingerprintInput(), "folder", folder));
    return new Plan("smoke-" + runId, steps, summary, fingerprint);
  }

  static JrsAdapter adapter(Context ctx) {
    return ctx.service(JrsAdapter.class);
  }

  private static boolean exists(JrsAdapter adapter, String parent, String uri) {
    try {
      return adapter.listFolder(parent).contains(uri);
    } catch (RuntimeException e) {
      LOG.debug("cannot list {}", parent, e);
      return false;
    }
  }

  private static StepResult deleteQuietly(JrsAdapter adapter, String parent, String uri) {
    if (!exists(adapter, parent, uri)) {
      return StepResult.ok();
    }
    try {
      adapter.deleteResource(uri);
      return StepResult.ok();
    } catch (RuntimeException e) {
      return StepResult.failed(
          StepFailure.recoverable(
              "cannot delete " + uri + ": " + e.getMessage(), "delete " + uri + " by hand"));
    }
  }

  /** Creates the smoke folder; compensation deletes it. */
  record CreateFolder(String folder) implements Step {
    @Override
    public String id() {
      return "smoke-folder-create";
    }

    @Override
    public String title() {
      return "create " + folder;
    }

    @Override
    public String phase() {
      return PHASE;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      JrsAdapter adapter = adapter(ctx);
      if (exists(adapter, PARENT, folder)) {
        return StepResult.ok();
      }
      try {
        adapter.createFolder(folder, "jrs-upgrade smoke");
        return StepResult.ok();
      } catch (RuntimeException e) {
        return StepResult.failed(
            StepFailure.recoverable(
                "cannot create " + folder + ": " + e.getMessage(),
                "check that the login may write under " + PARENT));
      }
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return deleteQuietly(adapter(ctx), PARENT, folder);
    }
  }

  /** Uploads the bundled JRXML into the folder; compensation deletes the report. */
  record UploadReport(String folder) implements Step {
    @Override
    public String id() {
      return "smoke-report-upload";
    }

    @Override
    public String title() {
      return "upload " + REPORT_LABEL + " into " + folder;
    }

    @Override
    public String phase() {
      return PHASE;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return SmokePlan.class.getResource(JRXML_RESOURCE) == null
          ? CheckResult.fail(JRXML_RESOURCE + " missing from the jar", "reinstall jrs-upgrade")
          : CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      JrsAdapter adapter = adapter(ctx);
      String uri = folder + "/" + REPORT_LABEL;
      if (exists(adapter, folder, uri)) {
        return StepResult.ok();
      }
      Path staging = ctx.home().stagingDir(ctx.runId());
      Path jrxml = staging.resolve("minimal.jrxml");
      try {
        Files.createDirectories(staging);
        try (InputStream in = SmokePlan.class.getResourceAsStream(JRXML_RESOURCE)) {
          if (in == null) {
            return StepResult.failed(
                StepFailure.fatal(
                    JRXML_RESOURCE + " missing from the jar", "reinstall jrs-upgrade"));
          }
          Files.copy(in, jrxml, StandardCopyOption.REPLACE_EXISTING);
        }
      } catch (IOException e) {
        return StepResult.failed(
            StepFailure.recoverable(
                "cannot stage the JRXML: " + e.getMessage(), "check " + staging + " is writable"));
      }
      try {
        adapter.uploadJrxmlReport(folder, REPORT_LABEL, jrxml);
        return StepResult.ok();
      } catch (RuntimeException e) {
        return StepResult.failed(
            StepFailure.recoverable(
                "cannot upload " + uri + ": " + e.getMessage(),
                "check that the login may create reports under " + folder));
      }
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return deleteQuietly(adapter(ctx), folder, folder + "/" + REPORT_LABEL);
    }
  }

  /** Runs the uploaded report to PDF; read-only, nothing to compensate. */
  record RunReport(String reportUri) implements Step {
    @Override
    public String id() {
      return "smoke-report-run";
    }

    @Override
    public String title() {
      return "run " + reportUri + " to PDF";
    }

    @Override
    public String phase() {
      return PHASE;
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
      Path target = ctx.home().stagingDir(ctx.runId()).resolve("smoke.pdf");
      try {
        Files.createDirectories(target.getParent());
        adapter(ctx).runReportToPdf(reportUri, target);
        Optional<String> problem = PdfCheck.problem(target);
        if (problem.isPresent()) {
          return StepResult.failed(
              StepFailure.recoverable(
                  reportUri + ": " + problem.get(), "check the server log for the report run"));
        }
        return StepResult.ok();
      } catch (IOException | RuntimeException e) {
        return StepResult.failed(
            StepFailure.recoverable(
                "cannot run " + reportUri + ": " + e.getMessage(),
                "check the server log for the report run"));
      } finally {
        try {
          Files.deleteIfExists(target);
        } catch (IOException e) {
          LOG.debug("cannot delete {}", target, e);
        }
      }
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return StepResult.ok();
    }
  }

  /**
   * Deletes the smoke folder and everything in it. Irreversible by design: the folder holds only
   * what this plan created a moment ago, so there is nothing to restore and re-creating it on
   * rollback would leave the very litter the plan exists to remove.
   */
  record DeleteFolder(String folder) implements Step {
    @Override
    public String id() {
      return "smoke-folder-delete";
    }

    @Override
    public String title() {
      return "delete " + folder;
    }

    @Override
    public String phase() {
      return PHASE;
    }

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
      return deleteQuietly(adapter(ctx), PARENT, folder);
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return StepResult.ok();
    }
  }
}
