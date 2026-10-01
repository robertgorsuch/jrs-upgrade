package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsupgrade.jrs.vendor.BuildomaticLocator;
import com.jaspersoft.jrsupgrade.ops.JrsVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * What jrs-upgrade could find out about the JasperReports Server distribution the operator pointed
 * {@code --package} at (spec §10.2 step 2). Invariants: {@code dir} is absolute and normalised; the
 * record never throws for a missing or incomplete package, it simply reports what is absent so the
 * precheck can phrase the failure; {@link #discoveredVersion()} is only present when a file or
 * directory name in the package states a version, otherwise the operator's {@code --to} is trusted.
 */
record TargetPackage(
    Path dir,
    Optional<Buildomatic> buildomatic,
    Optional<Path> webappDir,
    Optional<Path> warFile,
    Optional<String> discoveredVersion) {

  static final List<String> WEBAPP_NAMES = List.of("jasperserver-pro", "jasperserver");

  TargetPackage {
    dir = Objects.requireNonNull(dir, "dir").toAbsolutePath().normalize();
    Objects.requireNonNull(buildomatic, "buildomatic");
    Objects.requireNonNull(webappDir, "webappDir");
    Objects.requireNonNull(warFile, "warFile");
    Objects.requireNonNull(discoveredVersion, "discoveredVersion");
  }

  static TargetPackage inspect(Path packageDir, BuildomaticLocator locator) {
    Path dir = packageDir.toAbsolutePath().normalize();
    if (!Files.isDirectory(dir)) {
      return new TargetPackage(
          dir, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
    Optional<Buildomatic> buildomatic = locator.locate(dir);
    Optional<Path> webapp =
        WEBAPP_NAMES.stream().map(dir::resolve).filter(Files::isDirectory).findFirst();
    Optional<Path> war = Optional.empty();
    try (DirectoryStream<Path> children = Files.newDirectoryStream(dir, "*.war")) {
      for (Path child : children) {
        if (Files.isRegularFile(child)) {
          war = Optional.of(child);
          break;
        }
      }
    } catch (IOException e) {
      war = Optional.empty();
    }
    Optional<String> version = versionOf(dir, webapp, war);
    return new TargetPackage(dir, buildomatic, webapp, war, version);
  }

  /** True when both the vendor scripts and a webapp (exploded or WAR) are present. */
  boolean complete() {
    return buildomatic.map(b -> b.scriptFor(Buildomatic.ANT_SCRIPT).isPresent()).orElse(false)
        && (webappDir.isPresent() || warFile.isPresent());
  }

  List<String> problems(String scriptExtension) {
    List<String> problems = new ArrayList<>();
    if (!Files.isDirectory(dir)) {
      problems.add(dir + " is not a directory");
      return problems;
    }
    if (buildomatic.isEmpty()) {
      problems.add("no buildomatic/ directory under " + dir);
    } else if (buildomatic.get().scriptFor(Buildomatic.ANT_SCRIPT).isEmpty()) {
      problems.add(
          "no "
              + Buildomatic.ANT_SCRIPT
              + scriptExtension
              + " in "
              + buildomatic.get().dir()
              + " (the package does not match this operating system)");
    }
    if (webappDir.isEmpty() && warFile.isEmpty()) {
      problems.add(
          "no webapp directory (" + String.join(" or ", WEBAPP_NAMES) + ") or .war under " + dir);
    }
    return problems;
  }

  /** SHA-256 over the sorted relative paths and sizes of every regular file in the package. */
  String contentHash() {
    if (!Files.isDirectory(dir)) {
      return "absent";
    }
    TreeMap<String, Long> entries = new TreeMap<>();
    try {
      Files.walkFileTree(
          dir,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              String rel = dir.relativize(file).toString().replace('\\', '/');
              entries.put(rel, attrs.size());
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      return "unreadable";
    }
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      for (var e : entries.entrySet()) {
        md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        md.update(Long.toString(e.getValue()).getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
      }
      return HexFormat.of().formatHex(md.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static Optional<String> versionOf(Path dir, Optional<Path> webapp, Optional<Path> war) {
    return JrsVersion.ofDistributionDir(dir)
        .or(() -> war.flatMap(JrsVersion::ofArtifactName))
        .or(() -> webapp.flatMap(JrsVersion::ofWebapp));
  }
}
