package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.PlanSummary;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link Plan} for the operator before confirmation (spec §6.2): header, summary block,
 * steps grouped by phase and numbered the way the progress renderer numbers them, the fingerprint,
 * and the reminder that nothing has changed yet. Invariants: the JSON form is the document stored
 * in the {@code plans} table, so {@code --plan --json} output and the stored plan are identical;
 * step numbers are 1-based positions in {@code plan.steps()}; every text line is redacted.
 */
final class PlanPrinter {

  private static final int MAX_LISTED_FILES = 20;

  private PlanPrinter() {}

  /** Two-digit step number for position {@code index} (0-based). */
  static String number(int index) {
    return String.format(java.util.Locale.ROOT, "%02d", index + 1);
  }

  static void print(PrintWriter out, Plan plan, Ansi ansi, Redactor redactor) {
    PlanSummary s = plan.summary();
    List<String> lines = new ArrayList<>();
    lines.add("Plan  " + s.operation() + "  " + s.target());
    lines.add("Summary");
    TextTable summary = new TextTable();
    summary.row("  files", count(s.filesTouched().size(), "file"));
    for (String f : listed(s.filesTouched())) {
      summary.row("", f);
    }
    if (!s.resourcesTouched().isEmpty()) {
      summary.row("  resources", count(s.resourcesTouched().size(), "resource"));
      for (String r : listed(s.resourcesTouched().stream().map(Object::toString).toList())) {
        summary.row("", r);
      }
    }
    summary.row("  service", s.serviceRestart() ? "restart required" : "no restart");
    summary.row("  strategy", s.strategy().isBlank() ? "-" : s.strategy());
    summary.row(
        "  backups",
        s.backupLocations().isEmpty()
            ? "none"
            : String.join(", ", s.backupLocations().stream().map(Path::toString).toList()));
    if (s.rollbackPointsByPhase().isEmpty()) {
      summary.row("  rollback", "per phase boundary");
    } else {
      boolean first = true;
      for (Map.Entry<String, String> e : s.rollbackPointsByPhase().entrySet()) {
        summary.row(first ? "  rollback" : "", e.getKey() + " -> " + e.getValue());
        first = false;
      }
    }
    lines.addAll(summary.lines());
    for (String w : s.warnings()) {
      lines.add("  ! " + w);
    }
    lines.add("Steps");
    TextTable steps = new TextTable();
    List<Step> all = plan.steps();
    List<String> headers = new ArrayList<>();
    String phase = "";
    for (int i = 0; i < all.size(); i++) {
      Step step = all.get(i);
      headers.add(step.phase().equals(phase) ? "" : "  " + step.phase());
      phase = step.phase();
      String title = step.irreversible() ? step.title() + " (irreversible)" : step.title();
      steps.row("  " + number(i), title, ansi.dim(step.detail()));
    }
    List<String> stepLines = steps.lines();
    for (int i = 0; i < all.size(); i++) {
      if (!headers.get(i).isEmpty()) {
        lines.add(headers.get(i));
      }
      lines.add(stepLines.get(i));
    }
    lines.add("Fingerprint  " + plan.fingerprint().value());
    lines.add("nothing has changed");
    for (String line : lines) {
      out.println(redactor.redact(line.stripTrailing()));
    }
    out.flush();
  }

  private static String count(int n, String noun) {
    return n + " " + (n == 1 ? noun : noun + "s");
  }

  private static List<String> listed(List<?> items) {
    List<String> out = new ArrayList<>();
    int shown = Math.min(items.size(), MAX_LISTED_FILES);
    for (int i = 0; i < shown; i++) {
      out.add(items.get(i).toString());
    }
    if (items.size() > shown) {
      out.add("... and " + (items.size() - shown) + " more");
    }
    return out;
  }
}
