package com.jaspersoft.jrsupgrade.jrs.api;

import com.jaspersoft.jrsupgrade.core.secrets.SecretRef;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Unified import request (spec §9.1). */
public record ImportRequest(
    Path archive,
    boolean update,
    boolean skipUserUpdate,
    boolean includeAccessEvents,
    boolean includeAuditEvents,
    boolean includeMonitoring,
    boolean includeSettings,
    boolean skipThemes,
    Optional<Path> sourceKeystore,
    Optional<SecretRef> sourceKeystorePassword,
    BrokenDependencies brokenDependencies,
    Optional<String> keyAlias,
    Optional<String> organization,
    boolean mergeOrganization) {

  public ImportRequest {
    Objects.requireNonNull(brokenDependencies, "brokenDependencies");
    Objects.requireNonNull(keyAlias, "keyAlias");
    Objects.requireNonNull(organization, "organization");
  }

  public ImportRequest(
      Path archive,
      boolean update,
      boolean skipUserUpdate,
      boolean includeAccessEvents,
      boolean includeAuditEvents,
      boolean includeMonitoring,
      boolean includeSettings,
      boolean skipThemes,
      Optional<Path> sourceKeystore,
      Optional<SecretRef> sourceKeystorePassword,
      BrokenDependencies brokenDependencies,
      Optional<String> keyAlias) {
    this(
        archive,
        update,
        skipUserUpdate,
        includeAccessEvents,
        includeAuditEvents,
        includeMonitoring,
        includeSettings,
        skipThemes,
        sourceKeystore,
        sourceKeystorePassword,
        brokenDependencies,
        keyAlias,
        Optional.empty(),
        false);
  }

  /** The same request imported into organisation {@code id}, merged when the ids differ. */
  public ImportRequest withOrganization(String id, boolean merge) {
    return new ImportRequest(
        archive,
        update,
        skipUserUpdate,
        includeAccessEvents,
        includeAuditEvents,
        includeMonitoring,
        includeSettings,
        skipThemes,
        sourceKeystore,
        sourceKeystorePassword,
        brokenDependencies,
        keyAlias,
        Optional.of(id),
        merge);
  }

  public ImportRequest(
      Path archive,
      boolean update,
      boolean skipUserUpdate,
      boolean includeAccessEvents,
      boolean includeAuditEvents,
      boolean includeMonitoring,
      boolean includeSettings,
      boolean skipThemes,
      Optional<Path> sourceKeystore,
      Optional<SecretRef> sourceKeystorePassword,
      BrokenDependencies brokenDependencies) {
    this(
        archive,
        update,
        skipUserUpdate,
        includeAccessEvents,
        includeAuditEvents,
        includeMonitoring,
        includeSettings,
        skipThemes,
        sourceKeystore,
        sourceKeystorePassword,
        brokenDependencies,
        Optional.empty());
  }

  /** The same request decrypted with {@code alias} instead of the server's own key. */
  public ImportRequest withKeyAlias(String alias) {
    return new ImportRequest(
        archive,
        update,
        skipUserUpdate,
        includeAccessEvents,
        includeAuditEvents,
        includeMonitoring,
        includeSettings,
        skipThemes,
        sourceKeystore,
        sourceKeystorePassword,
        brokenDependencies,
        Optional.of(alias),
        organization,
        mergeOrganization);
  }

  /** The request with the server's own default for broken dependencies ({@code fail}). */
  public ImportRequest(
      Path archive,
      boolean update,
      boolean skipUserUpdate,
      boolean includeAccessEvents,
      boolean includeAuditEvents,
      boolean includeMonitoring,
      boolean includeSettings,
      boolean skipThemes,
      Optional<Path> sourceKeystore,
      Optional<SecretRef> sourceKeystorePassword) {
    this(
        archive,
        update,
        skipUserUpdate,
        includeAccessEvents,
        includeAuditEvents,
        includeMonitoring,
        includeSettings,
        skipThemes,
        sourceKeystore,
        sourceKeystorePassword,
        BrokenDependencies.FAIL);
  }
}
