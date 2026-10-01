package com.jaspersoft.jrsupgrade.core.secrets;

/**
 * No passphrase source could supply the passphrase for {@code secrets.enc} (spec §5.2). Invariant:
 * the message always tells a non-interactive caller how to provide one.
 */
public final class PassphraseUnavailableException extends SecretException {

  private static final long serialVersionUID = 1L;

  public static final String REMEDIATION =
      "set JRS_UPGRADE_PASSPHRASE or pass --passphrase-file for non-interactive runs";

  public PassphraseUnavailableException() {
    super("no passphrase available to unlock secrets.enc: " + REMEDIATION);
  }
}
