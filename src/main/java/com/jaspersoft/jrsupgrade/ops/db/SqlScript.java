package com.jaspersoft.jrsupgrade.ops.db;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Splits a SQL script into statements (spec §8.1). A statement ends at the terminator, by default
 * {@code ;}, wherever that terminator is not inside a string, a quoted identifier, a comment or a
 * dollar-quoted body; comment lines standing before a statement are dropped, comments inside one
 * are kept, and blank statements are dropped.
 *
 * <p>The terminator is found by reading the script rather than by looking at line endings, because
 * a splitter that only asks whether a line ends in {@code ;} cuts through the middle of anything
 * that contains one: a literal such as {@code 'a;b'} at the end of a line, a PostgreSQL {@code $$
 * ... $$} body, a comment. Each half is then sent to the database on its own, and because a hotfix
 * session runs on the driver's own auto-commit, the halves before the cut have already committed by
 * the time the first one fails. That leaves the schema in a shape the hotfix's rollback script was
 * never written for.
 *
 * <p>Some statements end with the same character their inner statements do, a PL/SQL {@code BEGIN
 * ... END;} block above all, and no reader can tell those apart. Such a script says so itself with
 * a directive on its own line, in the spirit of {@code DELIMITER}:
 *
 * <pre>{@code
 * -- jrs-upgrade:delimiter //
 * CREATE PROCEDURE p AS BEGIN UPDATE t SET a = 1; UPDATE t SET b = 2; END;
 * //
 * -- jrs-upgrade:delimiter ;
 * }</pre>
 *
 * <p>Invariants: statements come out in the order they were written, and nothing is reordered or
 * rewritten; the reader is consumed once and only the statement being assembled is held in memory;
 * a doubled quote ({@code ''}, {@code ""}) keeps its string open; block comments do not nest, so a
 * script relying on PostgreSQL's nesting ends its comment early and fails loudly at the database
 * rather than silently swallowing the rest of the file. Backslash escapes inside strings are not
 * recognised, and neither are SQL Server's {@code [bracketed]} identifiers; a script that needs a
 * {@code ;} inside either has to set a delimiter.
 */
public final class SqlScript {

  /** Directive that changes the statement terminator for the rest of the script. */
  public static final String DELIMITER_DIRECTIVE = "jrs-upgrade:delimiter";

  private static final String DEFAULT_DELIMITER = ";";

  private SqlScript() {}

  /** Statements of {@code sqlText}, in order, without their terminator. */
  public static List<String> statements(String sqlText) {
    try (Reader reader = new StringReader(sqlText)) {
      return statements(reader);
    } catch (IOException e) {
      throw new IllegalStateException("in-memory read failed", e);
    }
  }

  /** Streams {@code file} (UTF-8) and splits it into statements. */
  public static List<String> read(Path file) throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      return statements(reader);
    }
  }

  /** Splits everything {@code reader} yields; the reader is not closed. */
  public static List<String> statements(Reader reader) throws IOException {
    return new Splitter(reader).run();
  }

  /** Where in the script the reader stands; a terminator only counts in {@link #CODE}. */
  private enum State {
    CODE,
    LINE_COMMENT,
    BLOCK_COMMENT,
    SINGLE_QUOTED,
    DOUBLE_QUOTED,
    BACKTICK_QUOTED,
    DOLLAR_QUOTED
  }

  /**
   * One pass over a script. Invariants: {@code current} holds the statement being assembled and
   * never the blank lines or comment lines that stood before it; characters read while deciding
   * whether something is a dollar quote are pushed back, so no input is lost; a delimiter directive
   * takes effect when its comment line ends.
   */
  private static final class Splitter {

    private final Reader reader;
    private final Deque<Character> pending = new ArrayDeque<>();
    private final List<String> out = new ArrayList<>();
    private final StringBuilder current = new StringBuilder();
    private final StringBuilder comment = new StringBuilder();

    private State state = State.CODE;
    private String delimiter = DEFAULT_DELIMITER;
    private String dollarTag = "";
    private boolean started;

    Splitter(Reader reader) {
      this.reader = reader;
    }

    List<String> run() throws IOException {
      int c;
      while ((c = next()) != -1) {
        switch (state) {
          case CODE -> code((char) c);
          case LINE_COMMENT -> lineComment((char) c);
          case BLOCK_COMMENT -> blockComment((char) c);
          case SINGLE_QUOTED -> quoted((char) c, '\'', State.SINGLE_QUOTED);
          case DOUBLE_QUOTED -> quoted((char) c, '"', State.DOUBLE_QUOTED);
          case BACKTICK_QUOTED -> quoted((char) c, '`', State.BACKTICK_QUOTED);
          case DOLLAR_QUOTED -> dollarQuoted((char) c);
        }
      }
      if (state == State.LINE_COMMENT) {
        applyDirective();
      }
      flush();
      return List.copyOf(out);
    }

    private void code(char c) throws IOException {
      if (c == '-' && peekIs('-')) {
        next();
        comment.setLength(0);
        state = State.LINE_COMMENT;
        appendIfStarted("--");
        return;
      }
      if (c == '/' && peekIs('*')) {
        next();
        state = State.BLOCK_COMMENT;
        appendIfStarted("/*");
        return;
      }
      if (c == '$') {
        Optional<String> tag = dollarTag();
        if (tag.isPresent()) {
          dollarTag = tag.get();
          state = State.DOLLAR_QUOTED;
          append("$" + dollarTag + "$");
        } else {
          append("$");
        }
        return;
      }
      switch (c) {
        case '\'' -> state = State.SINGLE_QUOTED;
        case '"' -> state = State.DOUBLE_QUOTED;
        case '`' -> state = State.BACKTICK_QUOTED;
        default -> {
          // an ordinary character: nothing changes but the buffer
        }
      }
      append(String.valueOf(c));
      if (state == State.CODE && started && endsWithDelimiter()) {
        current.setLength(current.length() - delimiter.length());
        flush();
      }
    }

    private void lineComment(char c) {
      appendIfStarted(String.valueOf(c));
      if (c == '\n') {
        applyDirective();
        state = State.CODE;
        return;
      }
      comment.append(c);
    }

    private void blockComment(char c) throws IOException {
      appendIfStarted(String.valueOf(c));
      if (c == '*' && peekIs('/')) {
        next();
        appendIfStarted("/");
        state = State.CODE;
      }
    }

    private void quoted(char c, char quote, State inside) throws IOException {
      append(String.valueOf(c));
      if (c != quote) {
        return;
      }
      if (peekIs(quote)) {
        append(String.valueOf((char) next()));
        state = inside;
        return;
      }
      state = State.CODE;
    }

    private void dollarQuoted(char c) throws IOException {
      if (c != '$') {
        append(String.valueOf(c));
        return;
      }
      Optional<String> tag = dollarTag();
      if (tag.isEmpty()) {
        append("$");
        return;
      }
      append("$" + tag.get() + "$");
      if (tag.get().equals(dollarTag)) {
        state = State.CODE;
      }
    }

    /**
     * The tag of a dollar quote when the reader stands just after its opening {@code $}, or empty
     * with every character it looked at pushed back. A tag is a PostgreSQL identifier or nothing at
     * all, which is the common {@code $$}.
     */
    private Optional<String> dollarTag() throws IOException {
      StringBuilder tag = new StringBuilder();
      while (true) {
        int c = next();
        if (c == -1) {
          pushBack(tag.toString());
          return Optional.empty();
        }
        if (c == '$') {
          return Optional.of(tag.toString());
        }
        boolean valid =
            tag.length() == 0
                ? Character.isLetter((char) c) || c == '_'
                : Character.isLetterOrDigit((char) c) || c == '_';
        if (!valid) {
          pushBack(tag.toString() + (char) c);
          return Optional.empty();
        }
        tag.append((char) c);
      }
    }

    private void applyDirective() {
      String text = comment.toString().strip();
      comment.setLength(0);
      if (!text.toLowerCase(Locale.ROOT).startsWith(DELIMITER_DIRECTIVE)) {
        return;
      }
      String token = text.substring(DELIMITER_DIRECTIVE.length()).strip();
      if (!token.isEmpty()) {
        delimiter = token;
      }
    }

    private boolean endsWithDelimiter() {
      int from = current.length() - delimiter.length();
      return from >= 0 && current.indexOf(delimiter, from) == from;
    }

    /** Appends, starting the statement at the first character that is neither blank nor comment. */
    private void append(String text) {
      if (!started && text.isBlank()) {
        return;
      }
      started = true;
      current.append(text);
    }

    /** Appends comment text only once a statement is under way, so it keeps its own comments. */
    private void appendIfStarted(String text) {
      if (started) {
        current.append(text);
      }
    }

    private void flush() {
      String statement = current.toString().strip();
      current.setLength(0);
      started = false;
      if (!statement.isEmpty()) {
        out.add(statement);
      }
    }

    private int next() throws IOException {
      return pending.isEmpty() ? reader.read() : pending.removeFirst();
    }

    private boolean peekIs(char expected) throws IOException {
      int c = next();
      if (c == -1) {
        return false;
      }
      pending.addFirst((char) c);
      return c == expected;
    }

    private void pushBack(String text) {
      for (int i = text.length() - 1; i >= 0; i--) {
        pending.addFirst(text.charAt(i));
      }
    }
  }
}
