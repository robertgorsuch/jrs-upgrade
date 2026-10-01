package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.ops.init.InitOperation;
import com.jaspersoft.jrsupgrade.ops.init.InitReport;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How {@code init} shows the installations its search found and lets the operator pick one (field
 * test 3: a JRS 9 was proposed while a JRS 10 ran beside it, and the other was never mentioned).
 * Invariants: candidates are numbered from 1 in the report's ranked order, the first being the
 * recommended one; {@link #ask} returns a position in that list and never throws, Enter or the end
 * of input keeping the recommended one; every printed line passes the redaction filter.
 */
final class InstallChoice {

  private InstallChoice() {}

  /** The numbered list and the search notes, one line each. */
  static List<String> lines(InitReport report) {
    List<String> lines = new ArrayList<>();
    List<InitReport.Candidate> candidates = report.candidates();
    if (candidates.size() > 1) {
      lines.add("Found " + candidates.size() + " installations:");
      for (int i = 0; i < candidates.size(); i++) {
        InitReport.Candidate c = candidates.get(i);
        lines.add(
            "  "
                + (i + 1)
                + ") "
                + c.layout().installDir()
                + "  ("
                + InitOperation.describe(c)
                + (i == 0 ? "; recommended" : "")
                + ")");
      }
    }
    for (String note : report.notes()) {
      lines.add("note: " + note);
    }
    return lines;
  }

  static void print(InitReport report, PrintWriter out) {
    Redactor redactor = Redactor.global();
    for (String line : lines(report)) {
      out.println(redactor.redact(line));
    }
    out.flush();
  }

  /**
   * Asks which installation to use until the answer is a listed number; Enter or the end of input
   * is the recommended one, position 0.
   */
  static int ask(InitReport report, PrintWriter out) {
    int count = report.candidates().size();
    while (true) {
      Optional<String> answer =
          Prompter.line(out, "Use which installation? [1-" + count + ", Enter = 1]: ");
      if (answer.isEmpty() || answer.get().isEmpty()) {
        return 0;
      }
      try {
        int picked = Integer.parseInt(answer.get().strip());
        if (picked >= 1 && picked <= count) {
          return picked - 1;
        }
      } catch (NumberFormatException e) {
        // asked again below
      }
      out.println("  no installation " + answer.get() + "; type 1 to " + count + ", or Enter");
    }
  }

  /** The candidates for {@code init --json}: one object each, in the ranked order. */
  static List<Map<String, Object>> json(InitReport report) {
    List<Map<String, Object>> out = new ArrayList<>();
    List<InitReport.Candidate> candidates = report.candidates();
    for (int i = 0; i < candidates.size(); i++) {
      InitReport.Candidate c = candidates.get(i);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("installDir", c.layout().installDir().toString());
      m.put("tomcatDir", c.layout().tomcatDir().toString());
      m.put("webappName", c.layout().webappName());
      m.put("edition", c.edition());
      c.version().ifPresent(v -> m.put("version", v));
      m.put("running", c.running());
      m.put("recommended", i == 0);
      m.put("chosen", c.chosen());
      out.add(m);
    }
    return out;
  }
}
