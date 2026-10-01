package com.jaspersoft.jrsupgrade.app;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Aligned text columns for terminal output. Invariants: every column but the last is padded to the
 * widest cell (ANSI escape sequences do not count towards the width); the last column is never
 * padded so lines carry no trailing spaces; rows are rendered in insertion order. A table built
 * with a width (field test 2, G8) wraps the last column on spaces so no line is wider than that:
 * continuation lines are indented to the last column's start, and a token longer than the room left
 * goes on a continuation line of its own, never split. The no-argument table never wraps.
 */
final class TextTable {

  private static final Pattern ANSI = Pattern.compile(Ansi.ESC + "\\[[0-9;]*m");
  private static final String GAP = "  ";

  /** Below this much room for the last column, wrapping would shred every word; leave it. */
  private static final int MIN_LAST_COLUMN = 12;

  private final List<String[]> rows = new ArrayList<>();
  private final int width;

  TextTable() {
    this(Integer.MAX_VALUE);
  }

  TextTable(int width) {
    this.width = width;
  }

  TextTable row(String... cells) {
    rows.add(cells.clone());
    return this;
  }

  /** The rendered lines of every row, flattened in order. */
  List<String> lines() {
    List<String> flat = new ArrayList<>();
    linesByRow().forEach(flat::addAll);
    return flat;
  }

  /** The rendered lines of each row, in order: one line, or several when the last column wraps. */
  List<List<String>> linesByRow() {
    int columns = rows.stream().mapToInt(r -> r.length).max().orElse(0);
    int[] widths = new int[columns];
    for (String[] row : rows) {
      for (int i = 0; i < row.length; i++) {
        widths[i] = Math.max(widths[i], visibleLength(row[i]));
      }
    }
    List<List<String>> lines = new ArrayList<>();
    for (String[] row : rows) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < row.length - 1; i++) {
        if (i > 0) {
          sb.append(GAP);
        }
        sb.append(row[i]);
        sb.append(" ".repeat(widths[i] - visibleLength(row[i])));
      }
      if (row.length == 0) {
        lines.add(List.of(""));
        continue;
      }
      String prefix = row.length > 1 ? sb + GAP : "";
      int room = width - visibleLength(prefix);
      String last = row[row.length - 1];
      if (room < MIN_LAST_COLUMN || visibleLength(last) <= room) {
        lines.add(List.of(prefix + last));
        continue;
      }
      String indent = " ".repeat(visibleLength(prefix));
      List<String> rendered = new ArrayList<>();
      for (String piece : wrap(last, room)) {
        rendered.add((rendered.isEmpty() ? prefix : indent) + piece);
      }
      lines.add(rendered);
    }
    return lines;
  }

  /** {@code text} cut on spaces into pieces of at most {@code room} visible columns. */
  static List<String> wrap(String text, int room) {
    List<String> pieces = new ArrayList<>();
    StringBuilder line = new StringBuilder();
    int used = 0;
    for (String token : text.split(" ", -1)) {
      if (token.isEmpty()) {
        continue;
      }
      int length = visibleLength(token);
      if (used == 0) {
        line.append(token);
        used = length;
      } else if (used + 1 + length <= room) {
        line.append(' ').append(token);
        used += 1 + length;
      } else {
        pieces.add(line.toString());
        line.setLength(0);
        line.append(token);
        used = length;
      }
    }
    pieces.add(line.toString());
    return pieces;
  }

  static int visibleLength(String cell) {
    return ANSI.matcher(cell).replaceAll("").length();
  }
}
