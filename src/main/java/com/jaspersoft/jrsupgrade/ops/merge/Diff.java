package com.jaspersoft.jrsupgrade.ops.merge;

import java.util.ArrayList;
import java.util.List;

/**
 * The longest common subsequence of two lists of lines, by Myers' greedy algorithm (ported from
 * jrs-hotfix), and a unified diff made from it. Invariants: nothing is read or written; the matches
 * are pairs of indices, strictly ascending in both lists; when the two differ in more than {@link
 * #MAX_EDITS} lines beyond their common start and end, only that common start and end match, which
 * is a valid if coarse answer and keeps the memory bounded.
 */
public final class Diff {

  /** The edit distance past which the middle of two files is taken as wholly different. */
  static final int MAX_EDITS = 2000;

  private static final int CONTEXT = 3;

  private Diff() {}

  /** For every line of {@code a}, the index of the line of {@code b} it matches, or -1. */
  public static int[] matches(List<String> a, List<String> b) {
    int[] out = new int[a.size()];
    java.util.Arrays.fill(out, -1);
    int start = 0;
    while (start < a.size() && start < b.size() && a.get(start).equals(b.get(start))) {
      out[start] = start;
      start++;
    }
    int endA = a.size();
    int endB = b.size();
    while (endA > start && endB > start && a.get(endA - 1).equals(b.get(endB - 1))) {
      endA--;
      endB--;
      out[endA] = endB;
    }
    middle(a.subList(start, endA), b.subList(start, endB), out, start);
    return out;
  }

  /** Myers' algorithm on the differing middle; matches are written into {@code out}. */
  private static void middle(List<String> a, List<String> b, int[] out, int shift) {
    int n = a.size();
    int m = b.size();
    if (n == 0 || m == 0) {
      return;
    }
    int max = Math.min(n + m, MAX_EDITS);
    int offset = max + 1;
    int[] v = new int[2 * max + 3];
    List<int[]> trace = new ArrayList<>();
    boolean found = false;
    for (int d = 0; d <= max && !found; d++) {
      // the state before round d, for the indices round d reads: -d-1 .. d+1
      int[] snapshot = new int[2 * d + 3];
      System.arraycopy(v, offset - d - 1, snapshot, 0, snapshot.length);
      trace.add(snapshot);
      for (int k = -d; k <= d; k += 2) {
        int x =
            k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])
                ? v[offset + k + 1]
                : v[offset + k - 1] + 1;
        int y = x - k;
        while (x < n && y < m && a.get(x).equals(b.get(y))) {
          x++;
          y++;
        }
        v[offset + k] = x;
        if (x >= n && y >= m) {
          found = true;
          break;
        }
      }
    }
    if (!found) {
      return;
    }
    int x = n;
    int y = m;
    for (int d = trace.size() - 1; d >= 0; d--) {
      int[] before = trace.get(d);
      int center = d + 1;
      int k = x - y;
      int prevK =
          k == -d || (k != d && before[center + k - 1] < before[center + k + 1]) ? k + 1 : k - 1;
      int prevX = before[center + prevK];
      int prevY = prevX - prevK;
      while (x > prevX && y > prevY) {
        x--;
        y--;
        out[shift + x] = shift + y;
      }
      if (d > 0) {
        x = prevX;
        y = prevY;
      }
    }
  }

  /**
   * The unified diff from {@code a} to {@code b}, three lines of context around each change, under
   * the two header lines; empty when the two are equal.
   */
  public static List<String> unified(String nameA, String nameB, List<String> a, List<String> b) {
    int[] match = matches(a, b);
    // the edit script: ' ' kept, '-' only in a, '+' only in b, with the line numbers of both
    List<int[]> ops = new ArrayList<>();
    int j = 0;
    for (int i = 0; i < a.size(); i++) {
      if (match[i] < 0) {
        ops.add(new int[] {'-', i, j});
      } else {
        while (j < match[i]) {
          ops.add(new int[] {'+', i, j++});
        }
        ops.add(new int[] {' ', i, j++});
      }
    }
    while (j < b.size()) {
      ops.add(new int[] {'+', a.size(), j++});
    }
    List<String> out = new ArrayList<>();
    int at = 0;
    while (at < ops.size()) {
      while (at < ops.size() && ops.get(at)[0] == ' ') {
        at++;
      }
      if (at == ops.size()) {
        break;
      }
      int from = Math.max(0, at - CONTEXT);
      int to = at;
      int last = at;
      while (to < ops.size() && to - last <= 2 * CONTEXT + 1) {
        if (ops.get(to)[0] != ' ') {
          last = to;
        }
        to++;
      }
      to = Math.min(ops.size(), last + CONTEXT + 1);
      if (out.isEmpty()) {
        out.add("--- " + nameA);
        out.add("+++ " + nameB);
      }
      int countA = 0;
      int countB = 0;
      for (int[] op : ops.subList(from, to)) {
        countA += op[0] == '+' ? 0 : 1;
        countB += op[0] == '-' ? 0 : 1;
      }
      int[] first = ops.get(from);
      out.add(
          "@@ -"
              + (countA == 0 ? first[1] : first[1] + 1)
              + ","
              + countA
              + " +"
              + (countB == 0 ? first[2] : first[2] + 1)
              + ","
              + countB
              + " @@");
      for (int[] op : ops.subList(from, to)) {
        out.add((char) op[0] + (op[0] == '+' ? b.get(op[2]) : a.get(op[1])));
      }
      at = to;
    }
    return out;
  }
}
