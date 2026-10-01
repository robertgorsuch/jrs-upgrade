package com.jaspersoft.jrsupgrade.ops.exim;

import com.jaspersoft.jrsupgrade.jrs.api.Capability;
import com.jaspersoft.jrsupgrade.jrs.api.Credentials;
import com.jaspersoft.jrsupgrade.jrs.api.ExportRequest;
import com.jaspersoft.jrsupgrade.jrs.api.Handles;
import com.jaspersoft.jrsupgrade.jrs.api.HealthReport;
import com.jaspersoft.jrsupgrade.jrs.api.ImportRequest;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.api.KeystoreInfo;
import com.jaspersoft.jrsupgrade.jrs.api.ServerIdentity;
import com.jaspersoft.jrsupgrade.jrs.api.Session;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A {@link JrsAdapter} that connects on first use. The run context registers one of these so plans
 * whose steps never talk to the server (a hotfix that only swaps files) run without a reachable
 * server, while export and import steps obtain the real adapter through {@code
 * ctx.service(JrsAdapter.class)} exactly when they need it. Invariants: every call delegates to
 * {@code supplier.get()}, so a memoising supplier connects once and a failing one surfaces its
 * {@link com.jaspersoft.jrsupgrade.jrs.api.JrsUnreachableException} at the step that needed the
 * server, where it is classified by that step; nothing is cached here.
 */
public final class DeferredJrsAdapter implements JrsAdapter {

  private final Supplier<JrsAdapter> supplier;

  public DeferredJrsAdapter(Supplier<JrsAdapter> supplier) {
    this.supplier = Objects.requireNonNull(supplier, "supplier");
  }

  private JrsAdapter adapter() {
    return supplier.get();
  }

  @Override
  public ServerIdentity identity() {
    return adapter().identity();
  }

  @Override
  public ServerIdentity refreshIdentity() {
    return adapter().refreshIdentity();
  }

  @Override
  public Session login(Credentials credentials) {
    return adapter().login(credentials);
  }

  @Override
  public Handles.ExportHandle startExport(ExportRequest request) {
    return adapter().startExport(request);
  }

  @Override
  public Handles.ExportStatus pollExport(Handles.ExportHandle handle) {
    return adapter().pollExport(handle);
  }

  @Override
  public Path downloadExport(Handles.ExportHandle handle, Path target) {
    return adapter().downloadExport(handle, target);
  }

  @Override
  public Handles.ImportHandle startImport(ImportRequest request, Path archive) {
    return adapter().startImport(request, archive);
  }

  @Override
  public Handles.ImportStatus pollImport(Handles.ImportHandle handle) {
    return adapter().pollImport(handle);
  }

  @Override
  public void cancelImport(Handles.ImportHandle handle) {
    adapter().cancelImport(handle);
  }

  @Override
  public KeystoreInfo keystore() {
    return adapter().keystore();
  }

  @Override
  public Set<Capability> capabilities() {
    return adapter().capabilities();
  }

  @Override
  public HealthReport health() {
    return adapter().health();
  }

  @Override
  public List<String> listFolder(String folderUri) {
    return adapter().listFolder(folderUri);
  }

  @Override
  public List<String> listTree(String folderUri) {
    return adapter().listTree(folderUri);
  }

  @Override
  public boolean resourceExists(String uri) {
    return adapter().resourceExists(uri);
  }

  @Override
  public Path runReportToPdf(String reportUri, Path target) {
    return adapter().runReportToPdf(reportUri, target);
  }

  @Override
  public boolean schedulerReachable() {
    return adapter().schedulerReachable();
  }

  @Override
  public void createFolder(String folderUri, String label) {
    adapter().createFolder(folderUri, label);
  }

  @Override
  public void uploadJrxmlReport(String folderUri, String label, Path jrxml) {
    adapter().uploadJrxmlReport(folderUri, label, jrxml);
  }

  @Override
  public void deleteResource(String uri) {
    adapter().deleteResource(uri);
  }
}
