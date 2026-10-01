package com.jaspersoft.jrsupgrade.core.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Ed25519 signing with the JDK's built-in provider (spec §11.1, ADR-0006). Keys are exchanged as
 * single-line base64 of their standard encodings (X.509 SubjectPublicKeyInfo for public keys,
 * PKCS#8 for private keys). Invariant: no method here logs or stringifies private key material.
 */
public final class Ed25519 {

  private static final String ALGORITHM = "Ed25519";

  private Ed25519() {}

  public static KeyPair generate() {
    try {
      return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Ed25519 unavailable in this runtime", e);
    }
  }

  public static byte[] sign(PrivateKey key, byte[] data) {
    try {
      Signature s = Signature.getInstance(ALGORITHM);
      s.initSign(key);
      s.update(data);
      return s.sign();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("signing failed", e);
    }
  }

  public static boolean verify(PublicKey key, byte[] data, byte[] signature) {
    try {
      Signature s = Signature.getInstance(ALGORITHM);
      s.initVerify(key);
      s.update(data);
      return s.verify(signature);
    } catch (GeneralSecurityException e) {
      return false;
    }
  }

  public static String encodePublic(PublicKey key) {
    return Base64.getEncoder().encodeToString(key.getEncoded());
  }

  public static String encodePrivate(PrivateKey key) {
    return Base64.getEncoder().encodeToString(key.getEncoded());
  }

  public static PublicKey decodePublic(String base64) {
    try {
      byte[] der = Base64.getDecoder().decode(base64.strip());
      return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(der));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalArgumentException("not an Ed25519 public key", e);
    }
  }

  public static PrivateKey decodePrivate(String base64) {
    try {
      byte[] der = Base64.getDecoder().decode(base64.strip());
      return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalArgumentException("not an Ed25519 private key", e);
    }
  }

  /** Short stable identifier of a public key for display: first 16 hex chars of its SHA-256. */
  public static String fingerprint(PublicKey key) {
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
      return java.util.HexFormat.of().formatHex(digest, 0, 8);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Encodes a detached signature for the {@code SIGNATURE} file: one base64 line. */
  public static String encodeSignature(byte[] signature) {
    return Base64.getEncoder().encodeToString(signature);
  }

  public static byte[] decodeSignature(String text) {
    return Base64.getDecoder().decode(text.strip().getBytes(StandardCharsets.US_ASCII));
  }
}
