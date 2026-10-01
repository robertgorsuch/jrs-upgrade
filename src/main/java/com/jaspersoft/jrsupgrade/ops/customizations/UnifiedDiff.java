package com.jaspersoft.jrsupgrade.ops.customizations;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Minimal line-based unified diff (spec §10.3 {@code customizations diff}, §10.2 step 13). The edit
 * script is a longest-common-subsequence over lines with a size cap: above {@value #MAX_CELLS} DP
 * cells the whole file is reported as one replace hunk rather than an optimal diff, so memory stays
 * bounded for large files. Invariants: identical inputs yield an empty list; the output uses the
 * {@code ---}/{@code +++}/{@code @@} header convention with three lines of context and no library
 * dependency.
 */
public final class UnifiedDiff {

  static final long MAX_CELLS = 25_000_000L;
  static final int CONTEXT = 3;

  private UnifiedDiff() {}

  private enum Op {
    EQUAL,
    DELETE,
    INSERT
  }

  private record Edit(Op op, String line) {}

  public static List<String> of(List<String> a, List<String> b, String aName, String bName) {
    Objects.requireNonNull(a, "a");
    Objects.requireNonNull(b, "b");
    if (a.equals(b)) {
      return List.of();
    }
    List<Edit> edits = script(a, b);
    List<String> out = new ArrayList<>();
    out.add("--- " + aName);
    out.add("+++ " + bName);
    out.addAll(hunks(edits));
    return List.copyOf(out);
  }

  private static List<Edit> script(List<String> a, List<String> b) {
    int n = a.size();
    int m = b.size();
    List<Edit> edits = new ArrayList<>();
    if ((long) (n + 1) * (m + 1) > MAX_CELLS) {
      for (String line : a) {
        edits.add(new Edit(Op.DELETE, line));
      }
      for (String line : b) {
        edits.add(new Edit(Op.INSERT, line));
      }
      return edits;
    }
    int[][] lcs = new int[n + 1][m + 1];
    for (int i = n - 1; i >= 0; i--) {
      for (int j = m - 1; j >= 0; j--) {
        lcs[i][j] =
            a.get(i).equals(b.get(j))
                ? lcs[i + 1][j + 1] + 1
                : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
      }
    }
    int i = 0;
    int j = 0;
    while (i < n && j < m) {
      if (a.get(i).equals(b.get(j))) {
        edits.add(new Edit(Op.EQUAL, a.get(i)));
        i++;
        j++;
      } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
        edits.add(new Edit(Op.DELETE, a.get(i)));
        i++;
      } else {
        edits.add(new Edit(Op.INSERT, b.get(j)));
        j++;
      }
    }
    while (i < n) {
      edits.add(new Edit(Op.DELETE, a.get(i++)));
    }
    while (j < m) {
      edits.add(new Edit(Op.INSERT, b.get(j++)));
    }
    return edits;
  }

  private static List<String> hunks(List<Edit> edits) {
    List<String> out = new ArrayList<>();
    int aLine = 1;
    int bLine = 1;
    int k = 0;
    while (k < edits.size()) {
      if (edits.get(k).op() == Op.EQUAL) {
        aLine++;
        bLine++;
        k++;
        continue;
      }
      int start = Math.max(0, k - CONTEXT);
      int end = k;
      int gap = 0;
      int cursor = k;
      while (cursor < edits.size()) {
        if (edits.get(cursor).op() == Op.EQUAL) {
          gap++;
          if (gap > 2 * CONTEXT) {
            break;
          }
        } else {
          gap = 0;
          end = cursor;
        }
        cursor++;
      }
      int stop = Math.min(edits.size(), end + CONTEXT + 1);
      int aStart = aLine - (k - start);
      int bStart = bLine - (k - start);
      int aCount = 0;
      int bCount = 0;
      List<String> body = new ArrayList<>();
      for (int x = start; x < stop; x++) {
        Edit e = edits.get(x);
        switch (e.op()) {
          case EQUAL -> {
            body.add(" " + e.line());
            aCount++;
            bCount++;
          }
          case DELETE -> {
            body.add("-" + e.line());
            aCount++;
          }
          case INSERT -> {
            body.add("+" + e.line());
            bCount++;
          }
        }
      }
      out.add("@@ -" + aStart + "," + aCount + " +" + bStart + "," + bCount + " @@");
      out.addAll(body);
      for (int x = k; x < stop; x++) {
        switch (edits.get(x).op()) {
          case EQUAL -> {
            aLine++;
            bLine++;
          }
          case DELETE -> aLine++;
          case INSERT -> bLine++;
        }
      }
      k = stop;
    }
    return out;
  }
}
