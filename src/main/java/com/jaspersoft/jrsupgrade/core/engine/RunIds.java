package com.jaspersoft.jrsupgrade.core.engine;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Run identifiers of the form {@code r-YYYYMMDD-HHmmss-xxxx} (UTC timestamp plus four random hex
 * digits). Invariant: ids sort chronologically as strings and are file-system safe, so a run id
 * doubles as the name of its directory under {@code $JRS_UPGRADE_HOME/runs}.
 */
public final class RunIds {

  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  private RunIds() {}

  public static String next(Clock clock) {
    int suffix = ThreadLocalRandom.current().nextInt(0x10000);
    return "r-" + STAMP.format(clock.instant()) + "-" + HexFormat.of().toHexDigits((short) suffix);
  }
}
