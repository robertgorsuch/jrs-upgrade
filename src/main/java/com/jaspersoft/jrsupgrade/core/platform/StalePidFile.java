package com.jaspersoft.jrsupgrade.core.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.LongPredicate;

/**
 * The {@code catalina.pid} a bundled Tomcat leaves under {@code <tomcat>/temp} when its JVM dies
 * without running the stop script; {@code catalina.sh start} refuses to start while the file names
 * a process, even one that no longer exists (installation guide 10.1 p.237, issue #113).
 * Invariants: a file is stale exactly when its first token is not a pid of a live process, an
 * unparsable file counting as stale because Tomcat would refuse over it just the same; {@link
 * #find} and {@link #stale} read only; {@link #removeIfStale} is the one mutation and deletes
 * nothing that a live process is named in.
 */
public final class StalePidFile {

  /** Where the bundled installer's {@code setenv} puts the pid file, relative to the Tomcat dir. */
  public static final String RELATIVE = "temp/catalina.pid";

  /** Liveness against this host's process table. */
  public static final LongPredicate LIVE_PROCESSES =
      pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);

  private StalePidFile() {}

  /** What the file names, when it exists: the pid, or empty for a file that holds no number. */
  public record Named(Path file, Optional<Long> pid) {

    /** True when no live process has this pid (or the file holds none). */
    public boolean stale(LongPredicate alive) {
      return pid.map(p -> !alive.test(p)).orElse(true);
    }
  }

  /** The pid file under {@code tomcatDir}, read, when there is one. */
  public static Optional<Named> find(Path tomcatDir) {
    Path file = tomcatDir.resolve("temp").resolve("catalina.pid");
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    return Optional.of(new Named(file, parse(file)));
  }

  /** The pid file under the first of {@code tomcatDirs} that has one. */
  public static Optional<Named> findUnder(List<Path> tomcatDirs) {
    for (Path dir : tomcatDirs) {
      Optional<Named> named = find(dir);
      if (named.isPresent()) {
        return named;
      }
    }
    return Optional.empty();
  }

  /** True when the pid file under {@code tomcatDir} exists and names no live process. */
  public static boolean stale(Path tomcatDir, LongPredicate alive) {
    return find(tomcatDir).map(n -> n.stale(alive)).orElse(false);
  }

  /**
   * Deletes the pid file under the first of {@code tomcatDirs} that has one, when it is stale, and
   * answers which file went. A file naming a live process, or no file at all, leaves nothing to do.
   */
  public static Optional<Named> removeIfStale(List<Path> tomcatDirs, LongPredicate alive)
      throws IOException {
    Optional<Named> named = findUnder(tomcatDirs);
    if (named.isEmpty() || !named.get().stale(alive)) {
      return Optional.empty();
    }
    Files.deleteIfExists(named.get().file());
    return named;
  }

  private static Optional<Long> parse(Path file) {
    try {
      String text = Files.readString(file, StandardCharsets.UTF_8).strip();
      int end = 0;
      while (end < text.length() && Character.isDigit(text.charAt(end))) {
        end++;
      }
      return end == 0 ? Optional.empty() : Optional.of(Long.parseLong(text.substring(0, end)));
    } catch (IOException | NumberFormatException e) {
      return Optional.empty();
    }
  }
}
