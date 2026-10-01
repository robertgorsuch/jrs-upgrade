package com.jaspersoft.jrsupgrade.jrs.strategy;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.jrs.api.Handles;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsupgrade.jrs.rest.RestException;
import java.io.IOException;
import java.util.Optional;

/**
 * Confirms the import task reports {@code READY} once more after polling finished (spec §7.3).
 * Non-mutating; the whole check is the {@link #postcheck}, so a failure here is a {@code
 * Recoverable} that triggers the phase rollback.
 */
final class VerifyImport implements Step {

  static final String ID = "import.verify";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Verify import status";
  }

  @Override
  public String phase() {
    return RestStrategy.IMPORT_PHASE;
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
    return StepResult.ok();
  }

  @Override
  public CheckResult postcheck(Context ctx) {
    Optional<String> id;
    try {
      id = RunFiles.read(RunFiles.in(ctx, RunFiles.IMPORT_HANDLE));
    } catch (IOException e) {
      return CheckResult.fail("cannot read the import handle: " + e.getMessage(), "run again");
    }
    if (id.isEmpty()) {
      return CheckResult.fail("no import task handle recorded", "start the import again");
    }
    try {
      Handles.ImportStatus s =
          ctx.service(JrsAdapter.class).pollImport(new Handles.ImportHandle(id.get()));
      return switch (s.phase()) {
        case READY -> CheckResult.pass();
        case INPROGRESS ->
            CheckResult.fail(
                "import " + id.get() + " is still in progress", "wait and verify the repository");
        case FAILED ->
            CheckResult.fail(
                "import " + id.get() + " failed: " + s.message().orElse("no message"),
                "check the jasperserver log; the pre-import snapshot is re-imported by rollback");
        case PENDING ->
            CheckResult.fail(
                "import " + id.get() + " is pending on the server and imported nothing",
                "cancel it and run again with --broken-dependencies skip or include");
      };
    } catch (JrsUnreachableException e) {
      return CheckResult.fail("server unreachable: " + e.getMessage(), e.remediation());
    } catch (RestException e) {
      if (e.status() == 404) {
        // import.poll already observed READY; the server has since purged the finished task,
        // which answers the same question a fresh READY would (spec §7.3).
        return CheckResult.pass();
      }
      return CheckResult.fail(
          "cannot query import " + id.get() + ": " + e.getMessage(),
          "check the server log and verify the repository by hand");
    }
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    return StepResult.ok();
  }
}
