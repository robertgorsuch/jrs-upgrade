package com.jaspersoft.jrsupgrade.core.platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where the SQLite driver unpacks and runs its native library. Invariant: the directory is inside
 * the jrs-upgrade home rather than the system temp directory, because a CIS-hardened Linux box
 * mounts {@code /tmp} with {@code noexec} and every stateful command would die with an {@code
 * UnsatisfiedLinkError} (review 3.4); the choice is made once, before the driver loads, and an
 * explicit {@code -Dorg.sqlite.tmpdir} from the operator always wins.
 */
public final class NativeTempDir {

  /** The driver property naming the directory the native library is unpacked into. */
  public static final String PROPERTY = "org.sqlite.tmpdir";

  private static final Logger LOG = LoggerFactory.getLogger(NativeTempDir.class);
  private static final Path PROC_MOUNTS = Path.of("/proc/mounts");
  private static final Pattern FIELDS = Pattern.compile(" ");
  private static final Pattern OPTIONS = Pattern.compile(",");

  private NativeTempDir() {}

  /**
   * Points the driver at {@code dir}, creating it, unless the operator already set the property.
   * Returns the directory in force. Never throws: a directory that cannot be created leaves the
   * driver on the system temp directory, where it may still work.
   */
  public static Path use(Path dir) {
    String existing = System.getProperty(PROPERTY);
    if (existing != null && !existing.isBlank()) {
      return Path.of(existing);
    }
    try {
      Files.createDirectories(dir);
      System.setProperty(PROPERTY, dir.toAbsolutePath().normalize().toString());
      return dir;
    } catch (IOException | RuntimeException e) {
      LOG.debug("cannot use {} for the sqlite native library", dir, e);
      return current();
    }
  }

  /** The directory the driver will use: the property when set, else the system temp directory. */
  public static Path current() {
    String value = System.getProperty(PROPERTY);
    return value == null || value.isBlank()
        ? Path.of(System.getProperty("java.io.tmpdir", "."))
        : Path.of(value);
  }

  /**
   * Why the native library cannot be executed from {@code dir}, or empty when it can. Only Linux
   * mounts can forbid it; the answer comes from the mount options of the longest mount point that
   * is a prefix of the directory.
   */
  public static Optional<String> noexecReason(Path dir) {
    return noexecReason(dir, PROC_MOUNTS);
  }

  /** As {@link #noexecReason(Path)}, reading the mount table from {@code procMounts}. */
  public static Optional<String> noexecReason(Path dir, Path procMounts) {
    if (!Files.isReadable(procMounts)) {
      return Optional.empty();
    }
    Path target = dir.toAbsolutePath().normalize();
    List<String> lines;
    try {
      lines = Files.readAllLines(procMounts, StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException e) {
      LOG.debug("cannot read {}", procMounts, e);
      return Optional.empty();
    }
    Path best = null;
    String bestOptions = "";
    for (String line : lines) {
      String[] fields = FIELDS.split(line, -1);
      if (fields.length < 4) {
        continue;
      }
      Path mount;
      try {
        mount = Path.of(unescape(fields[1])).toAbsolutePath().normalize();
      } catch (RuntimeException e) {
        continue;
      }
      if (target.startsWith(mount)
          && (best == null || mount.getNameCount() >= best.getNameCount())) {
        best = mount;
        bestOptions = fields[3];
      }
    }
    if (best == null || !optionSet(bestOptions, "noexec")) {
      return Optional.empty();
    }
    return Optional.of(
        target
            + " is on "
            + best
            + ", mounted noexec; the SQLite native library cannot be run from there");
  }

  private static boolean optionSet(String options, String name) {
    for (String option : OPTIONS.split(options, -1)) {
      if (option.equals(name)) {
        return true;
      }
    }
    return false;
  }

  /** {@code /proc/mounts} escapes spaces and tabs in mount points as octal. */
  private static String unescape(String field) {
    return field.replace("\\040", " ").replace("\\011", "\t").replace("\\134", "\\");
  }
}
