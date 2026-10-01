package com.jaspersoft.jrsupgrade.ops.customizations;

/**
 * Orders the free-form versions jars carry ({@code 3.14.0}, {@code 5.8.9.RELEASE}, {@code 2.0.21-
 * patched}). Invariants: versions split into parts at {@code . - _ +}; numeric parts compare as
 * numbers and come before text parts; a missing part counts as {@code 0}, so {@code 1.2} equals
 * {@code 1.2.0}; text parts compare case-insensitively.
 */
final class Versions {

  private static final java.util.regex.Pattern PARTS = java.util.regex.Pattern.compile("[.\\-_+]");

  private Versions() {}

  static int compare(String a, String b) {
    String[] x = PARTS.split(a.strip(), -1);
    String[] y = PARTS.split(b.strip(), -1);
    for (int i = 0; i < Math.max(x.length, y.length); i++) {
      String p = i < x.length ? x[i] : "0";
      String q = i < y.length ? y[i] : "0";
      boolean pn = p.matches("\\d+");
      boolean qn = q.matches("\\d+");
      int c;
      if (pn && qn) {
        c = new java.math.BigInteger(p).compareTo(new java.math.BigInteger(q));
      } else if (pn != qn) {
        c = pn ? -1 : 1;
      } else {
        c = p.compareToIgnoreCase(q);
      }
      if (c != 0) {
        return c;
      }
    }
    return 0;
  }
}
