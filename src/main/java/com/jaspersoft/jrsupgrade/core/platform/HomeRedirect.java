package com.jaspersoft.jrsupgrade.core.platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * A jrs-upgrade home that has been pointed elsewhere (field test 3, ADR-0041). The file {@value
 * #FILE} in a home names the directory jrs-upgrade uses instead, so an operator whose home sits on
 * a small volume can move it once, for every operator who reaches that home, without exporting
 * {@code JRS_UPGRADE_HOME} in every shell or passing {@code --home} to every command. Invariants:
 * exactly one hop is followed, so a redirect to a directory that itself holds a redirect is not
 * chased and a loop cannot form; the target is absolute and normalised; a file that cannot be read,
 * is empty or names a relative path counts as no redirect; nothing here logs or loads a class that
 * owns a logger, so the logging bootstrap can call it before any logger exists.
 */
public final class HomeRedirect {

  /** Name of the redirect file inside a home. */
  public static final String FILE = "home.redirect";

  private HomeRedirect() {}

  /** The redirect file of {@code home}. */
  public static Path file(Path home) {
    return Objects.requireNonNull(home, "home").resolve(FILE);
  }

  /** The directory {@code home}'s redirect names, if it has a usable one. */
  public static Optional<Path> target(Path home) {
    Path file = file(home);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        String s = line.strip();
        if (s.isEmpty() || s.startsWith("#")) {
          continue;
        }
        Path target = Path.of(s);
        return target.isAbsolute()
            ? Optional.of(target.toAbsolutePath().normalize())
            : Optional.empty();
      }
      return Optional.empty();
    } catch (IOException | InvalidPathException e) {
      return Optional.empty();
    }
  }

  /** {@code home}, or the directory its redirect names. */
  public static Path follow(Path home) {
    Path base = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
    return target(base).orElse(base);
  }

  /** The text written to a redirect file pointing at {@code target}. */
  public static String content(Path target) {
    return "# jrs-upgrade home moved here by `jrs-upgrade home set` (ADR-0041); `jrs-upgrade home reset` undoes it\n"
        + target.toAbsolutePath().normalize()
        + "\n";
  }
}
