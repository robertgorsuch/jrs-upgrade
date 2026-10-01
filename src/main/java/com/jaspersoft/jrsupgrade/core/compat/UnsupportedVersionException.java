package com.jaspersoft.jrsupgrade.core.compat;

/**
 * A JasperReports Server version that the bundled compatibility matrix does not list (spec §5.7).
 * Invariant: the message names the version and points at {@code --allow-unsupported}.
 */
public final class UnsupportedVersionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UnsupportedVersionException(String version) {
    super(
        "JasperReports Server version '"
            + version
            + "' is not in the compatibility matrix; pass --allow-unsupported to proceed anyway");
  }
}
