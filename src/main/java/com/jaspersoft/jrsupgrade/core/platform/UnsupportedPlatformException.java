package com.jaspersoft.jrsupgrade.core.platform;

/**
 * Raised when the host operating system or architecture is outside the pair ADR-0002 supports
 * (Windows or Linux on x86-64). Invariant: the message always names the observed {@code os.name}
 * and {@code os.arch} and cites ADR-0002, and the command exits 6 (unsupported) without touching
 * anything, because every path below this point assumes Windows service control or Linux {@code
 * /proc} semantics (review 3.1).
 */
public final class UnsupportedPlatformException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UnsupportedPlatformException(String message) {
    super(message);
  }
}
