package com.jaspersoft.jrsupgrade.jrs.strategy;

import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsupgrade.jrs.api.ExportRequest;
import com.jaspersoft.jrsupgrade.jrs.api.ImportRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Export and import over the async REST endpoints while the server keeps running (spec §7.3, §9.2).
 * Invariants: no service stop, which is why an import that brings a source keystore is refused here
 * and routed to the vendor strategy by {@link Strategies}; every step reads what it needs from the
 * run context ({@code JrsAdapter}, {@code Config}, {@code Redactor}); the export phase is
 * server-read-only, the import phase is repository-mutating and relies on the ops layer's
 * pre-import snapshot for rollback; task handles are persisted per run so a crashed run converges
 * on re-execution.
 */
public final class RestStrategy implements ExportImportStrategy {

  public static final String EXPORT_PHASE = "export";
  public static final String IMPORT_PHASE = "import";

  private final Polling polling;

  public RestStrategy() {
    this(Polling.defaults());
  }

  public RestStrategy(Polling polling) {
    this.polling = Objects.requireNonNull(polling, "polling");
  }

  @Override
  public Kind kind() {
    return Kind.REST;
  }

  @Override
  public boolean requiresServiceStop() {
    return false;
  }

  @Override
  public List<Step> exportSteps(ExportRequest request) {
    Objects.requireNonNull(request, "request");
    return List.of(
        new StartExport(request),
        new PollExport(polling),
        new DownloadExport(request.output()),
        new WriteSidecar(EXPORT_PHASE, request, Kind.REST, polling.clock()));
  }

  @Override
  public List<Step> importSteps(ImportRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.sourceKeystore().isPresent()) {
      // js-import --keystore replaces the keys a running server loaded at startup; only the vendor
      // strategy stops and restarts the service around it. Strategies never routes such a request
      // here, so reaching this is a programming error rather than an operator one.
      throw new IllegalArgumentException(
          "an import with a source keystore cannot run over REST; it needs the vendor strategy");
    }
    List<Step> steps = new ArrayList<>();
    steps.add(new CheckKeystoreFingerprint(IMPORT_PHASE, request));
    steps.add(new StartImport(request));
    steps.add(new PollImport(polling));
    steps.add(new VerifyImport());
    return List.copyOf(steps);
  }
}
