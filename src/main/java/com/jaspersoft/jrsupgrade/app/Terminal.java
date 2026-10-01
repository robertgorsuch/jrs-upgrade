package com.jaspersoft.jrsupgrade.app;

import java.io.Console;
import java.util.Map;
import java.util.Optional;

/**
 * The process console, if any. Invariant: on the JDK 21 runtime jrs-upgrade ships with, a present
 * console means stdin and stdout are a terminal, which is what interactivity and colour decisions
 * are based on; there is no other source of that fact.
 */
final class Terminal {

  private Terminal() {}

  static Optional<Console> console() {
    return Optional.ofNullable(System.console());
  }

  static boolean present() {
    return console().isPresent();
  }

  /** Width used when the terminal's is unknown: what a default terminal window shows. */
  static final int DEFAULT_WIDTH = 80;

  static final int MIN_WIDTH = 40;
  static final int MAX_WIDTH = 400;

  /**
   * The width to render at (field test 2, G8): {@code COLUMNS} from {@code env} when it is a number
   * between {@link #MIN_WIDTH} and {@link #MAX_WIDTH}, else {@link #DEFAULT_WIDTH}. The JVM cannot
   * ask the terminal itself, so bash users export {@code COLUMNS} to get wider output.
   */
  static int width(Map<String, String> env) {
    return Optional.ofNullable(env.get("COLUMNS"))
        .map(String::strip)
        .filter(v -> v.matches("\\d{1,4}"))
        .map(Integer::parseInt)
        .filter(w -> w >= MIN_WIDTH && w <= MAX_WIDTH)
        .orElse(DEFAULT_WIDTH);
  }
}
