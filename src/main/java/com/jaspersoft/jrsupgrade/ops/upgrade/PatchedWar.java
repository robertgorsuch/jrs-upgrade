package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.platform.FileOps;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A patched WAR the operator passed with {@code --war} to deploy instead of the package's own
 * webapp (issue #9: the vendor's pre-patched build rather than the GA WAR plus jar swaps), as read
 * at planning time. Invariants: {@code path} is absolute and normalised; {@code sha256} is the
 * file's hash when it was read, which the staging step checks again before it copies the file;
 * {@code versions} are the versions the WAR's {@code WEB-INF/lib/jasperserver*} jar names state
 * (more than one means hotfix-labelled jars), {@code build} the manifest's {@code
 * Implementation-Version} when the WAR has one; the WAR is read as a stream, never unpacked.
 */
record PatchedWar(Path path, String sha256, NavigableSet<String> versions, Optional<String> build) {

  PatchedWar {
    path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    Objects.requireNonNull(sha256, "sha256");
    TreeSet<String> ordered = new TreeSet<>(HotfixLabels.VERSION_ORDER);
    ordered.addAll(versions);
    versions = ordered;
    Objects.requireNonNull(build, "build");
  }

  /**
   * Reads {@code war}; throws {@link UpgradeException} (exit 2) when it is not a readable WAR,
   * since nothing can be planned around a webapp that is not there.
   */
  static PatchedWar read(Path war, FileOps files) {
    Path file = war.toAbsolutePath().normalize();
    if (!Files.isRegularFile(file)) {
      throw new UpgradeException(
          UpgradeException.PRECHECK,
          "--war " + file + " is not a file",
          "pass the patched WAR the vendor delivered");
    }
    NavigableSet<String> versions = new TreeSet<>(HotfixLabels.VERSION_ORDER);
    Optional<String> build = Optional.empty();
    boolean webInf = false;
    try (InputStream in = Files.newInputStream(file);
        ZipInputStream zip = new ZipInputStream(in)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName();
        if (name.startsWith("WEB-INF/")) {
          webInf = true;
        }
        if (name.startsWith("WEB-INF/lib/")) {
          HotfixLabels.versionOfJar(name.substring("WEB-INF/lib/".length()))
              .ifPresent(versions::add);
        }
        if (name.equals("META-INF/MANIFEST.MF")) {
          Manifest manifest = new Manifest(zip);
          build =
              Optional.ofNullable(manifest.getMainAttributes().getValue("Implementation-Version"))
                  .map(String::strip)
                  .filter(v -> !v.isEmpty());
        }
      }
      if (!webInf) {
        throw new UpgradeException(
            UpgradeException.PRECHECK,
            "--war " + file + " holds no WEB-INF/: it is not a webapp archive",
            "pass the patched jasperserver-pro.war (or jasperserver.war) itself");
      }
      return new PatchedWar(file, files.sha256(file), versions, build);
    } catch (IOException e) {
      throw new UpgradeException(
          UpgradeException.PRECHECK,
          "cannot read --war " + file + ": " + e.getMessage(),
          "pass a readable, complete WAR",
          e);
    }
  }

  /** The product version the WAR states: the lowest jar version, which hotfix labels exceed. */
  Optional<String> version() {
    return versions.isEmpty() ? Optional.empty() : Optional.of(versions.first());
  }

  /** "sha256 ..., version 10.1.0, build ..." for the plan and the run record. */
  String describe() {
    return path
        + " (sha256 "
        + sha256
        + (versions.isEmpty() ? "" : ", jar versions " + String.join(", ", versions))
        + build.map(b -> ", build " + b).orElse("")
        + ")";
  }
}
