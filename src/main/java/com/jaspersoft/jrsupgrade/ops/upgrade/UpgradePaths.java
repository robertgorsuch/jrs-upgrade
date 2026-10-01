package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.compat.CompatMatrix;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.Mode;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The one sentence planning and preflight both give for an upgrade the matrix does not list (review
 * §1.3): the vendor offers each source-to-target pair in one or both modes, and a pair it offers
 * only in the other mode is named as such rather than reported as unknown. Invariant: empty exactly
 * when the matrix lists {@code from -> to} in {@code mode}.
 */
final class UpgradePaths {

  private UpgradePaths() {}

  static String wire(Mode mode) {
    return mode.name().toLowerCase(Locale.ROOT);
  }

  static Optional<String> problem(CompatMatrix matrix, String from, String to, Mode mode) {
    if (matrix.upgradePathSupported(from, to, wire(mode))) {
      return Optional.empty();
    }
    Set<String> offered = matrix.upgradeModes(from, to);
    String pair = "upgrade path " + from + " -> " + to;
    if (offered.isEmpty()) {
      return Optional.of(pair + " is not in the compatibility matrix");
    }
    return Optional.of(
        pair
            + " is not offered in "
            + wire(mode)
            + " mode; the vendor offers "
            + String.join(" or ", offered)
            + " (--mode)");
  }
}
