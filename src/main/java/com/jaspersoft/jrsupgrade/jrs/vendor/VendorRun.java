package com.jaspersoft.jrsupgrade.jrs.vendor;

import java.time.Duration;
import java.util.List;

/**
 * Outcome of one vendor-tool invocation (spec §7.4). Invariant: {@code tail} holds the last few
 * already-redacted output lines so a failure message can quote the tool without re-reading a log;
 * {@link NotStarted} means the process was never launched (missing script, missing {@code
 * vendor.javaHome}) and therefore nothing on disk changed; {@link Completed#reported} carries what
 * Ant said and {@link Completed#processing} what the export/import command said, because a
 * buildomatic wrapper's exit code cannot be trusted on its own.
 */
public sealed interface VendorRun
    permits VendorRun.Completed, VendorRun.TimedOut, VendorRun.NotStarted {

  /**
   * The process exited on its own. A zero exit code is not enough on its own: {@code js-import.sh}
   * in 10.0.0 runs {@code js-ant validate-database validate-keystore} and guards the import with a
   * bare {@code if [ $? -eq 0 ]} that has no else branch, so a validation failure imports nothing
   * and still exits 0. {@link #ok()} therefore also requires that the transcript did not report a
   * failure, and {@link #processed()} that an export or import command finished its work.
   */
  record Completed(
      int exitCode, Duration elapsed, List<String> tail, Reported reported, Processing processing)
      implements VendorRun {
    public Completed {
      tail = List.copyOf(tail);
    }

    /**
     * No failure evidence: a zero exit, no failed build, and no export/import command that failed
     * or stopped part-way. A run that never starts such a command, an Ant target, can be ok.
     */
    public boolean ok() {
      return exitCode == 0
          && reported != Reported.FAILED
          && reported != Reported.CREATED_KEYSTORE
          && processing != Processing.FAILED
          && processing != Processing.UNFINISHED;
    }

    /**
     * {@link #ok()} plus the command's own {@code Done}: the evidence an export or import needs,
     * because the wrappers exit 0 when the command threw (issue #40).
     */
    public boolean processed() {
      return ok() && processing == Processing.COMPLETED;
    }

    /** Why this run is not {@link #processed()}, for failure messages; "completed" when it is. */
    public String summary() {
      if (exitCode != 0) {
        return "exited with " + exitCode;
      }
      if (reported == Reported.CREATED_KEYSTORE) {
        return "created a new keystore because it found none to reuse (buildomatic setup.xml"
            + " create-ks); the repository's passwords are now encrypted with a key no other copy"
            + " of the server has";
      }
      if (reported == Reported.FAILED) {
        return "reported a failed build but exited 0";
      }
      return switch (processing) {
        case FAILED -> "exited 0 but the export/import command reported an error";
        case UNFINISHED -> "exited 0 but the export/import command started and never printed Done";
        case NOT_STARTED ->
            "exited 0 but the export/import command never printed Processing started";
        case COMPLETED -> "completed";
      };
    }
  }

  /**
   * What the tool said about itself, read from its output. {@link #SUCCEEDED} is Ant's {@code BUILD
   * SUCCESSFUL} or the {@code VALIDATION COMPLETED} that buildomatic's {@code ImportExportLogger}
   * prints in its place; {@link #FAILED} is Ant's {@code BUILD FAILED} or the {@code Checking Ant
   * return code: BAD} of the Windows wrappers; {@link #CREATED_KEYSTORE} is setup.xml's
   * announcement that {@code create-ks} is about to make a new keystore because it found none,
   * which is a failure whatever the exit code and outranks the other banners (review §1.4); {@link
   * #SILENT} means none appeared, which a caller that needs positive evidence must not read as
   * success.
   */
  enum Reported {
    SUCCEEDED,
    FAILED,
    CREATED_KEYSTORE,
    SILENT
  }

  /**
   * What the export/import command, {@code BaseExportImportCommand} in the vendor's export tool,
   * said about its own work. It prints {@code Processing started} before the work and {@code Done}
   * only when the work returned, and logs a failure as {@code ERROR BaseExportImportCommand}; Ant's
   * {@code BUILD SUCCESSFUL} before it belongs to the validation, not to the command (issue #40).
   * {@link #NOT_STARTED} is also the value for a run that is no export or import at all.
   */
  enum Processing {
    COMPLETED,
    FAILED,
    UNFINISHED,
    NOT_STARTED
  }

  /** The process was killed after {@code timeout}. */
  record TimedOut(Duration timeout, List<String> tail) implements VendorRun {
    public TimedOut {
      tail = List.copyOf(tail);
    }
  }

  /** The process was never launched; {@code remediation} says what to fix. */
  record NotStarted(String reason, String remediation) implements VendorRun {}
}
