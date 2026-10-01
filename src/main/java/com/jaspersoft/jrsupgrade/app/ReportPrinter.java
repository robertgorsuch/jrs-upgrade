package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.ops.Report;
import com.jaspersoft.jrsupgrade.ops.ReportItem;
import java.io.PrintWriter;
import java.util.List;

/**
 * Text rendering shared by {@code doctor}, {@code smoke} and {@code hotfix verify}: one aligned row
 * per item ({@code status name detail}), the remediation indented under every non-PASS item, the
 * problems (FAIL, WARN) before a rule and everything else after it, then the summary line naming
 * the problems (field test 2, D2: a failure used to be one line followed by many green ones).
 * Invariants: every line passes through the redactor before it is written; the remediation of a
 * problem is never dimmed, only a SKIP's; the summary is coloured by the worst status.
 */
final class ReportPrinter {

  static final String RULE = "----";
  private static final String INDENT = "        -> ";

  private ReportPrinter() {}

  static void print(
      PrintWriter out, List<ReportItem> items, Report.Counts counts, Ansi ansi, Redactor redactor) {
    Report report = new Report(items, counts);
    TextTable table = new TextTable(Terminal.width(Env.vars()));
    for (ReportItem item : items) {
      table.row(ansi.status(item.status()), item.name(), item.detail());
    }
    List<List<String>> lines = table.linesByRow();
    int problems = counts.fail() + counts.warn();
    for (int i = 0; i < items.size(); i++) {
      ReportItem item = items.get(i);
      if (i == problems && problems > 0) {
        out.println(RULE);
      }
      for (String line : lines.get(i)) {
        out.println(redactor.redact(line));
      }
      if (item.status() != ReportItem.Status.PASS && !item.remediation().isEmpty()) {
        String remediation = INDENT + item.remediation();
        out.println(
            redactor.redact(
                item.status() == ReportItem.Status.SKIP ? ansi.dim(remediation) : remediation));
      }
    }
    ReportItem.Status worst =
        counts.fail() > 0
            ? ReportItem.Status.FAIL
            : counts.warn() > 0 ? ReportItem.Status.WARN : ReportItem.Status.PASS;
    out.println(redactor.redact(ansi.summary(report.summary(), worst)));
    out.flush();
  }
}
