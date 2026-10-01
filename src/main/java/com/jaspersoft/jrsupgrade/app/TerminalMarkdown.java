package com.jaspersoft.jrsupgrade.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the Markdown subset the embedded documents use as plain text for a terminal (#60).
 * Invariants: the output carries no markup a terminal would show literally (heading hashes, table
 * pipes and separator rows, emphasis asterisks, backticks, code fences, link brackets); every word
 * of text and every code line is kept; paragraphs, list items and quotes are re-wrapped at the
 * width on spaces only, so a word or path longer than the width overflows rather than being split;
 * code blocks are indented and never re-wrapped; a table that fits the width is aligned in columns,
 * and one that does not becomes one block of {@code header: value} lines per row; consecutive blank
 * lines collapse to one; the result is deterministic for a given input and width and reads nothing
 * but its arguments.
 */
final class TerminalMarkdown {

  /** Width used when the terminal's is unknown: the same as every table's. */
  static final int DEFAULT_WIDTH = Terminal.DEFAULT_WIDTH;

  private static final int MIN_WIDTH = 40;
  private static final int MAX_WIDTH = 160;
  private static final int RULE_WIDTH = 40;
  private static final String CODE_INDENT = "    ";

  private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
  private static final Pattern BULLET = Pattern.compile("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$");
  private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~).*$");
  private static final Pattern RULE = Pattern.compile("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$");
  private static final Pattern UNDERLINE = Pattern.compile("^\\s*(={3,}|-{3,})\\s*$");
  private static final Pattern TABLE_SEPARATOR =
      Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$");
  private static final Pattern QUOTE = Pattern.compile("^\\s*>\\s?(.*)$");
  private static final Pattern CODE_SPAN = Pattern.compile("`([^`]*)`");
  private static final Pattern PLACEHOLDER = Pattern.compile("(\\d+)");
  private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)");
  private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");
  private static final Pattern EMPHASIS =
      Pattern.compile("(?<![\\w*])\\*(?![\\s*])(.+?)(?<![\\s*])\\*(?![\\w*])");
  private static final Pattern SPACES = Pattern.compile("\\s+");

  private TerminalMarkdown() {}

  /** The width to render at: {@link Terminal#width}, kept within what prose can use. */
  static int width(Map<String, String> env) {
    return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, Terminal.width(env)));
  }

  /** Renders {@code lines} at {@code width} columns; the result ends with one line separator. */
  static String render(List<String> lines, int width) {
    int w = Math.max(MIN_WIDTH, width);
    List<String> out = new ArrayList<>();
    int i = 0;
    while (i < lines.size()) {
      String line = lines.get(i);
      if (FENCE.matcher(line).matches()) {
        i = code(lines, i + 1, out);
      } else if (isTableRow(line)
          && i + 1 < lines.size()
          && TABLE_SEPARATOR.matcher(lines.get(i + 1)).matches()) {
        i = table(lines, i, w, out);
      } else if (HEADING.matcher(line).matches()) {
        Matcher h = HEADING.matcher(line);
        h.matches();
        heading(h.group(1).length(), inline(h.group(2)), w, out);
        i++;
      } else if (line.isBlank()) {
        out.add("");
        i++;
      } else if (RULE.matcher(line).matches()) {
        out.add("-".repeat(RULE_WIDTH));
        i++;
      } else if (BULLET.matcher(line).matches()) {
        i = bullet(lines, i, w, out);
      } else if (QUOTE.matcher(line).matches()) {
        i = quote(lines, i, w, out);
      } else if (i + 1 < lines.size() && UNDERLINE.matcher(lines.get(i + 1)).matches()) {
        heading(lines.get(i + 1).trim().charAt(0) == '=' ? 1 : 2, inline(line.trim()), w, out);
        i += 2;
      } else {
        i = paragraph(lines, i, w, out);
      }
    }
    return join(out);
  }

  // ---- blocks -----------------------------------------------------------------------------------

  private static int code(List<String> lines, int start, List<String> out) {
    int i = start;
    while (i < lines.size() && !FENCE.matcher(lines.get(i)).matches()) {
      String code = lines.get(i).stripTrailing();
      out.add(code.isEmpty() ? "" : CODE_INDENT + code);
      i++;
    }
    return i + 1;
  }

  private static void heading(int level, String text, int width, List<String> out) {
    if (!out.isEmpty() && !out.get(out.size() - 1).isEmpty()) {
      out.add("");
    }
    int before = out.size();
    wrap(text, "", "", width, out);
    int underline = 0;
    for (String line : out.subList(before, out.size())) {
      underline = Math.max(underline, Math.min(line.length(), width));
    }
    if (level == 1) {
      out.add("=".repeat(underline));
    } else if (level == 2) {
      out.add("-".repeat(underline));
    }
  }

  private static int bullet(List<String> lines, int start, int width, List<String> out) {
    Matcher b = BULLET.matcher(lines.get(start));
    b.matches();
    String indent = " ".repeat(b.group(1).length());
    String marker = Character.isDigit(b.group(2).charAt(0)) ? b.group(2) : "-";
    StringBuilder text = new StringBuilder(b.group(3).trim());
    int i = start + 1;
    while (i < lines.size() && continues(lines.get(i))) {
      text.append(' ').append(lines.get(i).trim());
      i++;
    }
    wrap(
        inline(text.toString()),
        indent + marker + " ",
        indent + " ".repeat(marker.length() + 1),
        width,
        out);
    return i;
  }

  private static int quote(List<String> lines, int start, int width, List<String> out) {
    StringBuilder text = new StringBuilder();
    int i = start;
    while (i < lines.size() && QUOTE.matcher(lines.get(i)).matches()) {
      Matcher q = QUOTE.matcher(lines.get(i));
      q.matches();
      if (q.group(1).isBlank()) {
        if (text.length() > 0) {
          wrap(inline(text.toString()), CODE_INDENT, CODE_INDENT, width, out);
          out.add("");
          text.setLength(0);
        }
      } else {
        text.append(text.length() > 0 ? " " : "").append(q.group(1).trim());
      }
      i++;
    }
    if (text.length() > 0) {
      wrap(inline(text.toString()), CODE_INDENT, CODE_INDENT, width, out);
    }
    return i;
  }

  private static int paragraph(List<String> lines, int start, int width, List<String> out) {
    String first = lines.get(start);
    String indent = " ".repeat(first.length() - first.stripLeading().length());
    StringBuilder text = new StringBuilder(first.trim());
    int i = start + 1;
    while (i < lines.size() && continues(lines.get(i))) {
      if (i + 1 < lines.size() && UNDERLINE.matcher(lines.get(i + 1)).matches()) {
        break;
      }
      text.append(' ').append(lines.get(i).trim());
      i++;
    }
    wrap(inline(text.toString()), indent, indent, width, out);
    return i;
  }

  /** True when {@code line} continues the paragraph or list item above it. */
  private static boolean continues(String line) {
    return !line.isBlank()
        && !HEADING.matcher(line).matches()
        && !FENCE.matcher(line).matches()
        && !BULLET.matcher(line).matches()
        && !isTableRow(line)
        && !RULE.matcher(line).matches()
        && !UNDERLINE.matcher(line).matches()
        && !QUOTE.matcher(line).matches();
  }

  // ---- tables -----------------------------------------------------------------------------------

  private static boolean isTableRow(String line) {
    return line.stripLeading().startsWith("|");
  }

  private static int table(List<String> lines, int start, int width, List<String> out) {
    String indent =
        " ".repeat(lines.get(start).length() - lines.get(start).stripLeading().length());
    List<List<String>> rows = new ArrayList<>();
    rows.add(cells(lines.get(start)));
    int i = start + 2;
    while (i < lines.size() && isTableRow(lines.get(i))) {
      rows.add(cells(lines.get(i)));
      i++;
    }
    int columns = rows.stream().mapToInt(List::size).max().orElse(0);
    int[] widths = new int[columns];
    for (List<String> row : rows) {
      for (int c = 0; c < row.size(); c++) {
        widths[c] = Math.max(widths[c], row.get(c).length());
      }
    }
    int total = indent.length() + 2 * Math.max(0, columns - 1);
    for (int cw : widths) {
      total += cw;
    }
    if (total <= width) {
      out.add(indent + aligned(rows.get(0), widths));
      List<String> rule = new ArrayList<>();
      for (int cw : widths) {
        rule.add("-".repeat(cw));
      }
      out.add(indent + aligned(rule, widths));
      for (List<String> row : rows.subList(1, rows.size())) {
        out.add(indent + aligned(row, widths));
      }
    } else {
      List<String> header = rows.get(0);
      for (int r = 1; r < rows.size(); r++) {
        if (r > 1) {
          out.add("");
        }
        List<String> row = rows.get(r);
        for (int c = 0; c < row.size(); c++) {
          if (row.get(c).isEmpty()) {
            continue;
          }
          String label = c < header.size() && !header.get(c).isEmpty() ? header.get(c) + ": " : "";
          wrap(label + row.get(c), indent, indent + "  ", width, out);
        }
      }
    }
    return i;
  }

  private static String aligned(List<String> cells, int[] widths) {
    StringBuilder sb = new StringBuilder();
    for (int c = 0; c < widths.length; c++) {
      String cell = c < cells.size() ? cells.get(c) : "";
      if (c > 0) {
        sb.append("  ");
      }
      sb.append(cell).append(" ".repeat(widths[c] - cell.length()));
    }
    return sb.toString().stripTrailing();
  }

  /** Splits a row on pipes that are neither escaped nor inside a code span; cells are rendered. */
  private static List<String> cells(String row) {
    String text = row.trim();
    List<String> raw = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean code = false;
    for (int k = 0; k < text.length(); k++) {
      char ch = text.charAt(k);
      if (ch == '`') {
        code = !code;
      }
      if (ch == '|' && !code && (k == 0 || text.charAt(k - 1) != '\\')) {
        raw.add(cell.toString());
        cell.setLength(0);
      } else {
        cell.append(ch);
      }
    }
    raw.add(cell.toString());
    if (!raw.isEmpty() && raw.get(0).isBlank()) {
      raw.remove(0);
    }
    if (!raw.isEmpty() && raw.get(raw.size() - 1).isBlank()) {
      raw.remove(raw.size() - 1);
    }
    return raw.stream().map(c -> inline(c.trim())).toList();
  }

  // ---- inline -----------------------------------------------------------------------------------

  /** Strips emphasis, code spans and link syntax, keeping the text; code content is left as is. */
  static String inline(String text) {
    List<String> spans = new ArrayList<>();
    Matcher code = CODE_SPAN.matcher(text);
    StringBuilder protectedText = new StringBuilder();
    while (code.find()) {
      spans.add(code.group(1));
      code.appendReplacement(protectedText, "" + (spans.size() - 1) + "");
    }
    code.appendTail(protectedText);
    String s =
        LINK.matcher(protectedText.toString())
            .replaceAll(m -> Matcher.quoteReplacement(link(m.group(1), m.group(2))));
    s =
        BOLD.matcher(s)
            .replaceAll(
                m -> Matcher.quoteReplacement(m.group(1) != null ? m.group(1) : m.group(2)));
    s = EMPHASIS.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1)));
    s = s.replace("\\|", "|").replace("\\*", "*").replace("\\_", "_");
    return PLACEHOLDER
        .matcher(s)
        .replaceAll(
            m ->
                Matcher.quoteReplacement(
                    spans.get(Integer.parseInt(m.group(1))).replace("\\|", "|")));
  }

  private static String link(String text, String url) {
    if (url.startsWith("#") || url.equals(text) || text.contains(url)) {
      return text;
    }
    return text + " (" + url + ")";
  }

  // ---- output -----------------------------------------------------------------------------------

  private static void wrap(String text, String first, String rest, int width, List<String> out) {
    List<String> words = SPACES.splitAsStream(text.trim()).toList();
    StringBuilder line = new StringBuilder(first);
    boolean empty = true;
    for (String word : words) {
      if (word.isEmpty()) {
        continue;
      }
      if (!empty && line.length() + 1 + word.length() > width) {
        out.add(line.toString());
        line = new StringBuilder(rest);
        empty = true;
      }
      if (!empty) {
        line.append(' ');
      }
      line.append(word);
      empty = false;
    }
    out.add(line.toString().stripTrailing());
  }

  private static String join(List<String> lines) {
    List<String> kept = new ArrayList<>();
    for (String line : lines) {
      boolean blank = line.isBlank();
      if (blank && (kept.isEmpty() || kept.get(kept.size() - 1).isEmpty())) {
        continue;
      }
      kept.add(blank ? "" : line.stripTrailing());
    }
    while (!kept.isEmpty() && kept.get(kept.size() - 1).isEmpty()) {
      kept.remove(kept.size() - 1);
    }
    String sep = System.lineSeparator();
    return String.join(sep, kept) + sep;
  }
}
