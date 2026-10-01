package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.ops.doctor.DoctorOperation;
import com.jaspersoft.jrsupgrade.ops.doctor.DoctorOptions;
import com.jaspersoft.jrsupgrade.ops.doctor.DoctorReport;
import java.io.PrintWriter;
import java.time.Clock;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code jrs-upgrade doctor} (spec §12.1). Read-only. Invariant: the exit code is the report's (0,
 * 2 or 6); {@code --allow-unsupported} is audited and never changes a non-compat result.
 */
@Command(
    name = "doctor",
    mixinStandardHelpOptions = true,
    exitCodeOnInvalidInput = ExitCodes.USAGE,
    description = "Check the tool, the configuration, the server and the installation.")
final class DoctorCommand implements Callable<Integer> {

  @Spec CommandSpec spec;
  @Mixin GlobalOptions global;

  @Option(
      names = "--allow-unsupported",
      description =
          "Downgrade an unsupported server/config combination from FAIL to WARN (audited).")
  boolean allowUnsupported;

  @Override
  public Integer call() {
    PrintWriter out = spec.commandLine().getOut();
    Redactor redactor = Redactor.global();
    try (Bootstrap boot = Bootstrap.open(global, Env.vars(), Clock.systemUTC())) {
      DoctorReport report =
          new DoctorOperation(boot.services()).run(new DoctorOptions(allowUnsupported));
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
