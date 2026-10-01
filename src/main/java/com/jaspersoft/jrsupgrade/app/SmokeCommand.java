package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.ops.smoke.SmokeOperation;
import com.jaspersoft.jrsupgrade.ops.smoke.SmokeOptions;
import com.jaspersoft.jrsupgrade.ops.smoke.SmokeReport;
import java.io.PrintWriter;
import java.time.Clock;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code jrs-upgrade smoke} (spec §12.2). Invariant: non-mutating unless {@code --mutating}, in
 * which case the only mutation is the journaled smoke plan; the exit code is the report's (0 or 2).
 */
@Command(
    name = "smoke",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description = "Exercise the server: login, repository, a report, the scheduler and an export.")
final class SmokeCommand implements Callable<Integer> {

  @Spec CommandSpec spec;
  @Mixin GlobalOptions global;

  @Option(
      names = "--mutating",
      description = "Also create, run and delete a temporary report under /temp (journaled plan).")
  boolean mutating;

  @Override
  public Integer call() {
    PrintWriter out = spec.commandLine().getOut();
    Redactor redactor = Redactor.global();
    try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
      SmokeReport report = new SmokeOperation(boot.services()).run(new SmokeOptions(mutating));
      if (global.json()) {
        out.println(redactor.redact(JsonOut.write(report)));
        out.flush();
      } else {
        ReportPrinter.print(
            out, report.items(), report.counts(), Ansi.forStdout(global, Env.vars()), redactor);
      }
      return report.exitCode();
    }
  }
}
