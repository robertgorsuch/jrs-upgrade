package com.jaspersoft.jrsupgrade.ops;

import com.jaspersoft.jrsupgrade.core.platform.Platform.OsFamily;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The {@code --add-opens} options the installation guide 10.1 (pp.84-86) lists in {@code JAVA_OPTS}
 * for Java 17 and 21, read from a Tomcat's {@code bin/setenv.sh} or {@code bin/setenv.bat} (issue
 * #109). Invariants: read-only and streaming; only a Tomcat of the Jakarta generation (major 10 or
 * later, what JasperReports Server 10 runs on) is judged, since that is the generation the guide
 * documents the options for; a Tomcat whose version cannot be read is not judged; the finding is
 * advice, never a refusal, because the vendor's own bundled installer writes a {@code setenv}
 * without them and that server starts (10.0.0, Tomcat 10.1.41, Java 17).
 */
public final class TomcatJavaOpts {

  public static final String ADD_OPENS = "--add-opens";

  /** Where the guide says to put the options (installation guide 10.1 p.87). */
  public static final String GUIDE = "installation guide 10.1 pp.84-86";

  private static final int JAKARTA_MAJOR = 10;

  private TomcatJavaOpts() {}

  /** The {@code setenv} file Tomcat reads on this operating system, whether or not it exists. */
  public static Path setenv(Path tomcatDir, OsFamily os) {
    return tomcatDir.resolve("bin").resolve(os == OsFamily.WINDOWS ? "setenv.bat" : "setenv.sh");
  }

  /**
   * The {@code setenv} file that should carry {@code --add-opens} and does not (missing or without
   * the option), for a Tomcat of major 10 or later; empty when the option is there, when the Tomcat
   * is older, or when its version cannot be read.
   */
  public static Optional<Path> missingAddOpens(Path tomcatDir, OsFamily os) {
    Optional<String> version = TomcatVersion.detect(tomcatDir);
    if (version.isEmpty() || major(version.get()) < JAKARTA_MAJOR) {
      return Optional.empty();
    }
    Path file = setenv(tomcatDir, os);
    return hasAddOpens(file) ? Optional.empty() : Optional.of(file);
  }

  /** Whether the file exists and any line of it mentions {@code --add-opens}. */
  public static boolean hasAddOpens(Path setenv) {
    if (!Files.isRegularFile(setenv)) {
      return false;
    }
    try (Stream<String> lines = Files.lines(setenv, StandardCharsets.ISO_8859_1)) {
      return lines.anyMatch(l -> l.contains(ADD_OPENS));
    } catch (IOException | java.io.UncheckedIOException e) {
      return false;
    }
  }

  private static int major(String version) {
    int dot = version.indexOf('.');
    try {
      return Integer.parseInt(dot < 0 ? version : version.substring(0, dot));
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
