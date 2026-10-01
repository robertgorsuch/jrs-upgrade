package com.jaspersoft.jrsupgrade.ops;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the version of an Apache Tomcat installation (review §2.1): the {@code
 * Implementation-Version} of {@code lib/catalina.jar}'s manifest, else the {@code Apache Tomcat
 * Version x.y.z} line of {@code RELEASE-NOTES}. Invariants: read-only and streaming; empty when the
 * directory holds neither, which the callers report as "could not be read" rather than as any
 * version; the value is the bare {@code major.minor.patch}.
 */
public final class TomcatVersion {

  static final String CATALINA_JAR = "lib/catalina.jar";
  static final String RELEASE_NOTES = "RELEASE-NOTES";
  private static final Pattern RELEASE_LINE =
      Pattern.compile("Apache Tomcat Version (\\d+\\.\\d+\\.\\d+)");
  private static final Pattern VERSION = Pattern.compile("(\\d+\\.\\d+\\.\\d+)");

  private TomcatVersion() {}

  public static Optional<String> detect(Path tomcatDir) {
    Path jar = tomcatDir.resolve("lib").resolve("catalina.jar");
    if (Files.isRegularFile(jar)) {
      try (InputStream in = Files.newInputStream(jar);
          JarInputStream jarIn = new JarInputStream(in)) {
        Manifest manifest = jarIn.getManifest();
        if (manifest != null) {
          String value = manifest.getMainAttributes().getValue("Implementation-Version");
          if (value != null) {
            Matcher m = VERSION.matcher(value);
            if (m.find()) {
              return Optional.of(m.group(1));
            }
          }
        }
      } catch (IOException e) {
        // fall through to the release notes
      }
    }
    Path notes = tomcatDir.resolve(RELEASE_NOTES);
    if (Files.isRegularFile(notes)) {
      try (Stream<String> lines = Files.lines(notes, StandardCharsets.ISO_8859_1)) {
        return lines
            .limit(200)
            .map(RELEASE_LINE::matcher)
            .filter(Matcher::find)
            .map(m -> m.group(1))
            .findFirst();
      } catch (IOException | java.io.UncheckedIOException e) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }
}
