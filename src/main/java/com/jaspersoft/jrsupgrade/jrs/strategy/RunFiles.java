package com.jaspersoft.jrsupgrade.jrs.strategy;

import com.jaspersoft.jrsupgrade.core.engine.Context;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Small run-scoped files under {@code $JRS_UPGRADE_HOME/runs/<runId>} that make steps idempotent
 * across a crash: the export/import task handle, the "we stopped the service" marker, the keystore
 * backup manifest. Invariant: writes go through a temp file and an atomic move so a reader never
 * sees a half-written value; a missing file reads as empty, never as an error.
 */
final class RunFiles {

  static final String EXPORT_HANDLE = "export-handle.txt";
  static final String IMPORT_HANDLE = "import-handle.txt";
  static final String IMPORT_STARTED = "import-started.txt";

  private RunFiles() {}

  static Path in(Context ctx, String name) {
    return ctx.home().runDir(ctx.runId()).resolve(name);
  }

  static Optional<String> read(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    String text = Files.readString(file, StandardCharsets.UTF_8).strip();
    return text.isEmpty() ? Optional.empty() : Optional.of(text);
  }

  static void write(Path file, String text) throws IOException {
    Files.createDirectories(file.getParent());
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    Files.writeString(tmp, text, StandardCharsets.UTF_8);
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
  }

  static void delete(Path file) throws IOException {
    Files.deleteIfExists(file);
  }

  /** Renames {@code source} onto {@code target}, replacing it; atomic where the volume allows. */
  static void replace(Path source, Path target) throws IOException {
    Files.deleteIfExists(target);
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /** {@code <file>.part}: where a download or export is written before the final rename. */
  static Path partOf(Path file) {
    return file.resolveSibling(file.getFileName() + ".part");
  }
}
