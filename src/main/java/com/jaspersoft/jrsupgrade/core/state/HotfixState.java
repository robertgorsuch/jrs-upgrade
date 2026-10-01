package com.jaspersoft.jrsupgrade.core.state;

/** Lifecycle of a row in {@code hotfixes_installed} (spec §5.4). */
public enum HotfixState {
  INSTALLED,
  ROLLED_BACK,
  SUPERSEDED
}
