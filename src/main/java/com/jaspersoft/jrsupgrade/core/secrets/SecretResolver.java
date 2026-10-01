package com.jaspersoft.jrsupgrade.core.secrets;

import com.jaspersoft.jrsupgrade.core.platform.FileOps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Turns a {@link SecretRef} into a {@link Secret} (spec §5.2). Invariants: {@code file:} references
 * are refused unless the file is owner-only according to {@link FileOps#isOwnerOnly}; every failure
 * is a {@link SecretException} naming the reference and a remediation, never the value; the caller
 * owns the returned secret and must close it.
 */
public final class SecretResolver {

  private final Map<String, String> env;
  private final FileOps fileOps;
  private final EncryptedSecretStore store;

  public SecretResolver(Map<String, String> env, FileOps fileOps, EncryptedSecretStore store) {
    this.env = Map.copyOf(Objects.requireNonNull(env, "env"));
    this.fileOps = Objects.requireNonNull(fileOps, "fileOps");
    this.store = Objects.requireNonNull(store, "store");
  }

  public Secret resolve(SecretRef ref) {
    Objects.requireNonNull(ref, "ref");
    return switch (ref) {
      case SecretRef.Env e -> fromEnv(e);
      case SecretRef.File f -> fromFile(f);
      case SecretRef.Enc c -> fromStore(c);
    };
  }

  /**
   * True when {@link #resolve} would not prompt for anything: the variable is set, the file exists
   * (its permissions are judged by {@code resolve}), or the store's passphrase is at hand. A caller
   * that must stay silent (field test 2, D1: {@code doctor}) asks this first.
   */
  public boolean availableWithoutPrompt(SecretRef ref) {
    Objects.requireNonNull(ref, "ref");
    return switch (ref) {
      case SecretRef.Env e -> {
        String v = env.get(e.name());
        yield v != null && !v.isEmpty();
      }
      case SecretRef.File f -> Files.isRegularFile(f.path());
      case SecretRef.Enc c -> store.canUnlockWithoutPrompt();
    };
  }

  private Secret fromEnv(SecretRef.Env ref) {
    String value = env.get(ref.name());
    if (value == null || value.isEmpty()) {
      throw new SecretException(
          "environment variable "
              + ref.name()
              + " is not set (referenced by "
              + ref.render()
              + ")");
    }
    return Secret.fromString(value);
  }

  private Secret fromFile(SecretRef.File ref) {
    Path path = ref.path();
    if (!Files.isRegularFile(path)) {
      throw new SecretException(
          "secret file " + path + " does not exist (referenced by " + ref.render() + ")");
    }
    boolean ownerOnly;
    try {
      ownerOnly = fileOps.isOwnerOnly(path);
    } catch (IOException e) {
      throw new SecretException(
          "cannot inspect permissions of secret file " + path + ": " + e.getMessage(), e);
    }
    if (!ownerOnly) {
      throw new SecretException(
          "secret file "
              + path
              + " is readable by other users; restrict it to the owner only"
              + " (chmod 600 on Linux; remove ACL entries other than the owner and"
              + " Administrators on Windows)");
    }
    char[] chars;
    try {
      chars = SecretFiles.readChars(path);
    } catch (IOException e) {
      throw new SecretException("cannot read secret file " + path + ": " + e.getMessage(), e);
    }
    try {
      if (chars.length == 0) {
        throw new SecretException("secret file " + path + " is empty");
      }
      return Secret.of(chars);
    } finally {
      Arrays.fill(chars, '\0');
    }
  }

  private Secret fromStore(SecretRef.Enc ref) {
    return store
        .get(ref.name())
        .orElseThrow(
            () ->
                new SecretException(
                    "no entry '"
                        + ref.name()
                        + "' in "
                        + store.file()
                        + "; run jrs-upgrade secrets set "
                        + ref.name()));
  }
}
