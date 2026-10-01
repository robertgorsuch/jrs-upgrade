package com.jaspersoft.jrsupgrade.jrs.rest;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Builds the {@code j_username}/{@code j_password} login form body from a password held as {@code
 * char[]} (issue #51), without a {@link String} copy of the password. Invariants: the bytes are
 * exactly what {@link URLEncoder} produces with UTF-8 (letters, digits and {@code . - * _} as-is,
 * space as {@code +}, every other byte as upper-case {@code %XX}); the intermediate UTF-8 buffer is
 * zeroed before returning; the caller zeroes the returned array once it has been sent.
 */
final class FormBodies {

  private static final byte[] HEX = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);

  private FormBodies() {}

  static byte[] login(String username, char[] password) {
    byte[] prefix =
        ("j_username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&j_password=")
            .getBytes(StandardCharsets.US_ASCII);
    ByteBuffer utf8 = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
    try {
      int length = prefix.length;
      for (int i = utf8.position(); i < utf8.limit(); i++) {
        byte b = utf8.get(i);
        length += (b == ' ' || unreserved(b)) ? 1 : 3;
      }
      byte[] body = Arrays.copyOf(prefix, length);
      int at = prefix.length;
      for (int i = utf8.position(); i < utf8.limit(); i++) {
        byte b = utf8.get(i);
        if (b == ' ') {
          body[at++] = '+';
        } else if (unreserved(b)) {
          body[at++] = b;
        } else {
          body[at++] = '%';
          body[at++] = HEX[(b >> 4) & 0xF];
          body[at++] = HEX[b & 0xF];
        }
      }
      return body;
    } finally {
      // the same zeroing RestClient.useBasic applies to its encoded credentials
      utf8.clear();
      while (utf8.hasRemaining()) {
        utf8.put((byte) 0);
      }
    }
  }

  private static boolean unreserved(byte b) {
    return (b >= 'a' && b <= 'z')
        || (b >= 'A' && b <= 'Z')
        || (b >= '0' && b <= '9')
        || b == '.'
        || b == '-'
        || b == '*'
        || b == '_';
  }
}
