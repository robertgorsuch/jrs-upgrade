package com.jaspersoft.jrsupgrade.core.secrets;

/**
 * A secret could not be resolved, stored or unlocked. Invariant: the message names the reference or
 * entry and a remediation, never the secret value or the passphrase.
 */
public class SecretException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SecretException(String message) {
    super(message);
  }

  public SecretException(String message, Throwable cause) {
    super(message, cause);
  }
}
