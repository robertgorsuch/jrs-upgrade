package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.secrets.SecretRef;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Upgrade orchestration entry points (spec §10). Invariants: planning never mutates the server or
 * the installation (it may read the target package and the state store); every returned {@link
 * Plan} carries the five phases of spec §10.2 in order ({@code preflight}, {@code backup}, {@code
 * vendor-upgrade}, {@code reconcile}, {@code verify}) for an upgrade, or the single {@code
 * rollback} phase for a rollback; every plan carries the spec §10.1 warning that rollback restores
 * files only, because the vendor script changes the repository database in both modes (ADR-0012).
 */
public interface UpgradeOperations {

  /** Operation id recorded in the run journal for an upgrade. */
  String UPGRADE_OPERATION = "upgrade";

  /** Operation id recorded in the run journal for a rollback to point B or C. */
  String ROLLBACK_OPERATION = "upgrade.rollback";

  /** The rehearsal ({@code --test}): the vendor's validation, nothing changed (spec §10.2). */
  String TEST_OPERATION = "upgrade.test";

  /**
   * How the vendor script treats the repository database (spec §10.1, ADR-0012): {@code samedb}
   * migrates its schema in place, {@code newdb} drops it and recreates it from the point-B full
   * export. Neither can be undone by jrs-upgrade, so both require {@code --db-backup-confirmed}.
   */
  enum Mode {
    NEWDB,
    SAMEDB;

    public String vendorSuffix() {
      return switch (this) {
        case NEWDB -> "newdb";
        case SAMEDB -> "samedb";
      };
    }
  }

  /**
   * Rollback points of spec §10.2: B is the state after the backup phase, C the state after the
   * vendor upgrade. Restoring to C means restoring the point-B backups, so both restore the same
   * snapshot set; the distinction is kept for the operator's vocabulary.
   */
  enum RollbackPoint {
    B,
    C
  }

  /**
   * What a rollback restores: the point-B files always, and with {@code restoreDatabase} the
   * repository database of a newdb run, rebuilt from the point-B export with the restored
   * buildomatic (ADR-0029). Refused at plan time for a samedb run (exit 6) and for a run whose
   * vendor script never started (exit 2).
   */
  record RollbackOptions(RollbackPoint toPoint, boolean restoreDatabase) {
    public RollbackOptions {
      Objects.requireNonNull(toPoint, "toPoint");
    }
  }

  /**
   * What the operator asked for. {@code existingExport} is an export taken earlier, here or on
   * another server, that a newdb upgrade rebuilds the repository from instead of exporting now
   * (ADR-0028); {@code keyAlias} and {@code keyPassword} name the key that export was encrypted
   * with, written into the target buildomatic's properties for the vendor import; {@code
   * includeEvents} asks a newdb upgrade to import the access, audit and monitoring events the
   * vendor script leaves behind (issue #106), and means nothing for samedb; {@code
   * migratePasswords} asks a samedb upgrade to 10.1 or later to run the vendor's password migration
   * after the vendor run (issue #108), is refused for an older target and ignored with a warning
   * for newdb.
   */
  record UpgradeOptions(
      String toVersion,
      Path packageDir,
      Mode mode,
      boolean dbBackupConfirmed,
      Optional<Path> tomcatDir,
      Optional<Path> existingExport,
      Optional<String> keyAlias,
      Optional<SecretRef> keyPassword,
      boolean includeEvents,
      boolean migratePasswords) {
    public UpgradeOptions {
      Objects.requireNonNull(toVersion, "toVersion");
      Objects.requireNonNull(packageDir, "packageDir");
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(tomcatDir, "tomcatDir");
      Objects.requireNonNull(existingExport, "existingExport");
      Objects.requireNonNull(keyAlias, "keyAlias");
      Objects.requireNonNull(keyPassword, "keyPassword");
      if (toVersion.isBlank()) {
        throw new IllegalArgumentException("toVersion must not be blank");
      }
      packageDir = packageDir.toAbsolutePath().normalize();
      existingExport = existingExport.map(p -> p.toAbsolutePath().normalize());
    }

    /** The options with the events and the passwords left where the vendor script leaves them. */
    public UpgradeOptions(
        String toVersion,
        Path packageDir,
        Mode mode,
        boolean dbBackupConfirmed,
        Optional<Path> tomcatDir,
        Optional<Path> existingExport,
        Optional<String> keyAlias,
        Optional<SecretRef> keyPassword,
        boolean includeEvents) {
      this(
          toVersion,
          packageDir,
          mode,
          dbBackupConfirmed,
          tomcatDir,
          existingExport,
          keyAlias,
          keyPassword,
          includeEvents,
          false);
    }

    /** The options with the events left where the vendor script leaves them. */
    public UpgradeOptions(
        String toVersion,
        Path packageDir,
        Mode mode,
        boolean dbBackupConfirmed,
        Optional<Path> tomcatDir,
        Optional<Path> existingExport,
        Optional<String> keyAlias,
        Optional<SecretRef> keyPassword) {
      this(
          toVersion,
          packageDir,
          mode,
          dbBackupConfirmed,
          tomcatDir,
          existingExport,
          keyAlias,
          keyPassword,
          false,
          false);
    }

    public UpgradeOptions withMigratePasswords(boolean migrate) {
      return new UpgradeOptions(
          toVersion,
          packageDir,
          mode,
          dbBackupConfirmed,
          tomcatDir,
          existingExport,
          keyAlias,
          keyPassword,
          includeEvents,
          migrate);
    }

    /** The options with this run's own export and the server's own key. */
    public UpgradeOptions(
        String toVersion,
        Path packageDir,
        Mode mode,
        boolean dbBackupConfirmed,
        Optional<Path> tomcatDir) {
      this(
          toVersion,
          packageDir,
          mode,
          dbBackupConfirmed,
          tomcatDir,
          Optional.empty(),
          Optional.empty(),
          Optional.empty());
    }

    public UpgradeOptions withIncludeEvents(boolean include) {
      return new UpgradeOptions(
          toVersion,
          packageDir,
          mode,
          dbBackupConfirmed,
          tomcatDir,
          existingExport,
          keyAlias,
          keyPassword,
          include,
          migratePasswords);
    }

    /** The options with the webapp staying in the Tomcat the server runs in now. */
    public UpgradeOptions(String toVersion, Path packageDir, Mode mode, boolean dbBackupConfirmed) {
      this(toVersion, packageDir, mode, dbBackupConfirmed, Optional.empty());
    }

    public UpgradeOptions withExistingExport(Path export) {
      return new UpgradeOptions(
          toVersion,
          packageDir,
          mode,
          dbBackupConfirmed,
          tomcatDir,
          Optional.of(export),
          keyAlias,
          keyPassword,
          includeEvents,
          migratePasswords);
    }

    public UpgradeOptions withKeyAlias(String alias) {
      return new UpgradeOptions(
          toVersion,
          packageDir,
          mode,
          dbBackupConfirmed,
          tomcatDir,
          existingExport,
          Optional.of(alias),
          keyPassword,
          includeEvents,
          migratePasswords);
    }

    public UpgradeOptions withKeyPassword(SecretRef ref) {
      return new UpgradeOptions(
          toVersion,
          packageDir,
          mode,
          dbBackupConfirmed,
          tomcatDir,
          existingExport,
          keyAlias,
          Optional.of(ref),
          includeEvents);
    }

    public static UpgradeOptions newdb(String toVersion, Path packageDir) {
      return new UpgradeOptions(toVersion, packageDir, Mode.NEWDB, true);
    }
  }

  Plan planUpgrade(UpgradeOptions options);

  /**
   * The rehearsal of {@code options}: the same preflight and staging as the upgrade, then {@code
   * js-upgrade-<mode> test} (the vendor's validation of the properties, the database connection and
   * the package), then the package put back as it was. No stop, no backup, no export, nothing
   * changed; the same refusals as {@link #planUpgrade} (unsupported path, exit 6; inconsistent
   * options, exit 1).
   */
  Plan planTest(UpgradeOptions options);

  Plan planRollback(String runId, RollbackOptions options);

  default Plan planRollback(String runId, RollbackPoint point) {
    return planRollback(runId, new RollbackOptions(point, false));
  }
}
