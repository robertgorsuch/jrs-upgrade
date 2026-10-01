package com.jaspersoft.jrsupgrade.core.secrets;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Where a secret lives (spec §5.2): an environment variable, an owner-only file, or an entry in
 * {@code secrets.enc}. Invariant: a parsed reference always {@link #render()}s back to a string
 * that {@link #parse} accepts again, and a reference never carries the secret value itself, so it
 * is safe to log and to write into {@code config.yaml}.
 */
public sealed interface SecretRef permits SecretRef.Env, SecretRef.File, SecretRef.Enc {

  /** {@code env:NAME}: the value of an environment variable. */
  record Env(String name) implements SecretRef {
    public Env {
      Objects.requireNonNull(name, "name");
      if (!ENV_NAME.matcher(name).matches()) {
        throw new IllegalArgumentException(
            "invalid secret reference env:" + name + " (expected an environment variable name)");
      }
    }

    @Override
    public String render() {
      return "env:" + name;
    }
  }

  /** {@code file:/path}: the trimmed contents of a file that only the owner can read. */
  record File(Path path) implements SecretRef {
    public File {
      Objects.requireNonNull(path, "path");
    }

    @Override
    public String render() {
      return "file:" + path;
    }
  }

  /** {@code enc:NAME}: an entry in the encrypted store {@code $JRS_UPGRADE_HOME/secrets.enc}. */
  record Enc(String name) implements SecretRef {
    public Enc {
      Objects.requireNonNull(name, "name");
      if (!ENC_NAME.matcher(name).matches()) {
        throw new IllegalArgumentException(
            "invalid secret reference enc:" + name + " (expected letters, digits, '_', '.', '-')");
      }
    }

    @Override
    public String render() {
      return "enc:" + name;
    }
  }

  Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  Pattern ENC_NAME = Pattern.compile("[A-Za-z0-9_.-]+");

  /** The canonical {@code kind:value} spelling accepted by {@link #parse}. */
  String render();

  /**
   * Parses {@code env:NAME}, {@code file:/path} or {@code enc:NAME}.
   *
   * @throws IllegalArgumentException with a message naming the accepted forms for anything else
   */
  static SecretRef parse(String text) {
    Objects.requireNonNull(text, "text");
    String trimmed = text.strip();
    int colon = trimmed.indexOf(':');
    if (colon <= 0 || colon == trimmed.length() - 1) {
      throw new IllegalArgumentException(unrecognised(trimmed));
    }
    String kind = trimmed.substring(0, colon).toLowerCase(Locale.ROOT);
    String value = trimmed.substring(colon + 1);
    return switch (kind) {
      case "env" -> new Env(value);
      case "file" -> new File(Path.of(value));
      case "enc" -> new Enc(value);
      default -> throw new IllegalArgumentException(unrecognised(trimmed));
    };
  }

  private static String unrecognised(String text) {
    return "unrecognised secret reference '"
        + text
        + "': expected env:NAME, file:/path or enc:NAME";
  }
}
