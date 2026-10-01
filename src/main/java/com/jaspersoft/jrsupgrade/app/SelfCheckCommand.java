package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.core.selfcheck.SelfCheck;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** {@code jrs-upgrade selfcheck}: verifies the tool itself (spec §12.3). Read-only. */
@Command(
    name = "selfcheck",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description = "Verify the jrs-upgrade runtime, bundled resources, key ring and state schema.")
final class SelfCheckCommand implements Callable<Integer> {

  @Spec CommandSpec spec;

  @Option(names = "--json", description = "Emit the report as JSON.")
  boolean json;

  @Override
  public Integer call() {
    SelfCheck.Report report = new SelfCheck().run();
    PrintWriter out = spec.commandLine().getOut();
    if (json) {
      out.println(JsonOut.write(report));
    } else {
      for (SelfCheck.Item item : report.items()) {
        out.printf("  %-4s  %-16s %s%n", item.status(), item.name(), item.detail());
      }
      out.println(report.ok() ? "selfcheck ok" : "selfcheck failed");
    }
    out.flush();
    if (report.ok()) {
      return ExitCodes.SUCCESS;
    }
    // review 3.1: a host outside ADR-0002 is unsupported (6), not a precheck failure (2)
    boolean unsupportedHost =
        report.items().stream()
            .anyMatch(
                i -> i.name().equals(SelfCheck.PLATFORM) && i.status() == SelfCheck.Status.FAIL);
    return unsupportedHost ? ExitCodes.UNSUPPORTED : ExitCodes.PRECHECK_FAILED;
  }
}
