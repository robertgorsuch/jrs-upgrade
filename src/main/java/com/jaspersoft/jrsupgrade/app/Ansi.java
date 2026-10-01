package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.ops.ReportItem;
import java.nio.charset.Charset;
import java.util.Map;
import picocli.CommandLine;

/**
 * Text-output colouring and glyph choice. Invariants: colour is used only when {@code --color} or
 * the terminal says so, which on Windows means a host that actually interprets escape sequences
 * ({@code WT_SESSION}, ConEmu, a {@code TERM}), not merely a console, so cmd.exe never shows raw
 * escapes (review 3.5); non-ASCII glyphs are used only when the standard-output encoding can carry
 * them and {@code --ascii} was not given; a status is always rendered as an ASCII icon plus the
 * word ({@code x FAIL}), so the meaning survives without colour and in any code page.
 */
final class Ansi {

  static final String ESC = String.valueOf((char) 27);
  static final String RESET = ESC + "[0m";
  static final String RED = ESC + "[31m";
  static final String YELLOW = ESC + "[33m";
  static final String GREEN = ESC + "[32m";
  static final String DIM = ESC + "[2m";

  /** The glyphs {@link ProgressRenderer} draws when the output encoding can carry them. */
  static final String GLYPHS = "✔✖↻↩";

  private final boolean enabled;
  private final boolean unicode;

  Ansi(boolean enabled, boolean unicode) {
    this.enabled = enabled;
    this.unicode = unicode;
  }

  static Ansi forStdout(GlobalOptions global, Map<String, String> env) {
    boolean colour =
        switch (global.color()) {
          case ALWAYS -> true;
          case NEVER -> false;
          case AUTO -> !env.containsKey("NO_COLOR") && ansiCapable();
        };
    return new Ansi(colour, !global.ascii() && stdoutCanEncodeGlyphs());
  }

  /**
   * Whether the terminal attached to this process interprets ANSI escapes. picocli's heuristic is
   * the one to use: it knows Windows Terminal, ConEmu, ANSICON, Cygwin and the {@code TERM} and
   * {@code CLICOLOR} conventions, where a bare {@code System.console() != null} does not.
   */
  private static boolean ansiCapable() {
    return CommandLine.Help.Ansi.AUTO.enabled();
  }

  /** True when standard output is encoded in something that can carry the progress glyphs. */
  static boolean stdoutCanEncodeGlyphs() {
    return stdoutCharset().newEncoder().canEncode(GLYPHS);
  }

  /** The charset standard output is written with; the console's when one is attached. */
  static Charset stdoutCharset() {
    return Terminal.console()
        .map(java.io.Console::charset)
        .orElseGet(
            () -> {
              String name = System.getProperty("stdout.encoding");
              if (name == null) {
                name = System.getProperty("native.encoding");
              }
              try {
                return name == null ? Charset.defaultCharset() : Charset.forName(name);
              } catch (IllegalArgumentException e) {
                return Charset.defaultCharset();
              }
            });
  }

  boolean enabled() {
    return enabled;
  }

  /** True when the renderer may use the tick and arrow glyphs rather than ASCII words. */
  boolean unicode() {
    return unicode;
  }

  /** {@code + PASS}, {@code ! WARN}, {@code x FAIL}, {@code - SKIP}; coloured when enabled. */
  String status(ReportItem.Status status) {
    String word =
        switch (status) {
          case PASS -> "+ PASS";
          case WARN -> "! WARN";
          case FAIL -> "x FAIL";
          case SKIP -> "- SKIP";
        };
    if (!enabled) {
      return word;
    }
    String colour =
        switch (status) {
          case PASS -> GREEN;
          case WARN -> YELLOW;
          case FAIL -> RED;
          case SKIP -> DIM;
        };
    return colour + word + RESET;
  }

  String dim(String text) {
    return enabled ? DIM + text + RESET : text;
  }

  /** The report's closing line, in the colour of its worst status; plain when colour is off. */
  String summary(String text, ReportItem.Status worst) {
    if (!enabled) {
      return text;
    }
    String colour =
        switch (worst) {
          case FAIL -> RED;
          case WARN -> YELLOW;
          case PASS, SKIP -> GREEN;
        };
    return colour + text + RESET;
  }
}
