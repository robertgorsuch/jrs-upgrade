package com.jaspersoft.jrsupgrade.ops.smoke;

/**
 * Flags for {@code smoke} (spec §12.2). Invariant: without {@code mutating} the operation sends no
 * repository-changing request; with it, the only mutations happen inside the smoke {@code Plan}.
 */
public record SmokeOptions(boolean mutating) {
  public static final SmokeOptions DEFAULT = new SmokeOptions(false);
}
