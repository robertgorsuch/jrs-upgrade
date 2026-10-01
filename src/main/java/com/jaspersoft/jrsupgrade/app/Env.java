package com.jaspersoft.jrsupgrade.app;

import java.util.Map;
import java.util.Optional;

/**
 * The process environment as the commands see it. Invariant: production code always reads {@link
 * System#getenv()}; the override exists so unit tests can supply {@code JRS_UPGRADE_PASSPHRASE} or
 * {@code JRS_PASSWORD} without touching the real environment, and it is never set outside tests.
 */
final class Env {

  private static volatile Optional<Map<String, String>> override = Optional.empty();

  private Env() {}

  static Map<String, String> vars() {
    return override.orElseGet(System::getenv);
  }

  /** Test seam: replaces the environment until {@link #reset()}. */
  static void override(Map<String, String> vars) {
    override = Optional.of(Map.copyOf(vars));
  }

  static void reset() {
    override = Optional.empty();
  }
}
