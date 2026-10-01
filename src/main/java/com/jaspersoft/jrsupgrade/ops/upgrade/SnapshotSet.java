package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Where one upgrade run keeps its rollback-point-B artefacts under {@code snapshots/<runId>/}: the
 * full export, the webapp and buildomatic archives, and the {@code SnapshotStore} entries for the
 * keystore and the configuration files. Invariant: every path is derived from the run id alone, so
 * a rollback plan built later from the same id finds the same files.
 */
record SnapshotSet(Path dir, Platform.OsFamily os) {

  static final String FULL_EXPORT = "full-export.zip";
  static final String KEYSTORE_STEP = "backup-keystore";
  static final String CONFIG_STEP = "backup-config";
  static final String ARCHIVES_DIR = "backup-webapp";
  static final String WEBAPP_ARCHIVE = "webapp";
  static final String BUILDOMATIC_ARCHIVE = "buildomatic";
  static final String MANIFEST = "upgrade.json";
  static final String SHA_SUFFIX = ".sha256";
  static final String EXTERNAL_RECORD = "full-export.external";
  static final String VENDOR_STARTED = "vendor-upgrade.started";

  SnapshotSet {
    Objects.requireNonNull(dir, "dir");
    Objects.requireNonNull(os, "os");
  }

  static SnapshotSet of(JrsUpgradeHome home, String runId, Platform.OsFamily os) {
    return new SnapshotSet(home.snapshots().resolve(runId).toAbsolutePath().normalize(), os);
  }

  static Path placeholder(JrsUpgradeHome home) {
    return home.snapshots().resolve("{runId}");
  }

  Path fullExport() {
    return dir.resolve(FULL_EXPORT);
  }

  /** Where {@code adopt-full-export} records an export taken elsewhere: its path, then its hash. */
  Path externalRecord() {
    return dir.resolve(EXTERNAL_RECORD);
  }

  /**
   * Written the moment the vendor script is launched and never removed, not even by the
   * compensation that restores point B: which script, and when. A database rollback (ADR-0029)
   * rebuilds nothing unless this says {@code js-upgrade-newdb} ran.
   */
  Path vendorStarted() {
    return dir.resolve(VENDOR_STARTED);
  }

  /** The vendor script named by {@link #vendorStarted()}, when it was written. */
  Optional<String> vendorScriptStarted() throws IOException {
    if (!Files.isRegularFile(vendorStarted())) {
      return Optional.empty();
    }
    for (String line : Files.readAllLines(vendorStarted(), StandardCharsets.UTF_8)) {
      if (!line.isBlank()) {
        String stripped = line.strip();
        int space = stripped.indexOf(' ');
        return Optional.of(space < 0 ? stripped : stripped.substring(0, space));
      }
    }
    return Optional.empty();
  }

  /** An export adopted from outside the home (ADR-0028), as recorded. */
  record ExternalExport(Path path, String sha256) {}

  Optional<ExternalExport> externalExport() throws IOException {
    if (!Files.isRegularFile(externalRecord())) {
      return Optional.empty();
    }
    List<String> lines = Files.readAllLines(externalRecord(), StandardCharsets.UTF_8);
    if (lines.size() < 2 || lines.get(0).isBlank() || lines.get(1).isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new ExternalExport(Path.of(lines.get(0).strip()), lines.get(1).strip()));
  }

  /** The export js-upgrade-newdb consumes: the adopted one when recorded, else this run's own. */
  Path resolveFullExport() {
    try {
      return externalExport().map(ExternalExport::path).orElse(fullExport());
    } catch (IOException e) {
      return fullExport();
    }
  }

  Path archivesDir() {
    return dir.resolve(ARCHIVES_DIR);
  }

  Path webappArchive() {
    return archivesDir().resolve(WEBAPP_ARCHIVE + Archives.extension(os));
  }

  Path buildomaticArchive() {
    return archivesDir().resolve(BUILDOMATIC_ARCHIVE + Archives.extension(os));
  }

  Path manifest() {
    return dir.resolve(MANIFEST);
  }

  static Path shaFileFor(Path archive) {
    return archive.resolveSibling(archive.getFileName() + SHA_SUFFIX);
  }
}
