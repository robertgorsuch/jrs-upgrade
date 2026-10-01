package com.jaspersoft.jrsupgrade.core.platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Orders names the way an operator reads versions, so {@code jasperreports-server-9.0.0} comes
 * before {@code jasperreports-server-10.0.0} and {@code apache-tomcat-9} before {@code
 * apache-tomcat-10} (field test 3: a plain string sort put a JRS 9 ahead of a JRS 10). Invariants:
 * runs of ASCII digits compare by numeric value, of any length; other characters compare
 * case-insensitively; the order is total, since names that tie that way (case, leading zeros) fall
 * back to their plain string order, so two names compare equal only when they are equal.
 */
public final class NaturalOrder {

  /** {@link #compare} as a comparator. */
  public static final Comparator<String> TEXT = NaturalOrder::compare;

  /** Paths by {@link #compare} of their string form. */
  public static final Comparator<Path> PATHS = (a, b) -> compare(a.toString(), b.toString());

  private NaturalOrder() {}

  /** Negative, zero or positive as {@code a} sorts before, with or after {@code b}. */
  public static int compare(String a, String b) {
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      char ca = a.charAt(i);
      char cb = b.charAt(j);
      if (digit(ca) && digit(cb)) {
        int ei = runEnd(a, i);
        int ej = runEnd(b, j);
        int c = compareDigits(a.substring(i, ei), b.substring(j, ej));
        if (c != 0) {
          return c;
        }
        i = ei;
        j = ej;
      } else {
        int c = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
        if (c != 0) {
          return c;
        }
        i++;
        j++;
      }
    }
    int rest = Integer.compare(a.length() - i, b.length() - j);
    return rest != 0 ? rest : a.compareTo(b);
  }

  /**
   * Compares only the numbers in two names, in order, ignoring the text around them: {@code
   * "10.0.0"} is above {@code "9.0.0"}, {@code "9.0"} below {@code "9.0.0"}, and {@code
   * "apache-tomcat-9"} equal to {@code "tomcat9"}; a name without numbers is below any with one.
   */
  public static int compareVersions(String a, String b) {
    List<String> na = numbers(a);
    List<String> nb = numbers(b);
    for (int k = 0; k < Math.min(na.size(), nb.size()); k++) {
      int c = compareDigits(na.get(k), nb.get(k));
      if (c != 0) {
        return c;
      }
    }
    return Integer.compare(na.size(), nb.size());
  }

  private static List<String> numbers(String s) {
    List<String> out = new ArrayList<>();
    int i = 0;
    while (i < s.length()) {
      if (digit(s.charAt(i))) {
        int end = runEnd(s, i);
        out.add(s.substring(i, end));
        i = end;
      } else {
        i++;
      }
    }
    return out;
  }

  private static boolean digit(char c) {
    return c >= '0' && c <= '9';
  }

  private static int runEnd(String s, int start) {
    int end = start;
    while (end < s.length() && digit(s.charAt(end))) {
      end++;
    }
    return end;
  }

  /** Two digit runs by value, without overflow: leading zeros dropped, then length, then text. */
  private static int compareDigits(String a, String b) {
    String x = stripZeros(a);
    String y = stripZeros(b);
    int c = Integer.compare(x.length(), y.length());
    return c != 0 ? c : x.compareTo(y);
  }

  private static String stripZeros(String digits) {
    int k = 0;
    while (k < digits.length() - 1 && digits.charAt(k) == '0') {
      k++;
    }
    return digits.substring(k);
  }
}
