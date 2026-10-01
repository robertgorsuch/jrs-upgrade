package com.jaspersoft.jrsupgrade.ops.customizations;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * What a webapp holds, read once for the customization findings (ADR-0003): its files, the jars
 * under {@code WEB-INF/lib} with the coordinates each states, and which jar holds each class.
 * Invariants: a webapp is a directory or a WAR, and a WAR and the jars inside it are read as
 * streams, never unpacked; nothing is loaded or run; a jar's coordinates come from its embedded
 * {@code pom.properties} when it has exactly one, else from its file name, else they are absent
 * (never guessed); class names are binary names ({@code com.example.Outer$Inner}); the index is
 * immutable.
 */
record PackageIndex(Path webapp, Set<String> files, List<Jar> jars, Map<String, String> classes) {

  static final String LIB = "WEB-INF/lib/";

  /** {@code name-1.2.3.jar}, {@code name-1.2.3-suffix.jar}. */
  private static final Pattern NAMED = Pattern.compile("^(.+?)-(\\d[\\w.\\-+]*)\\.jar$");

  PackageIndex {
    Objects.requireNonNull(webapp, "webapp");
    files = Set.copyOf(files);
    jars = List.copyOf(jars);
    classes = Map.copyOf(classes);
  }

  /** Where a {@link Coordinates} came from. */
  enum From {
    POM,
    FILE_NAME
  }

  /** What a jar says it is. {@code groupId} is empty when only the file name spoke. */
  record Coordinates(Optional<String> groupId, String artifactId, String version, From from) {
    Coordinates {
      Objects.requireNonNull(groupId, "groupId");
      Objects.requireNonNull(artifactId, "artifactId");
      Objects.requireNonNull(version, "version");
      Objects.requireNonNull(from, "from");
    }

    @Override
    public String toString() {
      return groupId.map(g -> g + ":").orElse("") + artifactId + ":" + version;
    }
  }

  /** One jar of {@code WEB-INF/lib}; {@code name} is its file name. */
  record Jar(String name, Optional<Coordinates> coordinates) {
    Jar {
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(coordinates, "coordinates");
    }
  }

  static PackageIndex read(Path webapp) throws IOException {
    Path at = webapp.toAbsolutePath().normalize();
    Builder b = new Builder();
    if (Files.isDirectory(at)) {
      try (Stream<Path> walk = Files.walk(at)) {
        for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
          String rel = at.relativize(p).toString().replace('\\', '/');
          b.files.add(rel);
          if (isLibJar(rel)) {
            try (InputStream in = Files.newInputStream(p)) {
              b.jar(rel.substring(LIB.length()), in);
            }
          }
        }
      }
    } else {
      try (InputStream in = Files.newInputStream(at);
          ZipInputStream war = new ZipInputStream(in)) {
        ZipEntry e;
        while ((e = war.getNextEntry()) != null) {
          if (e.isDirectory()) {
            continue;
          }
          String rel = e.getName();
          b.files.add(rel);
          if (isLibJar(rel)) {
            b.jar(rel.substring(LIB.length()), unclosable(war));
          }
        }
      }
    }
    return new PackageIndex(at, b.files, b.jars, b.classes);
  }

  /** The bytes of {@code relativePath} in this webapp, or empty when it has no such file. */
  Optional<byte[]> read(String relativePath) throws IOException {
    if (!files.contains(relativePath)) {
      return Optional.empty();
    }
    if (Files.isDirectory(webapp)) {
      return Optional.of(Files.readAllBytes(webapp.resolve(relativePath)));
    }
    try (InputStream in = Files.newInputStream(webapp);
        ZipInputStream war = new ZipInputStream(in)) {
      ZipEntry e;
      while ((e = war.getNextEntry()) != null) {
        if (e.getName().equals(relativePath)) {
          return Optional.of(war.readAllBytes());
        }
      }
    }
    return Optional.empty();
  }

  /** The jar of {@code WEB-INF/lib} with this file name. */
  Optional<Jar> jar(String name) {
    return jars.stream().filter(j -> j.name().equals(name)).findFirst();
  }

  static boolean isLibJar(String rel) {
    return rel.startsWith(LIB)
        && rel.indexOf('/', LIB.length()) < 0
        && rel.toLowerCase(java.util.Locale.ROOT).endsWith(".jar");
  }

  /** Coordinates from a jar's file name alone. */
  static Optional<Coordinates> fromName(String jarName) {
    Matcher m = NAMED.matcher(jarName);
    return m.matches()
        ? Optional.of(new Coordinates(Optional.empty(), m.group(1), m.group(2), From.FILE_NAME))
        : Optional.empty();
  }

  /**
   * Reads one jar from {@code in}: its embedded {@code pom.properties} and its classes, handed to
   * {@code onClass} with their bytes when given, else only named.
   */
  static Optional<Coordinates> scanJar(String name, InputStream in, ClassSink onClass)
      throws IOException {
    ZipInputStream jar = new ZipInputStream(in);
    List<Coordinates> poms = new ArrayList<>();
    ZipEntry e;
    while ((e = jar.getNextEntry()) != null) {
      String entry = e.getName();
      if (entry.startsWith("META-INF/maven/") && entry.endsWith("/pom.properties")) {
        Properties p = new Properties();
        p.load(unclosable(jar));
        String artifactId = p.getProperty("artifactId");
        String version = p.getProperty("version");
        if (artifactId != null && version != null) {
          poms.add(
              new Coordinates(
                  Optional.ofNullable(p.getProperty("groupId")),
                  artifactId.strip(),
                  version.strip(),
                  From.POM));
        }
      } else if (entry.endsWith(".class")
          && !entry.startsWith("META-INF/")
          && !entry.endsWith("module-info.class")
          && !entry.endsWith("package-info.class")) {
        String binary = entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
        onClass.accept(binary, unclosable(jar));
      }
    }
    // a fat jar holds several poms and says nothing about itself
    return poms.size() == 1 ? Optional.of(poms.get(0)) : fromName(name);
  }

  /** Receives each class of a jar; the stream holds its bytes and must not be closed. */
  @FunctionalInterface
  interface ClassSink {
    void accept(String binaryName, InputStream bytes) throws IOException;
  }

  private static InputStream unclosable(InputStream in) {
    return new FilterInputStream(in) {
      @Override
      public void close() {}
    };
  }

  private static final class Builder {
    final Set<String> files = new TreeSet<>();
    final List<Jar> jars = new ArrayList<>();
    final Map<String, String> classes = new HashMap<>();

    void jar(String name, InputStream in) throws IOException {
      Optional<Coordinates> c =
          scanJar(name, in, (binary, bytes) -> classes.putIfAbsent(binary, name));
      jars.add(new Jar(name, c));
    }
  }
}
