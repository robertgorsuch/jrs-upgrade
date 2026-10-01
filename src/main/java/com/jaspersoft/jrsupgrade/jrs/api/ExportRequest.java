package com.jaspersoft.jrsupgrade.jrs.api;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Unified export request (spec §9.1). {@code stopService} says whether the vendor strategy stops
 * the service around {@code js-export} (#67, ADR-0021); the REST strategy never stops it. The
 * constructor without it keeps the behaviour every caller had before the flag existed: stop. {@code
 * skipDependentResources} and {@code skipFavoriteResources} are the REST {@code
 * skip-dependent-resources} and {@code skip-favorite-resources} export parameters (REST API
 * reference 10.1 p.111, vendor doc review §4.2, issue #144); both default false, so a caller built
 * before they existed is unaffected.
 */
public record ExportRequest(
    Scope scope,
    Set<String> uris,
    boolean includeUsersRoles,
    boolean includeAccessEvents,
    boolean includeAuditEvents,
    boolean includeMonitoring,
    boolean includeSettings,
    boolean fullServer,
    Path output,
    boolean stopService,
    Optional<String> keyAlias,
    Optional<String> organization,
    boolean skipDependentResources,
    boolean skipFavoriteResources) {

  /** The alias {@code export --portable} names: every keystore since 7.5 holds it. */
  public static final String PORTABLE_KEY_ALIAS = "deprecatedImportExportEncSecret";

  public enum Scope {
    REPOSITORY,
    EVERYTHING
  }

  public ExportRequest {
    uris = Set.copyOf(uris);
    Objects.requireNonNull(keyAlias, "keyAlias");
    Objects.requireNonNull(organization, "organization");
  }

  public ExportRequest(
      Scope scope,
      Set<String> uris,
      boolean includeUsersRoles,
      boolean includeAccessEvents,
      boolean includeAuditEvents,
      boolean includeMonitoring,
      boolean includeSettings,
      boolean fullServer,
      Path output,
      boolean stopService,
      Optional<String> keyAlias) {
    this(
        scope,
        uris,
        includeUsersRoles,
        includeAccessEvents,
        includeAuditEvents,
        includeMonitoring,
        includeSettings,
        fullServer,
        output,
        stopService,
        keyAlias,
        Optional.empty(),
        false,
        false);
  }

  /** The same request limited to one organisation, its URIs relative to it. */
  public ExportRequest withOrganization(String id) {
    return new ExportRequest(
        scope,
        uris,
        includeUsersRoles,
        includeAccessEvents,
        includeAuditEvents,
        includeMonitoring,
        includeSettings,
        fullServer,
        output,
        stopService,
        keyAlias,
        Optional.of(id),
        skipDependentResources,
        skipFavoriteResources);
  }

  public ExportRequest(
      Scope scope,
      Set<String> uris,
      boolean includeUsersRoles,
      boolean includeAccessEvents,
      boolean includeAuditEvents,
      boolean includeMonitoring,
      boolean includeSettings,
      boolean fullServer,
      Path output,
      boolean stopService) {
    this(
        scope,
        uris,
        includeUsersRoles,
        includeAccessEvents,
        includeAuditEvents,
        includeMonitoring,
        includeSettings,
        fullServer,
        output,
        stopService,
        Optional.empty());
  }

  /** The same request encrypted with {@code alias} instead of the server's own key. */
  public ExportRequest withKeyAlias(String alias) {
    return new ExportRequest(
        scope,
        uris,
        includeUsersRoles,
        includeAccessEvents,
        includeAuditEvents,
        includeMonitoring,
        includeSettings,
        fullServer,
        output,
        stopService,
        Optional.of(alias),
        organization,
        skipDependentResources,
        skipFavoriteResources);
  }

  /** As the canonical constructor with {@code stopService} true, the behaviour before #67. */
  public ExportRequest(
      Scope scope,
      Set<String> uris,
      boolean includeUsersRoles,
      boolean includeAccessEvents,
      boolean includeAuditEvents,
      boolean includeMonitoring,
      boolean includeSettings,
      boolean fullServer,
      Path output) {
    this(
        scope,
        uris,
        includeUsersRoles,
        includeAccessEvents,
        includeAuditEvents,
        includeMonitoring,
        includeSettings,
        fullServer,
        output,
        true);
  }
}
