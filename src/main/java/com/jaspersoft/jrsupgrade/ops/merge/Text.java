package com.jaspersoft.jrsupgrade.ops.merge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A text file as the lines a merge works on, and back. Invariants: bytes are read and written as
 * ISO-8859-1, which maps every byte to itself, so no character of any encoding is changed; a line
 * holds neither its LF nor the CR before it; the line ends and the final line end of one text can
 * be given to another, so a merged file is written in the target file's line ends (ported from
 * jrs-hotfix).
 */
public record Text(List<String> lines, String eol, boolean finalEol) {

  public Text {
    lines = List.copyOf(lines);
  }

  public static Text of(byte[] bytes) {
    String text = new String(bytes, StandardCharsets.ISO_8859_1);
    List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
    boolean finalEol = lines.get(lines.size() - 1).isEmpty();
    if (finalEol) {
      lines.remove(lines.size() - 1);
    }
    boolean crlf = text.contains("\r\n");
    lines.replaceAll(l -> l.endsWith("\r") ? l.substring(0, l.length() - 1) : l);
    return new Text(lines, crlf ? "\r\n" : "\n", finalEol && !text.isEmpty());
  }

  /** {@code merged} written in the line ends of this text. */
  public byte[] bytes(List<String> merged) {
    if (merged.isEmpty()) {
      return new byte[0];
    }
    String text = String.join(eol, merged) + (finalEol ? eol : "");
    return text.getBytes(StandardCharsets.ISO_8859_1);
  }
}
