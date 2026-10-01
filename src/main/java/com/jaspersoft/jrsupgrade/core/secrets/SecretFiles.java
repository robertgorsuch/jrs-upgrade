package com.jaspersoft.jrsupgrade.core.secrets;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Reads small secret files into {@code char[]} without ever creating a {@link String}. */
final class SecretFiles {

  private static final int MAX_CHARS = 64 * 1024;

  private SecretFiles() {}

  /** File content as chars with trailing line terminators removed; intermediate buffers zeroed. */
  static char[] readChars(Path file) throws IOException {
    char[] buf = new char[256];
    int len = 0;
    try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      int n;
      while ((n = in.read(buf, len, buf.length - len)) != -1) {
        len += n;
        if (len == buf.length) {
          if (len >= MAX_CHARS) {
            throw new IOException("secret file " + file + " exceeds " + MAX_CHARS + " characters");
          }
          char[] bigger = Arrays.copyOf(buf, buf.length * 2);
          Arrays.fill(buf, '\0');
          buf = bigger;
        }
      }
    }
    while (len > 0 && (buf[len - 1] == '\n' || buf[len - 1] == '\r')) {
      len--;
    }
    char[] out = Arrays.copyOf(buf, len);
    Arrays.fill(buf, '\0');
    return out;
  }
}
