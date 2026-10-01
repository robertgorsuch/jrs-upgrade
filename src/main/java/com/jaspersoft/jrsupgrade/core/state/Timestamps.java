package com.jaspersoft.jrsupgrade.core.state;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * The one text form for every timestamp column in {@code state.db} (review finding 1.14).
 * Invariants: fixed width, UTC, exactly three fractional digits, so lexical order in SQL is
 * chronological order; encoding truncates to the millisecond; decoding accepts any ISO-8601
 * instant, so rows written before the codec existed still read.
 */
public final class Timestamps {

  private static final DateTimeFormatter FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private Timestamps() {}

  public static String encode(Instant instant) {
    return FORMAT.format(instant.truncatedTo(ChronoUnit.MILLIS));
  }

  public static Instant decode(String text) {
    return Instant.parse(text);
  }
}
