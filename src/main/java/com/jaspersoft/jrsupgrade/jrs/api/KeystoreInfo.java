package com.jaspersoft.jrsupgrade.jrs.api;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Where the server keystore lives and its fingerprint (spec §9.3). Invariant: {@code present} is
 * false when the server has no {@code KEYSTORE_ENCRYPTION} capability or the files cannot be read
 * from the location the server names or {@code server.runAsUser}'s home; the fingerprint is the
 * SHA-256 of {@code .jrsks}; {@code exposure} names a found file that accounts other than its owner
 * can read (the vendor asks for 600/640) and is empty when the files are owner-only or were not
 * found.
 */
public record KeystoreInfo(
    boolean present,
    Optional<Path> keystoreFile,
    Optional<Path> propertiesFile,
    Optional<String> fingerprint,
    Optional<String> reason,
    Optional<String> exposure) {

  /** A result whose permissions were not checked or raised nothing. */
  public KeystoreInfo(
      boolean present,
      Optional<Path> keystoreFile,
      Optional<Path> propertiesFile,
      Optional<String> fingerprint,
      Optional<String> reason) {
    this(present, keystoreFile, propertiesFile, fingerprint, reason, Optional.empty());
  }

  public static KeystoreInfo absent(String reason) {
    return new KeystoreInfo(
        false, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(reason));
  }
}
