package com.jaspersoft.jrsupgrade.jrs.strategy;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.jrs.api.Capability;
import com.jaspersoft.jrsupgrade.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsupgrade.jrs.api.ExportRequest;
import com.jaspersoft.jrsupgrade.jrs.api.ImportRequest;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.rest.RestException;
import com.jaspersoft.jrsupgrade.jrs.vendor.BuildomaticLocator;
import com.jaspersoft.jrsupgrade.jrs.vendor.VendorTools;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Strategy selection (spec §9.2): REST when the {@code EXPORT_ASYNC}/{@code IMPORT_ASYNC} probe
 * passes and the request is not a full-server export; vendor CLI when the request is full-server,
 * when the probe fails or the server cannot be reached, or when {@code --strategy} forces it.
 * Invariants: every selection carries a one-line reason for the plan summary; a forced kind wins
 * except for an import that brings a source keystore, which always uses the vendor tools because
 * {@code js-import --keystore} must run against a stopped server; an unreachable server or a probe
 * that fails for any reason other than refused credentials selects the vendor tools; refused
 * credentials (HTTP 401 or 403) propagate as a {@link RestException}, because falling back would
 * stop the production service over a wrong password.
 */
public final class Strategies {

  /** The chosen strategy and why, in one line for the plan summary. */
  public record Selection(ExportImportStrategy strategy, String reason) {
    public Selection {
      Objects.requireNonNull(strategy, "strategy");
      Objects.requireNonNull(reason, "reason");
    }

    public ExportImportStrategy.Kind kind() {
      return strategy.kind();
    }
  }

  private final RestStrategy rest;
  private final VendorCliStrategy vendor;

  public Strategies(RestStrategy rest, VendorCliStrategy vendor) {
    this.rest = Objects.requireNonNull(rest, "rest");
    this.vendor = Objects.requireNonNull(vendor, "vendor");
  }

  /** Production wiring: real polling, vendor tools built on the platform. */
  public static Strategies standard(Platform platform, Redactor redactor) {
    Objects.requireNonNull(platform, "platform");
    Objects.requireNonNull(redactor, "redactor");
    return new Strategies(
        new RestStrategy(),
        new VendorCliStrategy(
            new BuildomaticLocator(platform),
            new VendorTools(platform.processes(), platform.files(), redactor)));
  }

  public Selection select(
      Config config,
      JrsAdapter adapter,
      ExportRequest request,
      Optional<ExportImportStrategy.Kind> forced) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(request, "request");
    return select(adapter, Capability.EXPORT_ASYNC, request.fullServer(), forced);
  }

  public Selection select(
      Config config,
      JrsAdapter adapter,
      ImportRequest request,
      Optional<ExportImportStrategy.Kind> forced) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(request, "request");
    if (request.sourceKeystore().isPresent()) {
      String reason =
          "vendor CLI: importing a source keystore runs js-import --keystore with the service"
              + " stopped, so the running server picks the new keys up on restart";
      if (forced.isPresent() && forced.get() == ExportImportStrategy.Kind.REST) {
        reason += " (--strategy rest cannot apply to this request)";
      }
      return new Selection(vendor, reason);
    }
    return select(adapter, Capability.IMPORT_ASYNC, false, forced);
  }

  private Selection select(
      JrsAdapter adapter,
      Capability needed,
      boolean fullServer,
      Optional<ExportImportStrategy.Kind> forced) {
    Objects.requireNonNull(adapter, "adapter");
    Objects.requireNonNull(forced, "forced");
    if (forced.isPresent()) {
      return switch (forced.get()) {
        case REST -> new Selection(rest, "REST forced by --strategy rest");
        case VENDOR_CLI -> new Selection(vendor, "vendor CLI forced by --strategy vendor");
      };
    }
    if (fullServer) {
      return new Selection(vendor, "vendor CLI: a full-server export uses js-export");
    }
    Set<Capability> caps;
    try {
      caps = adapter.capabilities();
    } catch (RestException e) {
      if (e.authenticationFailure()) {
        throw e;
      }
      return new Selection(
          vendor, "vendor CLI: capability probe failed (" + Failures.describe(e) + ")");
    } catch (RuntimeException e) {
      return new Selection(
          vendor, "vendor CLI: capability probe failed (" + Failures.describe(e) + ")");
    }
    if (caps.contains(needed)) {
      return new Selection(rest, "REST: " + needed + " probe passed, service stays up");
    }
    return new Selection(vendor, "vendor CLI: " + needed + " probe failed on this server");
  }
}
