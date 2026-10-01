package com.jaspersoft.jrsupgrade.jrs.strategy;

import com.jaspersoft.jrsupgrade.core.json.Json;
import com.jaspersoft.jrsupgrade.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsupgrade.jrs.api.ExportRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The {@code <archive>.jrs-upgrade.json} sidecar written next to every export (spec §9.3): when it
 * was taken, from which server, with which request flags, the SHA-256 of the archive and the server
 * keystore fingerprint an import must match. Invariants: {@link #pathFor} is the one place the
 * sidecar name is derived, so export and import always agree; the file is written through a temp
 * file and an atomic rename; an unreadable or malformed sidecar surfaces as {@link
 * IllegalArgumentException} for the import precheck to report, never as a silent skip.
 */
public record Sidecar(
    Instant exportedAt,
    String serverIdentity,
    String serverVersion,
    Optional<String> keystoreFingerprint,
    Flags flags,
    String sha256,
    ExportImportStrategy.Kind strategy) {

  public static final String SUFFIX = ".jrs-upgrade.json";

  public Sidecar {
    Objects.requireNonNull(exportedAt, "exportedAt");
    Objects.requireNonNull(serverIdentity, "serverIdentity");
    Objects.requireNonNull(serverVersion, "serverVersion");
    Objects.requireNonNull(keystoreFingerprint, "keystoreFingerprint");
    Objects.requireNonNull(flags, "flags");
    Objects.requireNonNull(sha256, "sha256");
    Objects.requireNonNull(strategy, "strategy");
  }

  /** The request that produced the archive, minus the output path. */
  public record Flags(
      ExportRequest.Scope scope,
      List<String> uris,
      boolean includeUsersRoles,
      boolean includeAccessEvents,
      boolean includeAuditEvents,
      boolean includeMonitoring,
      boolean includeSettings,
      boolean fullServer,
      Optional<String> keyAlias,
      Optional<String> organization) {

    public Flags {
      Objects.requireNonNull(scope, "scope");
      uris = List.copyOf(uris);
      // a sidecar written before the fields existed has no keyAlias or organization entry
      keyAlias = keyAlias == null ? Optional.empty() : keyAlias;
      organization = organization == null ? Optional.empty() : organization;
    }

    public Flags(
        ExportRequest.Scope scope,
        List<String> uris,
        boolean includeUsersRoles,
        boolean includeAccessEvents,
        boolean includeAuditEvents,
        boolean includeMonitoring,
        boolean includeSettings,
        boolean fullServer,
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
          keyAlias,
          Optional.empty());
    }

    public Flags(
        ExportRequest.Scope scope,
        List<String> uris,
        boolean includeUsersRoles,
        boolean includeAccessEvents,
        boolean includeAuditEvents,
        boolean includeMonitoring,
        boolean includeSettings,
        boolean fullServer) {
      this(
          scope,
          uris,
          includeUsersRoles,
          includeAccessEvents,
          includeAuditEvents,
          includeMonitoring,
          includeSettings,
          fullServer,
          Optional.empty());
    }

    public static Flags of(ExportRequest r) {
      return new Flags(
          r.scope(),
          List.copyOf(new TreeSet<>(r.uris())),
          r.includeUsersRoles(),
          r.includeAccessEvents(),
          r.includeAuditEvents(),
          r.includeMonitoring(),
          r.includeSettings(),
          r.fullServer(),
          r.keyAlias(),
          r.organization());
    }
  }

  public static Path pathFor(Path archive) {
    return archive.resolveSibling(archive.getFileName() + SUFFIX);
  }

  public static void write(Path file, Sidecar sidecar) throws IOException {
    Files.createDirectories(file.toAbsolutePath().getParent());
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    Files.writeString(tmp, Json.writePretty(sidecar), StandardCharsets.UTF_8);
    RunFiles.replace(tmp, file);
  }

  /** Empty when no sidecar exists; throws {@link IllegalArgumentException} when it is malformed. */
  public static Optional<Sidecar> read(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    return Optional.of(Json.read(Files.readString(file, StandardCharsets.UTF_8), Sidecar.class));
  }
}
