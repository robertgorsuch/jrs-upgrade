package com.jaspersoft.jrsupgrade.core.keys;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.crypto.Ed25519;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Trusted public keys for bundle verification (spec §11.1): the bundled Jaspersoft publisher key
 * plus customer keys under {@code $JRS_UPGRADE_HOME/keys/trusted/<name>.pub}. Invariants: the
 * publisher key cannot be removed; a key file is one base64 line; verification reports which key
 * matched so the plan can show it.
 */
public final class KeyRing {

  public static final String PUBLISHER = "jaspersoft-publisher";
  private static final String PUBLISHER_RESOURCE = "/keys/jaspersoft-publisher.pub";

  /** A trusted key with its origin. */
  public record TrustedKey(String name, PublicKey key, boolean bundled) {
    public String fingerprint() {
      return Ed25519.fingerprint(key);
    }
  }

  private final Path dir;

  public KeyRing(JrsUpgradeHome home) {
    this.dir = home.trustedKeys();
  }

  public List<TrustedKey> list() {
    List<TrustedKey> keys = new ArrayList<>();
    publisherKey().ifPresent(keys::add);
    if (Files.isDirectory(dir)) {
      try (Stream<Path> files = Files.list(dir)) {
        files
            .filter(p -> p.getFileName().toString().endsWith(".pub"))
            .sorted()
            .forEach(
                p -> {
                  String name = p.getFileName().toString().replaceFirst("\\.pub$", "");
                  if (!name.equals(PUBLISHER)) {
                    keys.add(new TrustedKey(name, read(p), false));
                  }
                });
      } catch (IOException e) {
        throw new UncheckedIOException("cannot list " + dir, e);
      }
    }
    return List.copyOf(keys);
  }

  public Optional<TrustedKey> find(String name) {
    return list().stream().filter(k -> k.name().equals(name)).findFirst();
  }

  public TrustedKey add(String name, PublicKey key) {
    requireValidName(name);
    if (name.equals(PUBLISHER)) {
      throw new IllegalArgumentException("the publisher key is bundled and cannot be replaced");
    }
    try {
      Files.createDirectories(dir);
      Files.writeString(dir.resolve(name + ".pub"), Ed25519.encodePublic(key) + "\n");
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write key " + name, e);
    }
    return new TrustedKey(name, key, false);
  }

  public boolean remove(String name) {
    // The same name rule add() applies: a relative path would otherwise reach any *.pub under
    // the home (assessment item S5).
    requireValidName(name);
    if (name.equals(PUBLISHER)) {
      throw new IllegalArgumentException("the publisher key is bundled and cannot be removed");
    }
    try {
      return Files.deleteIfExists(dir.resolve(name + ".pub"));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot remove key " + name, e);
    }
  }

  /** Returns the first trusted key that verifies {@code signature} over {@code data}. */
  public Optional<TrustedKey> verify(byte[] data, byte[] signature) {
    return list().stream().filter(k -> Ed25519.verify(k.key(), data, signature)).findFirst();
  }

  private static Optional<TrustedKey> publisherKey() {
    try (InputStream in = KeyRing.class.getResourceAsStream(PUBLISHER_RESOURCE)) {
      if (in == null) {
        return Optional.empty();
      }
      String text = new String(in.readAllBytes(), StandardCharsets.US_ASCII).strip();
      if (text.isEmpty() || text.startsWith("#")) {
        return Optional.empty();
      }
      return Optional.of(new TrustedKey(PUBLISHER, Ed25519.decodePublic(text), true));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static PublicKey read(Path file) {
    try {
      return Ed25519.decodePublic(Files.readString(file, StandardCharsets.US_ASCII));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read key " + file, e);
    }
  }

  private static void requireValidName(String name) {
    if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
      throw new IllegalArgumentException(
          "key name must be 1-64 chars of letters, digits, '.', '_' or '-': " + name);
    }
  }
}
