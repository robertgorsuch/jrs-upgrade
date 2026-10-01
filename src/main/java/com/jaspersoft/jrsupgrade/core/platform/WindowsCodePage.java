package com.jaspersoft.jrsupgrade.core.platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The code page Windows console programs write with. Invariant: {@code sc.exe}, {@code reg.exe} and
 * the other console tools jrs-upgrade reads emit the OEM console code page (cp437, cp850, …), not
 * the ANSI code page that {@code native.encoding} reports, so decoding their output with {@code
 * native.encoding} mangles any non-ASCII install path and {@code init} then misses the installation
 * (review 3.5). The answer is {@code chcp.com}, which reports the console output code page whether
 * or not a console is attached; it is resolved once per JVM and falls back to {@code
 * native.encoding} when the probe cannot be run.
 */
public final class WindowsCodePage {

  /** System property that overrides detection, for operators on an unusual code page. */
  public static final String PROPERTY = "jrs-upgrade.console.encoding";

  private static final Logger LOG = LoggerFactory.getLogger(WindowsCodePage.class);
  private static final Pattern TRAILING_NUMBER = Pattern.compile(".*?(\\d{3,5})\\s*\\.?\\s*$");
  private static final long PROBE_TIMEOUT_SECONDS = 10;

  private static volatile Charset cached;

  private WindowsCodePage() {}

  /**
   * Charset for decoding the output of a Windows console program, resolved once and remembered. On
   * anything other than Windows this is {@link DefaultProcessRunner#nativeCharset()}.
   */
  public static Charset consoleCharset() {
    Charset local = cached;
    if (local == null) {
      synchronized (WindowsCodePage.class) {
        local = cached;
        if (local == null) {
          local = resolve();
          cached = local;
        }
      }
    }
    return local;
  }

  /** Forgets the resolved charset; for tests that change the inputs. */
  static void forget() {
    cached = null;
  }

  private static Charset resolve() {
    Optional<Charset> override = forCodePageOrName(System.getProperty(PROPERTY));
    if (override.isPresent()) {
      return override.get();
    }
    if (Platforms.osFamily(System.getProperty("os.name", "")).orElse(null)
        != Platform.OsFamily.WINDOWS) {
      return DefaultProcessRunner.nativeCharset();
    }
    return probeChcp().orElseGet(DefaultProcessRunner::nativeCharset);
  }

  /** Runs {@code chcp.com} and reads the active code page from its one line of ASCII output. */
  private static Optional<Charset> probeChcp() {
    Process process;
    try {
      process = new ProcessBuilder(List.of("chcp.com")).redirectErrorStream(true).start();
    } catch (IOException | RuntimeException e) {
      LOG.debug("cannot run chcp.com", e);
      return Optional.empty();
    }
    try {
      // Wait first, then read: reading to end of stream before the timed wait would let a wedged
      // chcp.com hang the first process run of every command (assessment item P6). Its one line
      // of output is far below the pipe buffer, so the wait cannot deadlock on a full pipe.
      if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return Optional.empty();
      }
      String output;
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.US_ASCII))) {
        output = reader.lines().reduce("", (a, b) -> a.isEmpty() ? b : a);
      }
      return codePageOf(output);
    } catch (IOException | RuntimeException e) {
      LOG.debug("cannot read chcp.com output", e);
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } finally {
      process.destroyForcibly();
    }
  }

  /** The charset named by a {@code chcp.com} line such as {@code Active code page: 850}. */
  static Optional<Charset> codePageOf(String chcpLine) {
    if (chcpLine == null) {
      return Optional.empty();
    }
    Matcher m = TRAILING_NUMBER.matcher(chcpLine.strip());
    return m.matches() ? forCodePageOrName(m.group(1)) : Optional.empty();
  }

  /** Resolves {@code 850}, {@code cp850} or {@code IBM850} to a charset, if the JDK has it. */
  static Optional<Charset> forCodePageOrName(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    String text = value.strip();
    List<String> candidates =
        text.chars().allMatch(Character::isDigit)
            ? List.of("65001".equals(text) ? "UTF-8" : "IBM" + text, "windows-" + text, "cp" + text)
            : List.of(text);
    for (String candidate : candidates) {
      try {
        return Optional.of(Charset.forName(candidate));
      } catch (IllegalArgumentException e) {
        // try the next spelling
      }
    }
    LOG.debug("no charset for console code page {}", text);
    return Optional.empty();
  }
}
