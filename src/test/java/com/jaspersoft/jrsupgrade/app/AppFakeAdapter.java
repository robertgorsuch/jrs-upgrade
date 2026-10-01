package com.jaspersoft.jrsupgrade.app;

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
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** A healthy 8.2.0 PRO server that answers every read-only call and records nothing. */
public final class AppFakeAdapter implements JrsAdapter {

  public volatile URI base = URI.create("http://localhost:8080/jasperserver-pro");
  public volatile String version = "8.2.0";

  @Override
  public ServerIdentity identity() {
    return new ServerIdentity(
        base,
        version,
        ServerIdentity.Edition.PRO,
        ServerIdentity.Tenancy.MULTI,
        Set.of("Fusion", "AHD", "EXP", "DB", "AUD", "ANA", "MT"),
        "20240101_1200",
        "yyyy-MM-dd");
  }

  @Override
  public ServerIdentity refreshIdentity() {
    return identity();
  }

  @Override
  public Session login(Credentials credentials) {
    return new Session(Session.AuthMode.BASIC, Optional.empty(), Instant.EPOCH);
  }

  @Override
  public Handles.ExportHandle startExport(ExportRequest request) {
    return new Handles.ExportHandle("exp-1");
  }

  @Override
  public Handles.ExportStatus pollExport(Handles.ExportHandle handle) {
    return new Handles.ExportStatus(
        Handles.Phase.READY, Optional.empty(), Optional.of("export.zip"), Optional.empty());
  }

  @Override
  public Path downloadExport(Handles.ExportHandle handle, Path target) {
    write(target, new byte[64]);
    return target;
  }

  @Override
  public Handles.ImportHandle startImport(ImportRequest request, Path archive) {
    return new Handles.ImportHandle("imp-1");
  }

  @Override
  public Handles.ImportStatus pollImport(Handles.ImportHandle handle) {
    return new Handles.ImportStatus(Handles.Phase.READY, Optional.empty(), Optional.empty());
  }

  @Override
  public void cancelImport(Handles.ImportHandle handle) {}

  @Override
  public KeystoreInfo keystore() {
    return new KeystoreInfo(
        true,
        Optional.of(Path.of("/home/jasperserver/.jrsks")),
        Optional.of(Path.of("/home/jasperserver/.jrsksp")),
        Optional.of("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
        Optional.empty());
  }

  @Override
  public Set<Capability> capabilities() {
    return EnumSet.of(
        Capability.EXPORT_ASYNC,
        Capability.IMPORT_ASYNC,
        Capability.REST_LOGIN,
        Capability.KEYSTORE_ENCRYPTION,
        Capability.TOKEN_AUTH,
        Capability.PREAUTH,
        Capability.ORGS);
  }

  @Override
  public HealthReport health() {
    return new HealthReport(true, Duration.ofMillis(1), List.of());
  }

  @Override
  public List<String> listFolder(String folderUri) {
    return List.of("/public", "/temp");
  }

  @Override
  public boolean resourceExists(String uri) {
    return true;
  }

  @Override
  public Path runReportToPdf(String reportUri, Path target) {
    byte[] bytes = new byte[2048];
    byte[] magic = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(magic, 0, bytes, 0, magic.length);
    write(target, bytes);
    return target;
  }

  @Override
  public boolean schedulerReachable() {
    return true;
  }

  @Override
  public void createFolder(String folderUri, String label) {}

  @Override
  public void uploadJrxmlReport(String folderUri, String label, Path jrxml) {}

  @Override
  public void deleteResource(String uri) {}

  private static void write(Path target, byte[] bytes) {
    try (OutputStream out = Files.newOutputStream(target)) {
      out.write(bytes);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
