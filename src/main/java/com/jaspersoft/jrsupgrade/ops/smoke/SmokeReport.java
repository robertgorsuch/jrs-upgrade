package com.jaspersoft.jrsupgrade.ops.smoke;

import com.jaspersoft.jrsupgrade.ops.Report;
import com.jaspersoft.jrsupgrade.ops.ReportItem;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code smoke} result (spec §12.2, §18). Invariants: items are sorted FAIL, WARN, PASS, SKIP;
 * {@code exitCode} is 0 when nothing failed and 2 otherwise (a missing sample report is a WARN and
 * keeps 0); {@code runId} is present only when the mutating plan ran, so the journal can be found.
 */
public record SmokeReport(
    List<ReportItem> items, Report.Counts counts, int exitCode, Optional<String> runId) {

  public static final int EXIT_OK = 0;
  public static final int EXIT_FAILED = 2;

  public SmokeReport {
    Objects.requireNonNull(items, "items");
    Objects.requireNonNull(counts, "counts");
    Objects.requireNonNull(runId, "runId");
    items = List.copyOf(items);
  }

  public static SmokeReport of(List<ReportItem> unsorted, Optional<String> runId) {
    Report report = Report.of(unsorted);
    return new SmokeReport(
        report.items(), report.counts(), report.ok() ? EXIT_OK : EXIT_FAILED, runId);
  }

  public boolean ok() {
    return counts.fail() == 0;
  }
}
