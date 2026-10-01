package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.secrets.EncryptedSecretStore;
import com.jaspersoft.jrsupgrade.core.secrets.PassphraseSource;
import com.jaspersoft.jrsupgrade.core.secrets.Secret;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Opens {@code secrets.enc} for a command that stores a password the operator just typed ({@code
 * init}, {@code config set}; #63, #70). Invariants: when {@code --passphrase-file} or {@code
 * JRS_UPGRADE_PASSPHRASE} supplies the passphrase the usual store of the {@link Bootstrap} is used;
 * otherwise the passphrase is asked for through {@link Prompter}, twice for a store that does not
 * exist yet, and three mismatches or an empty answer give up; a passphrase read here is added to
 * {@code held} because the store reads it on every use, and the caller closes it when done; no
 * passphrase is ever printed.
 */
final class SecretStores {

  private SecretStores() {}

  /** The store to write to, or empty when the operator gave no passphrase. */
  static Optional<EncryptedSecretStore> forWriting(
      Bootstrap boot, GlobalOptions global, PrintWriter out, List<Secret> held) {
    EncryptedSecretStore usual = boot.secretStore();
    if (global.passphraseFile().isPresent() || Env.vars().containsKey(PassphraseSource.ENV_VAR)) {
      return Optional.of(usual);
    }
    Optional<Secret> passphrase =
        usual.exists()
            ? Prompter.secret(out, "  Passphrase of the encrypted store " + usual.file() + ": ")
                .flatMap(SecretStores::secretOf)
            : newPassphrase(out);
    if (passphrase.isEmpty()) {
      return Optional.empty();
    }
    held.add(passphrase.get());
    Platform platform = boot.services().platform();
    return Optional.of(
        new EncryptedSecretStore(
            usual.file(),
            new PassphraseSource.Fixed(passphrase.get()),
            f -> OwnerOnlyFiles.restrictToOwner(platform, f)));
  }

  private static Optional<Secret> newPassphrase(PrintWriter out) {
    out.println(
        "  Choose a passphrase for the encrypted store. jrs-upgrade asks for it when it needs a"
            + " password, and it cannot be recovered.");
    for (int attempt = 0; attempt < 3; attempt++) {
      Optional<char[]> first = Prompter.secret(out, "  New passphrase: ");
      if (first.isEmpty() || first.get().length == 0) {
        first.ifPresent(c -> Arrays.fill(c, '\0'));
        return Optional.empty();
      }
      Optional<char[]> second = Prompter.secret(out, "  Type it again: ");
      try {
        if (second.isEmpty()) {
          return Optional.empty();
        }
        if (Arrays.equals(first.get(), second.get())) {
          return Optional.of(Secret.of(first.get()));
        }
      } finally {
        Arrays.fill(first.get(), '\0');
        second.ifPresent(c -> Arrays.fill(c, '\0'));
      }
      out.println("  the passphrases differ; try again");
    }
    return Optional.empty();
  }

  /** Wraps and zeroes {@code chars}; empty for an empty answer. */
  static Optional<Secret> secretOf(char[] chars) {
    try {
      return chars.length == 0 ? Optional.empty() : Optional.of(Secret.of(chars));
    } finally {
      Arrays.fill(chars, '\0');
    }
  }
}
