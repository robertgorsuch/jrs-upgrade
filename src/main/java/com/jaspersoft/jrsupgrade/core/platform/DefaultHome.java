package com.jaspersoft.jrsupgrade.core.platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The single answer to "where is {@code $JRS_UPGRADE_HOME} when nobody said" (spec §5.1). One
 * class, because the decision is made twice in a process: once by the logging bootstrap, before any
 * logger exists, and once by the running {@link Platform}; when the two disagreed, logs and state
 * landed in different homes. Invariants: the writability test is a create-and-delete probe, not
 * {@code Files.isWritable}, which lies about Windows ACLs; a per-user home is only ever chosen when
 * the system home does not exist, so an operator who merely lacks the rights to an existing system
 * home is told rather than quietly given a second state store and a second run lock; nothing here
 * logs or loads a class that owns a logger, so the bootstrap can call it first.
 */
public final class DefaultHome {

  /** Name of the home directory under the system base. */
  public static final String DIR = "jrs-upgrade";

  /** Name of the per-user home under the operator's home directory. */
  public static final String PER_USER_DIR = ".jrs-upgrade";

  private static final String PROGRAM_DATA = "ProgramData";

  private DefaultHome() {}

  /**
   * Where the default home is and how it was reached. {@code perUser} means the system home does
   * not exist and this process fell back to the operator's own directory; {@code
   * systemHomeUnwritable} means it does exist and this process cannot write to it, which the caller
   * must refuse rather than work around.
   */
  public record Choice(Path home, Path systemHome, boolean perUser, boolean systemHomeUnwritable) {
    public Choice {
      Objects.requireNonNull(home, "home");
      Objects.requireNonNull(systemHome, "systemHome");
    }
  }

  /** The system base for this OS: {@code %ProgramData%} on Windows, {@code /var/lib} elsewhere. */
  public static Path systemBase(Map<String, String> env) {
    Objects.requireNonNull(env, "env");
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    return windows
        ? Path.of(env.getOrDefault(PROGRAM_DATA, "C:\\ProgramData"))
        : Path.of("/var/lib");
  }

  public static Choice choose(Map<String, String> env) {
    return choose(systemBase(env));
  }

  /** Applies the rules above to one system base; never throws and never creates the home. */
  public static Choice choose(Path systemBase) {
    Objects.requireNonNull(systemBase, "systemBase");
    Path systemHome = systemBase.resolve(DIR).toAbsolutePath().normalize();
    Path perUser = Path.of(System.getProperty("user.home", ".")).resolve(PER_USER_DIR);
    if (Files.isDirectory(systemHome)) {
      return writable(systemHome)
          ? new Choice(systemHome, systemHome, false, false)
          : new Choice(perUser, systemHome, true, true);
    }
    return writable(systemBase)
        ? new Choice(systemHome, systemHome, false, false)
        : new Choice(perUser, systemHome, true, false);
  }

  /**
   * True when this process can create a file in the directory. {@code Files.isWritable} answers
   * from the read-only attribute on Windows and ignores the ACL that actually decides, so it
   * reports {@code C:\ProgramData} as writable for a standard user who cannot create anything in a
   * subdirectory of it.
   */
  private static boolean writable(Path dir) {
    if (!Files.isDirectory(dir)) {
      return false;
    }
    Path probe = null;
    try {
      probe = Files.createTempFile(dir, ".jrs-upgrade-probe", ".tmp");
      return true;
    } catch (IOException | SecurityException e) {
      return false;
    } finally {
      if (probe != null) {
        try {
          Files.deleteIfExists(probe);
        } catch (IOException ignored) {
          // an empty probe file left behind is harmless and says nothing about writability
        }
      }
    }
  }
}
