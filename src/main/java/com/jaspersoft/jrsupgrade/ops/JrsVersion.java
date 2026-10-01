package com.jaspersoft.jrsupgrade.ops;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The JasperReports Server version that file and directory names state, read without the server
 * running: the {@code jasperserver-*-X.Y.Z.jar} files of a webapp's {@code WEB-INF/lib}, a WAR's
 * name, or an unpacked distribution's directory name. Shared by the upgrade's target-package check
 * and {@code init}'s installation list. Invariants: read-only; never throws, an unreadable or
 * unnamed version is empty; a version is always three dotted numbers.
 */
public final class JrsVersion {

  private static final Pattern VERSION_IN_NAME =
      Pattern.compile("(?i)jasperserver(?:-pro)?-(?:api-)?(?:common-|impl-)?(\\d+\\.\\d+\\.\\d+)");
  private static final Pattern VERSION_IN_DIR =
      Pattern.compile("(?i)jasperreports-server-(?:bin-|pro-|cp-)?(\\d+\\.\\d+\\.\\d+)");

  private JrsVersion() {}

  /** The version a {@code jasperreports-server-[bin-|pro-|cp-]X.Y.Z} directory name states. */
  public static Optional<String> ofDistributionDir(Path dir) {
    return match(VERSION_IN_DIR, dir.getFileName());
  }

  /** The version a {@code jasperserver[-pro]-X.Y.Z.war}-style file name states. */
  public static Optional<String> ofArtifactName(Path file) {
    return match(VERSION_IN_NAME, file.getFileName());
  }

  /** The version the {@code jasperserver-*.jar} names in {@code webappDir/WEB-INF/lib} state. */
  public static Optional<String> ofWebapp(Path webappDir) {
    Path lib = webappDir.resolve("WEB-INF").resolve("lib");
    if (!Files.isDirectory(lib)) {
      return Optional.empty();
    }
    try (DirectoryStream<Path> jars = Files.newDirectoryStream(lib, "jasperserver-*.jar")) {
      for (Path jar : jars) {
        Optional<String> v = ofArtifactName(jar);
        if (v.isPresent()) {
          return v;
        }
      }
    } catch (IOException e) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private static Optional<String> match(Pattern pattern, Path name) {
    if (name == null) {
      return Optional.empty();
    }
    Matcher m = pattern.matcher(name.toString().toLowerCase(Locale.ROOT));
    return m.find() ? Optional.of(m.group(1)) : Optional.empty();
  }
}
