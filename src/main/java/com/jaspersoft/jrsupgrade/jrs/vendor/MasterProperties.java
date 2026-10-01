package com.jaspersoft.jrsupgrade.jrs.vendor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Stages property overrides into {@code default_master.properties} of the buildomatic directory
 * being invoked and restores the original afterwards (spec §7.4, open question Q5 in §19).
 * Invariants: the pre-existing file is copied to {@code <runDir>/default_master.properties.bak}
 * before the first modification and that copy is never overwritten, so re-executing {@link #stage}
 * after a crash still rebuilds from the pristine original; overrides are appended after the
 * original content in {@link Properties} syntax (last key wins) so vendor comments and every
 * existing key, including passwords, survive untouched; jrs-upgrade itself never adds a key whose
 * name contains {@code pass}, only keys the caller passed explicitly; {@link #restore} puts the
 * backup back or deletes the staged file when there was none, and is idempotent; all copies stream.
 */
public final class MasterProperties {

  public static final String BACKUP_NAME = Buildomatic.MASTER_PROPERTIES + ".bak";
  public static final String OVERRIDE_HEADER = "jrs-upgrade overrides (restored after the run)";

  private MasterProperties() {}

  /** What {@link #stage} did; {@code backup} is present only when an original file existed. */
  public record Staged(Path file, Optional<Path> backup, Map<String, String> overrides) {
    public Staged {
      Objects.requireNonNull(file, "file");
      Objects.requireNonNull(backup, "backup");
      overrides = Map.copyOf(overrides);
    }
  }

  /** Path of the backup {@link #stage} takes for {@code runDir}. */
  public static Path backupFor(Path runDir) {
    return runDir.resolve(BACKUP_NAME);
  }

  /**
   * Writes {@code overrides} into {@code buildomaticDir/default_master.properties}, preserving the
   * original content. Keys containing {@code pass} are written only because the caller asked for
   * them; nothing here adds one.
   */
  public static Staged stage(Path buildomaticDir, Map<String, String> overrides, Path runDir)
      throws IOException {
    Objects.requireNonNull(buildomaticDir, "buildomaticDir");
    Objects.requireNonNull(overrides, "overrides");
    Objects.requireNonNull(runDir, "runDir");
    for (Map.Entry<String, String> e : overrides.entrySet()) {
      if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) {
        throw new IllegalArgumentException("override keys and values must be non-blank");
      }
    }
    Path file = buildomaticDir.resolve(Buildomatic.MASTER_PROPERTIES);
    Path backup = backupFor(runDir);
    Files.createDirectories(runDir);
    if (!Files.exists(backup) && Files.isRegularFile(file)) {
      Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
    }
    Optional<Path> original = Files.isRegularFile(backup) ? Optional.of(backup) : Optional.empty();
    Path tmp = buildomaticDir.resolve(Buildomatic.MASTER_PROPERTIES + ".jrs-upgrade-tmp");
    try (OutputStream out = Files.newOutputStream(tmp)) {
      if (original.isPresent()) {
        try (InputStream in = Files.newInputStream(original.get())) {
          in.transferTo(out);
        }
        out.write(System.lineSeparator().getBytes(StandardCharsets.ISO_8859_1));
      }
      Properties p = new Properties();
      p.putAll(overrides);
      p.store(new NonClosing(out), OVERRIDE_HEADER);
    }
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    return new Staged(file, original, overrides);
  }

  /**
   * Puts the original back (or removes the staged file when there was none) and drops the backup.
   * Idempotent: once the backup is consumed, only a file that still carries the {@link
   * #OVERRIDE_HEADER} is touched, so a second call after a complete first one changes nothing
   * instead of deleting the restored original.
   */
  public static void restore(Path buildomaticDir, Path runDir) throws IOException {
    Objects.requireNonNull(buildomaticDir, "buildomaticDir");
    Objects.requireNonNull(runDir, "runDir");
    Path file = buildomaticDir.resolve(Buildomatic.MASTER_PROPERTIES);
    Path backup = backupFor(runDir);
    if (Files.isRegularFile(backup)) {
      Files.copy(
          backup, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
      Files.delete(backup);
    } else if (isStaged(file)) {
      Files.deleteIfExists(file);
    }
  }

  /** True when {@code file} exists and carries the override header {@link #stage} writes. */
  public static boolean isStaged(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return false;
    }
    try (Stream<String> lines = Files.lines(file, StandardCharsets.ISO_8859_1)) {
      return lines.anyMatch(line -> line.startsWith("#" + OVERRIDE_HEADER));
    }
  }

  /** True when {@code key} names something that must never be logged or invented by jrs-upgrade. */
  public static boolean isPasswordKey(String key) {
    return key.toLowerCase(Locale.ROOT).contains("pass");
  }

  private static final class NonClosing extends java.io.FilterOutputStream {
    NonClosing(OutputStream out) {
      super(out);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      out.write(b, off, len);
    }

    @Override
    public void close() throws IOException {
      out.flush();
    }
  }
}
