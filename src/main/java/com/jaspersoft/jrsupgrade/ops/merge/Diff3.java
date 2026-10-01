package com.jaspersoft.jrsupgrade.ops.merge;

import java.util.ArrayList;
import java.util.List;

/**
 * A three-way merge by line, ported from jrs-hotfix (issue #6, ADR-0003): base is the vendor's file
 * of the running version, mine the installed customization, theirs the target version's file. The
 * changes from the base to mine and from the base to theirs are both applied where they do not
 * overlap; where they overlap and differ, both sides and the base are written between conflict
 * markers. Invariants: nothing is read or written; a stretch only one side changed takes that
 * side's lines; a stretch both changed alike is taken once; with an empty base, two files that
 * differ at all conflict.
 */
public final class Diff3 {

  public static final String MARK_MINE = "<<<<<<< mine (the installed customization)";
  public static final String MARK_BASE = "||||||| base (the vendor's file of the running version)";
  public static final String MARK_SEPARATOR = "=======";
  public static final String MARK_THEIRS = ">>>>>>> theirs (the target version's file)";

  private Diff3() {}

  /** The merged lines and the number of conflict blocks among them. */
  public record Result(List<String> lines, int conflicts) {
    public Result {
      lines = List.copyOf(lines);
    }

    public boolean clean() {
      return conflicts == 0;
    }
  }

  public static Result merge(List<String> base, List<String> mine, List<String> theirs) {
    int[] toMine = Diff.matches(base, mine);
    int[] toTheirs = Diff.matches(base, theirs);
    List<String> out = new ArrayList<>();
    int conflicts = 0;
    int b = 0;
    int m = 0;
    int t = 0;
    while (b < base.size() || m < mine.size() || t < theirs.size()) {
      if (b < base.size() && toMine[b] == m && toTheirs[b] == t) {
        out.add(base.get(b));
        b++;
        m++;
        t++;
        continue;
      }
      // the next base line both sides still have: everything before it is one changed stretch
      int next = b;
      while (next < base.size() && (toMine[next] < m || toTheirs[next] < t)) {
        next++;
      }
      int mineEnd = next < base.size() ? toMine[next] : mine.size();
      int theirsEnd = next < base.size() ? toTheirs[next] : theirs.size();
      List<String> was = base.subList(b, next);
      List<String> ours = mine.subList(m, mineEnd);
      List<String> vendors = theirs.subList(t, theirsEnd);
      if (ours.equals(was)) {
        out.addAll(vendors);
      } else if (vendors.equals(was) || vendors.equals(ours)) {
        out.addAll(ours);
      } else {
        conflicts++;
        out.add(MARK_MINE);
        out.addAll(ours);
        out.add(MARK_BASE);
        out.addAll(was);
        out.add(MARK_SEPARATOR);
        out.addAll(vendors);
        out.add(MARK_THEIRS);
      }
      b = next;
      m = mineEnd;
      t = theirsEnd;
    }
    return new Result(out, conflicts);
  }
}
