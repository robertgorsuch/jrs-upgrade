package com.jaspersoft.jrsupgrade.jrs;

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
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Scripted {@link JrsAdapter} for strategy tests: identity, capabilities and keystore are plain
 * fields; the uncached identity read used by {@code WaitForServer} answers according to {@code
 * refreshIdentityBehaviour} and the repository listing according to {@code listFolderBehaviour};
 * the async export/import methods are unsupported (use WireMock for those).
 */
public final class FakeJrsAdapter implements JrsAdapter {

  public static final ServerIdentity IDENTITY =
      new ServerIdentity(
          URI.create("http://localhost:8080/jasperserver-pro"),
          "8.2.0",
          ServerIdentity.Edition.PRO,
          ServerIdentity.Tenancy.MULTI,
          Set.of("MT"),
          "20230101",
          "yyyy-MM-dd");

  public Set<Capability> capabilities =
      EnumSet.of(Capability.EXPORT_ASYNC, Capability.IMPORT_ASYNC);
  public KeystoreInfo keystore = KeystoreInfo.absent("no keystore in fake");
  public Supplier<Set<Capability>> capabilitySupplier = () -> capabilities;
  public Supplier<List<String>> listFolderBehaviour = () -> List.of("/public");
  public int listFolderCalls;
  public Supplier<ServerIdentity> refreshIdentityBehaviour = () -> IDENTITY;
  public int refreshIdentityCalls;

  public FakeJrsAdapter withKeystore(KeystoreInfo info) {
    this.keystore = info;
    return this;
  }

  public FakeJrsAdapter withCapabilities(Set<Capability> caps) {
    this.capabilities = caps.isEmpty() ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(caps);
    return this;
  }

  public FakeJrsAdapter failingProbe(RuntimeException e) {
    this.capabilitySupplier =
        () -> {
          throw e;
        };
    return this;
  }

  @Override
  public ServerIdentity identity() {
    return IDENTITY;
  }

  @Override
  public ServerIdentity refreshIdentity() {
    refreshIdentityCalls++;
    return refreshIdentityBehaviour.get();
  }

  @Override
  public Session login(Credentials credentials) {
    throw new UnsupportedOperationException("fake");
  }

  @Override
  public Handles.ExportHandle startExport(ExportRequest request) {
    throw new UnsupportedOperationException("fake");
  }

  @Override
  public Handles.ExportStatus pollExport(Handles.ExportHandle handle) {
    throw new UnsupportedOperationException("fake");
  }

  @Override
  public Path downloadExport(Handles.ExportHandle handle, Path target) {
    throw new UnsupportedOperationException("fake");
  }

  @Override
  public Handles.ImportHandle startImport(ImportRequest request, Path archive) {
    throw new UnsupportedOperationException("fake");
  }

  @Override
  public Handles.ImportStatus pollImport(Handles.ImportHandle handle) {
    throw new UnsupportedOperationException("fake");
  }

  @Override
  public void cancelImport(Handles.ImportHandle handle) {
    throw new UnsupportedOperationException("fake");
  }

  @Override
  public KeystoreInfo keystore() {
    return keystore;
  }

  @Override
  public Set<Capability> capabilities() {
    return capabilitySupplier.get();
  }

  @Override
  public HealthReport health() {
    return new HealthReport(true, Duration.ZERO, List.of());
  }

  @Override
  public List<String> listFolder(String folderUri) {
    listFolderCalls++;
    return listFolderBehaviour.get();
  }

  public java.util.function.Predicate<String> resourceExistsBehaviour = uri -> true;

  @Override
  public boolean resourceExists(String uri) {
    return resourceExistsBehaviour.test(uri);
  }

  @Override
  public Path runReportToPdf(String reportUri, Path target) {
    throw new UnsupportedOperationException("fake");
  }

  @Override
  public boolean schedulerReachable() {
    return true;
  }

  @Override
  public void createFolder(String folderUri, String label) {
    throw new UnsupportedOperationException("fake");
  }

  @Override
  public void uploadJrxmlReport(String folderUri, String label, Path jrxml) {
    throw new UnsupportedOperationException("fake");
  }

  @Override
  public void deleteResource(String uri) {
    throw new UnsupportedOperationException("fake");
  }

  public static KeystoreInfo keystoreWith(String fingerprint, Optional<Path> file) {
    return new KeystoreInfo(
        true,
        file,
        file.map(f -> f.resolveSibling(".jrsksp")),
        Optional.of(fingerprint),
        Optional.of("test"));
  }
}
