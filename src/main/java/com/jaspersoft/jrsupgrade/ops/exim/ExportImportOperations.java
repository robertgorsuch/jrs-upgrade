package com.jaspersoft.jrsupgrade.ops.exim;

import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.secrets.SecretRef;
import com.jaspersoft.jrsupgrade.jrs.api.BrokenDependencies;
import com.jaspersoft.jrsupgrade.jrs.api.ExportImportStrategy;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Entry points of the export/import subsystem (spec §9). Planning never mutates anything: it
 * connects to the server, chooses the strategy, and returns a {@link Plan} whose execution is the
 * {@code Runner}'s job. Invariants: an option record is exactly what the CLI flags carry, so a plan
 * can be rebuilt from them for {@code runs recover}; an empty {@code strategy} means "select by the
 * rules of spec §9.2", a present one forces that kind.
 */
public interface ExportImportOperations {

  /**
   * Options for {@code jrs-upgrade export}; an empty {@code uris} set means the whole repository.
   */
  record ExportOptions(
      Set<String> uris,
      boolean usersRoles,
      boolean accessEvents,
      boolean auditEvents,
      boolean monitoring,
      boolean settings,
      boolean fullServer,
      Path out,
      Optional<ExportImportStrategy.Kind> strategy,
      boolean stopService,
      Optional<String> keyAlias,
      Optional<String> organization,
      boolean skipDependentResources,
      boolean skipFavoriteResources) {

    public ExportOptions {
      uris = Set.copyOf(uris);
      Objects.requireNonNull(out, "out");
      Objects.requireNonNull(strategy, "strategy");
      Objects.requireNonNull(keyAlias, "keyAlias");
      Objects.requireNonNull(organization, "organization");
    }

    public ExportOptions(
        Set<String> uris,
        boolean usersRoles,
        boolean accessEvents,
        boolean auditEvents,
        boolean monitoring,
        boolean settings,
        boolean fullServer,
        Path out,
        Optional<ExportImportStrategy.Kind> strategy,
        boolean stopService,
        Optional<String> keyAlias,
        Optional<String> organization) {
      this(
          uris,
          usersRoles,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          fullServer,
          out,
          strategy,
          stopService,
          keyAlias,
          organization,
          false,
          false);
    }

    public ExportOptions(
        Set<String> uris,
        boolean usersRoles,
        boolean accessEvents,
        boolean auditEvents,
        boolean monitoring,
        boolean settings,
        boolean fullServer,
        Path out,
        Optional<ExportImportStrategy.Kind> strategy,
        boolean stopService,
        Optional<String> keyAlias) {
      this(
          uris,
          usersRoles,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          fullServer,
          out,
          strategy,
          stopService,
          keyAlias,
          Optional.empty());
    }

    public ExportOptions(
        Set<String> uris,
        boolean usersRoles,
        boolean accessEvents,
        boolean auditEvents,
        boolean monitoring,
        boolean settings,
        boolean fullServer,
        Path out,
        Optional<ExportImportStrategy.Kind> strategy,
        boolean stopService) {
      this(
          uris,
          usersRoles,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          fullServer,
          out,
          strategy,
          stopService,
          Optional.empty());
    }

    /** With {@code stopService} true, what an export did before #67. */
    public ExportOptions(
        Set<String> uris,
        boolean usersRoles,
        boolean accessEvents,
        boolean auditEvents,
        boolean monitoring,
        boolean settings,
        boolean fullServer,
        Path out,
        Optional<ExportImportStrategy.Kind> strategy) {
      this(
          uris,
          usersRoles,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          fullServer,
          out,
          strategy,
          true);
    }
  }

  /**
   * Options for {@code jrs-upgrade import}. {@code forceVersion} imports an archive whose sidecar
   * records a source version the vendor says cannot be imported here (10.1 and later into a server
   * below 10.1; issue #107) instead of refusing it; the override is audited. {@code keepThemes}
   * (issue #115) turns off the default of skipping themes when the archive comes from another major
   * version than this server. {@code snapshotStopsService} (ADR-0040) is true only for a plan
   * rebuilt from arguments an earlier jrs-upgrade stored, whose vendor snapshot stopped and started
   * the service; every new plan takes the snapshot with the server running. {@code noSnapshot}
   * (ADR-0040) leaves out the pre-import snapshot and its re-import on rollback; the override is
   * audited.
   */
  record ImportOptions(
      Path archive,
      boolean update,
      boolean skipUserUpdate,
      boolean accessEvents,
      boolean auditEvents,
      boolean monitoring,
      boolean settings,
      boolean skipThemes,
      Optional<Path> sourceKeystore,
      Optional<SecretRef> sourceKeystorePassword,
      Optional<ExportImportStrategy.Kind> strategy,
      BrokenDependencies brokenDependencies,
      Optional<String> keyAlias,
      Optional<String> organization,
      boolean mergeOrganization,
      boolean forceVersion,
      boolean keepThemes,
      boolean snapshotStopsService,
      boolean noSnapshot) {

    public ImportOptions {
      Objects.requireNonNull(archive, "archive");
      Objects.requireNonNull(sourceKeystore, "sourceKeystore");
      Objects.requireNonNull(sourceKeystorePassword, "sourceKeystorePassword");
      Objects.requireNonNull(strategy, "strategy");
      Objects.requireNonNull(brokenDependencies, "brokenDependencies");
      Objects.requireNonNull(keyAlias, "keyAlias");
      Objects.requireNonNull(organization, "organization");
    }

    /** The options of a new import: the snapshot is taken, with the server running. */
    public ImportOptions(
        Path archive,
        boolean update,
        boolean skipUserUpdate,
        boolean accessEvents,
        boolean auditEvents,
        boolean monitoring,
        boolean settings,
        boolean skipThemes,
        Optional<Path> sourceKeystore,
        Optional<SecretRef> sourceKeystorePassword,
        Optional<ExportImportStrategy.Kind> strategy,
        BrokenDependencies brokenDependencies,
        Optional<String> keyAlias,
        Optional<String> organization,
        boolean mergeOrganization,
        boolean forceVersion,
        boolean keepThemes) {
      this(
          archive,
          update,
          skipUserUpdate,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          skipThemes,
          sourceKeystore,
          sourceKeystorePassword,
          strategy,
          brokenDependencies,
          keyAlias,
          organization,
          mergeOrganization,
          forceVersion,
          keepThemes,
          false,
          false);
    }

    /** The same options, the vendor snapshot stopping the service as it did before ADR-0040. */
    public ImportOptions withSnapshotStopsService(boolean stops) {
      return new ImportOptions(
          archive,
          update,
          skipUserUpdate,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          skipThemes,
          sourceKeystore,
          sourceKeystorePassword,
          strategy,
          brokenDependencies,
          keyAlias,
          organization,
          mergeOrganization,
          forceVersion,
          keepThemes,
          stops,
          noSnapshot);
    }

    /** The same options with or without the pre-import snapshot ({@code --no-snapshot}). */
    public ImportOptions withNoSnapshot(boolean skip) {
      return new ImportOptions(
          archive,
          update,
          skipUserUpdate,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          skipThemes,
          sourceKeystore,
          sourceKeystorePassword,
          strategy,
          brokenDependencies,
          keyAlias,
          organization,
          mergeOrganization,
          forceVersion,
          keepThemes,
          snapshotStopsService,
          skip);
    }

    /** The options that keep the themes default: skipped only across a major version. */
    public ImportOptions(
        Path archive,
        boolean update,
        boolean skipUserUpdate,
        boolean accessEvents,
        boolean auditEvents,
        boolean monitoring,
        boolean settings,
        boolean skipThemes,
        Optional<Path> sourceKeystore,
        Optional<SecretRef> sourceKeystorePassword,
        Optional<ExportImportStrategy.Kind> strategy,
        BrokenDependencies brokenDependencies,
        Optional<String> keyAlias,
        Optional<String> organization,
        boolean mergeOrganization,
        boolean forceVersion) {
      this(
          archive,
          update,
          skipUserUpdate,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          skipThemes,
          sourceKeystore,
          sourceKeystorePassword,
          strategy,
          brokenDependencies,
          keyAlias,
          organization,
          mergeOrganization,
          forceVersion,
          false);
    }

    /** The options that refuse an archive from a version the vendor says cannot come here. */
    public ImportOptions(
        Path archive,
        boolean update,
        boolean skipUserUpdate,
        boolean accessEvents,
        boolean auditEvents,
        boolean monitoring,
        boolean settings,
        boolean skipThemes,
        Optional<Path> sourceKeystore,
        Optional<SecretRef> sourceKeystorePassword,
        Optional<ExportImportStrategy.Kind> strategy,
        BrokenDependencies brokenDependencies,
        Optional<String> keyAlias,
        Optional<String> organization,
        boolean mergeOrganization) {
      this(
          archive,
          update,
          skipUserUpdate,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          skipThemes,
          sourceKeystore,
          sourceKeystorePassword,
          strategy,
          brokenDependencies,
          keyAlias,
          organization,
          mergeOrganization,
          false);
    }

    public ImportOptions withForceVersion(boolean force) {
      return new ImportOptions(
          archive,
          update,
          skipUserUpdate,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          skipThemes,
          sourceKeystore,
          sourceKeystorePassword,
          strategy,
          brokenDependencies,
          keyAlias,
          organization,
          mergeOrganization,
          force,
          keepThemes,
          snapshotStopsService,
          noSnapshot);
    }

    public ImportOptions withKeepThemes(boolean keep) {
      return new ImportOptions(
          archive,
          update,
          skipUserUpdate,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          skipThemes,
          sourceKeystore,
          sourceKeystorePassword,
          strategy,
          brokenDependencies,
          keyAlias,
          organization,
          mergeOrganization,
          forceVersion,
          keep,
          snapshotStopsService,
          noSnapshot);
    }

    public ImportOptions(
        Path archive,
        boolean update,
        boolean skipUserUpdate,
        boolean accessEvents,
        boolean auditEvents,
        boolean monitoring,
        boolean settings,
        boolean skipThemes,
        Optional<Path> sourceKeystore,
        Optional<SecretRef> sourceKeystorePassword,
        Optional<ExportImportStrategy.Kind> strategy,
        BrokenDependencies brokenDependencies,
        Optional<String> keyAlias) {
      this(
          archive,
          update,
          skipUserUpdate,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          skipThemes,
          sourceKeystore,
          sourceKeystorePassword,
          strategy,
          brokenDependencies,
          keyAlias,
          Optional.empty(),
          false);
    }

    public ImportOptions(
        Path archive,
        boolean update,
        boolean skipUserUpdate,
        boolean accessEvents,
        boolean auditEvents,
        boolean monitoring,
        boolean settings,
        boolean skipThemes,
        Optional<Path> sourceKeystore,
        Optional<SecretRef> sourceKeystorePassword,
        Optional<ExportImportStrategy.Kind> strategy,
        BrokenDependencies brokenDependencies) {
      this(
          archive,
          update,
          skipUserUpdate,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          skipThemes,
          sourceKeystore,
          sourceKeystorePassword,
          strategy,
          brokenDependencies,
          Optional.empty());
    }

    /** The options with the server's own default for broken dependencies ({@code fail}). */
    public ImportOptions(
        Path archive,
        boolean update,
        boolean skipUserUpdate,
        boolean accessEvents,
        boolean auditEvents,
        boolean monitoring,
        boolean settings,
        boolean skipThemes,
        Optional<Path> sourceKeystore,
        Optional<SecretRef> sourceKeystorePassword,
        Optional<ExportImportStrategy.Kind> strategy) {
      this(
          archive,
          update,
          skipUserUpdate,
          accessEvents,
          auditEvents,
          monitoring,
          settings,
          skipThemes,
          sourceKeystore,
          sourceKeystorePassword,
          strategy,
          BrokenDependencies.FAIL);
    }
  }

  Plan planExport(ExportOptions options);

  Plan planImport(ImportOptions options);
}
