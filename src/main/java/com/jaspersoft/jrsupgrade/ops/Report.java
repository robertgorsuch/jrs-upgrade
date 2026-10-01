package com.jaspersoft.jrsupgrade.ops;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * An ordered list of {@link ReportItem}s with counts (spec §12). Invariants: items are sorted FAIL,
 * WARN, PASS, SKIP with the original order preserved inside each group (a stable sort), so the
 * operator reads the problems first; the list is immutable; {@link #ok()} is true exactly when no
 * item is a FAIL.
 */
public record Report(List<ReportItem> items, Counts counts) {

  /** How many items carry each status. */
  public record Counts(int pass, int warn, int fail, int skip) {
    static Counts of(List<ReportItem> items) {
      int pass = 0;
      int warn = 0;
      int fail = 0;
      int skip = 0;
      for (ReportItem item : items) {
        switch (item.status()) {
          case PASS -> pass++;
          case WARN -> warn++;
          case FAIL -> fail++;
          case SKIP -> skip++;
        }
      }
      return new Counts(pass, warn, fail, skip);
    }

    /** {@code "N pass M warn K fail J skip"}. */
    public String summary() {
      return pass + " pass " + warn + " warn " + fail + " fail " + skip + " skip";
    }
  }

  public Report {
    Objects.requireNonNull(items, "items");
    Objects.requireNonNull(counts, "counts");
    items = List.copyOf(items);
  }

  /** Sorts {@code items} by status rank and computes the counts. */
  public static Report of(List<ReportItem> items) {
    List<ReportItem> sorted = new ArrayList<>(items);
    sorted.sort(Comparator.comparingInt(i -> i.status().rank()));
    return new Report(sorted, Counts.of(sorted));
  }

  public boolean ok() {
    return counts.fail() == 0;
  }

  /**
   * The closing line of a text report, problems first and by name: {@code "1 fail (server), 1 warn
   * (disk), 21 pass, 2 skip"} (field test 2, D2). The names are in report order, so a reader who
   * sees only this line knows what to fix.
   */
  public String summary() {
    return counts.fail()
        + " fail"
        + names(ReportItem.Status.FAIL)
        + ", "
        + counts.warn()
        + " warn"
        + names(ReportItem.Status.WARN)
        + ", "
        + counts.pass()
        + " pass, "
        + counts.skip()
        + " skip";
  }

  private String names(ReportItem.Status status) {
    List<String> names = new ArrayList<>();
    for (ReportItem item : items) {
      if (item.status() == status) {
        names.add(item.name());
      }
    }
    return names.isEmpty() ? "" : " (" + String.join(", ", names) + ")";
  }
}
