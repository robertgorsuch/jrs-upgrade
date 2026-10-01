package com.jaspersoft.jrsupgrade.jrs.strategy;

import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsupgrade.jrs.api.ExportRequest;
import com.jaspersoft.jrsupgrade.jrs.api.ImportRequest;
import com.jaspersoft.jrsupgrade.jrs.service.ContextServiceRuntime;
import com.jaspersoft.jrsupgrade.jrs.service.ServiceRuntime;
import com.jaspersoft.jrsupgrade.jrs.service.ServiceSteps;
import com.jaspersoft.jrsupgrade.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsupgrade.jrs.vendor.BuildomaticLocator;
import com.jaspersoft.jrsupgrade.jrs.vendor.VendorTools;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Export and import with the vendor {@code js-export}/{@code js-import} tools (spec §7.3, §7.4,
 * §9.2). Invariants: the service is stopped before {@code js-import} runs, and before {@code
 * js-export} only when the request asks for it (#67, ADR-0021), and started (and waited for)
 * afterwards, in the returned steps themselves, which are the stop, start and wait steps every ops
 * plan uses ({@link ServiceSteps}, issue #43) with their runtime resolved from the run's context;
 * the vendor tree is located from {@code server.installDir} and verified before the service is
 * touched; it is not a {@code JrsAdapter} and only uses the context's adapter for the post-start
 * wait and the sidecar; the export sidecar is identical to the REST strategy's so imports can
 * verify either.
 */
public final class VendorCliStrategy implements ExportImportStrategy {

  public static final String EXPORT_PHASE = "export";
  public static final String IMPORT_PHASE = "import";

  /** Step id suffixes, unchanged since 1.0 so journaled runs still match a rebuilt plan. */
  private static final String STOP = ".stop-service";

  private static final String START = ".start-service";
  private static final String WAIT = ".wait-for-server";

  private final VendorAccess vendor;
  private final Polling polling;
  private final ServiceRuntime.Source runtime;

  public VendorCliStrategy(BuildomaticLocator locator, VendorTools tools) {
    this(VendorAccess.fixed(locator, tools), Polling.defaults());
  }

  public VendorCliStrategy(BuildomaticLocator locator, VendorTools tools, Polling polling) {
    this(VendorAccess.fixed(locator, tools), polling);
  }

  public VendorCliStrategy(VendorAccess vendor, Polling polling) {
    this.vendor = Objects.requireNonNull(vendor, "vendor");
    this.polling = Objects.requireNonNull(polling, "polling");
    this.runtime = ContextServiceRuntime.source(polling.clock(), polling.sleeper());
  }

  @Override
  public Kind kind() {
    return Kind.VENDOR_CLI;
  }

  @Override
  public boolean requiresServiceStop() {
    return true;
  }

  @Override
  public List<Step> exportSteps(ExportRequest request) {
    Objects.requireNonNull(request, "request");
    List<Step> steps = new ArrayList<>();
    steps.add(new LocateVendorTools(EXPORT_PHASE, List.of(Buildomatic.EXPORT_SCRIPT), vendor));
    if (request.stopService()) {
      steps.add(ServiceSteps.stop(runtime, EXPORT_PHASE, EXPORT_PHASE + STOP));
    }
    steps.add(new RunJsExport(request, vendor));
    if (request.stopService()) {
      steps.add(ServiceSteps.start(runtime, EXPORT_PHASE, EXPORT_PHASE + START));
      steps.add(ServiceSteps.waitForServer(runtime, EXPORT_PHASE, EXPORT_PHASE + WAIT));
    }
    steps.add(new WriteSidecar(EXPORT_PHASE, request, Kind.VENDOR_CLI, polling.clock()));
    return List.copyOf(steps);
  }

  @Override
  public List<Step> importSteps(ImportRequest request) {
    Objects.requireNonNull(request, "request");
    List<Step> steps = new ArrayList<>();
    steps.add(new CheckKeystoreFingerprint(IMPORT_PHASE, request));
    steps.add(new LocateVendorTools(IMPORT_PHASE, List.of(Buildomatic.IMPORT_SCRIPT), vendor));
    steps.add(ServiceSteps.stop(runtime, IMPORT_PHASE, IMPORT_PHASE + STOP));
    if (request.sourceKeystore().isPresent()) {
      steps.add(new ImportSourceKeystore(IMPORT_PHASE, request, vendor));
    }
    steps.add(new RunJsImport(request, vendor));
    steps.add(ServiceSteps.start(runtime, IMPORT_PHASE, IMPORT_PHASE + START));
    steps.add(ServiceSteps.waitForServer(runtime, IMPORT_PHASE, IMPORT_PHASE + WAIT));
    return List.copyOf(steps);
  }
}
