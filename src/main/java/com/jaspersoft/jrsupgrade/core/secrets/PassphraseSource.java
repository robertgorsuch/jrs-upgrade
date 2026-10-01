package com.jaspersoft.jrsupgrade.core.secrets;

import com.jaspersoft.jrsupgrade.core.platform.FileOps;
import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Strategy for obtaining the passphrase that unlocks {@code secrets.enc} (spec §5.2). The
 * {@linkplain #standard standard chain} tries {@code JRS_UPGRADE_PASSPHRASE}, then {@code
 * --passphrase-file}, then the interactive console. Invariant: {@link #read()} returns empty rather
 * than throwing when a source simply has nothing to offer; only {@link #require()} throws, and its
 * message always tells a non-interactive caller how to supply a passphrase.
 */
public sealed interface PassphraseSource
    permits PassphraseSource.FromEnv,
        PassphraseSource.FromFile,
        PassphraseSource.FromConsole,
        PassphraseSource.Fixed,
        PassphraseSource.Chain {

  String ENV_VAR = "JRS_UPGRADE_PASSPHRASE";

  /** The passphrase when this source can supply one, otherwise empty. */
  Optional<Secret> read();

  /**
   * True when {@link #read()} would answer without asking anyone: a set variable, an existing file,
   * a fixed value. A console source is never available in this sense, so a caller that must not
   * prompt (field test 2, D1: {@code doctor}) asks this first and leaves the store alone otherwise.
   */
  boolean availableWithoutPrompt();

  /** The passphrase, or {@link PassphraseUnavailableException} when no source has one. */
  default Secret require() {
    return read().orElseThrow(PassphraseUnavailableException::new);
  }

  /** The {@code JRS_UPGRADE_PASSPHRASE} environment variable. */
  record FromEnv(Map<String, String> env) implements PassphraseSource {
    public FromEnv {
      env = Map.copyOf(env);
    }

    @Override
    public Optional<Secret> read() {
      String v = env.get(ENV_VAR);
      return v == null || v.isEmpty() ? Optional.empty() : Optional.of(Secret.fromString(v));
    }

    @Override
    public boolean availableWithoutPrompt() {
      String v = env.get(ENV_VAR);
      return v != null && !v.isEmpty();
    }
  }

  /**
   * The file named by {@code --passphrase-file}; trailing line terminators are ignored. Refused
   * unless the file is owner-only according to {@link FileOps#isOwnerOnly}, the gate every {@code
   * file:} secret reference passes (assessment item S2): this is the master secret of {@code
   * secrets.enc}, so it is the last credential that may sit in a world-readable file.
   */
  record FromFile(Path path, FileOps files) implements PassphraseSource {
    public FromFile {
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(files, "files");
    }

    @Override
    public boolean availableWithoutPrompt() {
      return Files.isRegularFile(path);
    }

    @Override
    public Optional<Secret> read() {
      boolean ownerOnly;
      try {
        ownerOnly = files.isOwnerOnly(path);
      } catch (IOException e) {
        throw new SecretException("cannot read passphrase file " + path + ": " + e.getMessage(), e);
      }
      if (!ownerOnly) {
        throw new SecretException(
            "passphrase file "
                + path
                + " is readable by other users; restrict it to the owner only (chmod 600 on Linux;"
                + " remove ACL entries other than the owner and Administrators on Windows)");
      }
      char[] chars;
      try {
        chars = SecretFiles.readChars(path);
      } catch (IOException e) {
        throw new SecretException("cannot read passphrase file " + path + ": " + e.getMessage(), e);
      }
      try {
        return chars.length == 0 ? Optional.empty() : Optional.of(Secret.of(chars));
      } finally {
        Arrays.fill(chars, '\0');
      }
    }
  }

  /** {@link System#console()} when the process is interactive. */
  record FromConsole() implements PassphraseSource {
    // On the bundled JDK 21 runtime System.console() is null when stdin is not a TTY; JDK 22's
    // Console.isTerminal() is not available at this language level.
    @SuppressWarnings("SystemConsoleNull")
    @Override
    public Optional<Secret> read() {
      Console console = System.console();
      if (console == null) {
        return Optional.empty();
      }
      char[] chars = console.readPassword("jrs-upgrade passphrase: ");
      if (chars == null || chars.length == 0) {
        return Optional.empty();
      }
      try {
        return Optional.of(Secret.of(chars));
      } finally {
        Arrays.fill(chars, '\0');
      }
    }

    @Override
    public boolean availableWithoutPrompt() {
      return false;
    }
  }

  /** A passphrase supplied in code (tests, or a caller that already prompted). */
  record Fixed(Secret secret) implements PassphraseSource {
    public Fixed {
      Objects.requireNonNull(secret, "secret");
    }

    @Override
    public Optional<Secret> read() {
      char[] copy = secret.chars();
      try {
        return Optional.of(Secret.of(copy));
      } finally {
        Arrays.fill(copy, '\0');
      }
    }

    @Override
    public boolean availableWithoutPrompt() {
      return true;
    }
  }

  /** The first source that yields a passphrase wins. */
  record Chain(List<PassphraseSource> sources) implements PassphraseSource {
    public Chain {
      sources = List.copyOf(sources);
    }

    @Override
    public Optional<Secret> read() {
      for (PassphraseSource s : sources) {
        Optional<Secret> p = s.read();
        if (p.isPresent()) {
          return p;
        }
      }
      return Optional.empty();
    }

    @Override
    public boolean availableWithoutPrompt() {
      return sources.stream().anyMatch(PassphraseSource::availableWithoutPrompt);
    }
  }

  /** {@code JRS_UPGRADE_PASSPHRASE}, then {@code --passphrase-file}, then the console. */
  static PassphraseSource standard(
      Map<String, String> env, Optional<Path> passphraseFile, FileOps files) {
    List<PassphraseSource> chain =
        passphraseFile
            .<List<PassphraseSource>>map(
                p -> List.of(new FromEnv(env), new FromFile(p, files), new FromConsole()))
            .orElseGet(() -> List.of(new FromEnv(env), new FromConsole()));
    return new Chain(chain);
  }
}
