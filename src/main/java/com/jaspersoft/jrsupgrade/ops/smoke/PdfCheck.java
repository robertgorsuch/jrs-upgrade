package com.jaspersoft.jrsupgrade.ops.smoke;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

/**
 * Verifies a report output the way spec §12.2 asks: the file starts with {@code %PDF} and is larger
 * than 1 KB. Invariant: only the first four bytes are read; the size comes from the file system, so
 * a multi-megabyte PDF costs nothing to check.
 */
final class PdfCheck {

  static final long MIN_BYTES = 1024;
  private static final byte[] MAGIC = "%PDF".getBytes(StandardCharsets.US_ASCII);

  private PdfCheck() {}

  /** Empty when the file is a plausible PDF; otherwise the problem in one line. */
  static Optional<String> problem(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return Optional.of("no output file was produced");
    }
    byte[] head = new byte[MAGIC.length];
    int read;
    try (InputStream in = Files.newInputStream(file)) {
      read = in.readNBytes(head, 0, head.length);
    }
    if (read < MAGIC.length || !Arrays.equals(head, MAGIC)) {
      return Optional.of("output does not start with %PDF");
    }
    long size = Files.size(file);
    if (size <= MIN_BYTES) {
      return Optional.of("output is only " + size + " bytes; expected more than 1 KB");
    }
    return Optional.empty();
  }
}
