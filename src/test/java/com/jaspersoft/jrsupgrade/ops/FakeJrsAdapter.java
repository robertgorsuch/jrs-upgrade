package com.jaspersoft.jrsupgrade.ops;

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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory {@link JrsAdapter} for ops tests: records every call and lets a test shape the
 * identity, capabilities, keystore, repository tree and report output. Not thread-safe.
 */
public final class FakeJrsAdapter implements JrsAdapter {

  public static final URI BASE = URI.create("http://localhost:8080/jasperserver-pro");

  public final List<String> calls = new ArrayList<>();
  public ServerIdentity identity = identity("8.2.0");
  public Set<Capability> capabilities =
      EnumSet.of(
          Capability.EXPORT_ASYNC,
          Capability.IMPORT_ASYNC,
          Capability.REST_LOGIN,
          Capability.KEYSTORE_ENCRYPTION,
          Capability.TOKEN_AUTH,
          Capability.PREAUTH,
          Capability.ORGS);
  public KeystoreInfo keystore =
      new KeystoreInfo(
          true,
          Optional.of(Path.of("/home/jasperserver/.jrsks")),
          Optional.of(Path.of("/home/jasperserver/.jrsksp")),
          Optional.of("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"),
          Optional.empty());
  public HealthReport health = new HealthReport(true, Duration.ofMillis(5), List.of());
  public final Map<String, List<String>> folders = new HashMap<>();
  public final Set<String> missingReports = new HashSet<>();
  public byte[] reportBytes = fakePdf(2048);
  public boolean schedulerReachable = true;
  public Optional<RuntimeException> loginFailure = Optional.empty();
  public Handles.Phase exportPhase = Handles.Phase.READY;
  public int exportBytes = 512;

  public FakeJrsAdapter() {
    folders.put("/", new ArrayList<>(List.of("/public", "/temp", "/organizations")));
    folders.put("/temp", new ArrayList<>());
  }

  public static ServerIdentity identity(String version) {
    return new ServerIdentity(
        BASE,
        version,
        ServerIdentity.Edition.PRO,
        ServerIdentity.Tenancy.MULTI,
        Set.of("Fusion", "AHD", "EXP", "DB", "AUD", "ANA", "MT"),
        "20240101_1200",
        "yyyy-MM-dd");
  }

  public static byte[] fakePdf(int size) {
    byte[] bytes = new byte[size];
    byte[] magic = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(magic, 0, bytes, 0, magic.length);
    for (int i = magic.length; i < size; i++) {
      bytes[i] = (byte) ('a' + (i % 26));
    }
    return bytes;
  }

  /** When set, {@link #identity()} throws it (e.g. a 401 for refused credentials). */
  public Optional<RuntimeException> identityFailure = Optional.empty();

  @Override
  public ServerIdentity identity() {
    calls.add("identity");
    if (identityFailure.isPresent()) {
      throw identityFailure.get();
    }
    return identity;
  }

  @Override
  public ServerIdentity refreshIdentity() {
    calls.add("refreshIdentity");
    return identity;
  }

  @Override
  public Session login(Credentials credentials) {
    calls.add("login " + credentials.username());
    loginFailure.ifPresent(
        e -> {
          throw e;
        });
    return new Session(Session.AuthMode.BASIC, Optional.empty(), Instant.EPOCH);
  }

  @Override
  public Handles.ExportHandle startExport(ExportRequest request) {
    calls.add("startExport " + request.uris());
    return new Handles.ExportHandle("exp-1");
  }

  @Override
  public Handles.ExportStatus pollExport(Handles.ExportHandle handle) {
    calls.add("pollExport " + handle.id());
    return new Handles.ExportStatus(
        exportPhase, Optional.empty(), Optional.of("export.zip"), Optional.empty());
  }

  @Override
  public Path downloadExport(Handles.ExportHandle handle, Path target) {
    calls.add("downloadExport " + handle.id());
    write(target, new byte[exportBytes]);
    return target;
  }

  @Override
  public Handles.ImportHandle startImport(ImportRequest request, Path archive) {
    calls.add("startImport");
    return new Handles.ImportHandle("imp-1");
  }

  @Override
  public Handles.ImportStatus pollImport(Handles.ImportHandle handle) {
    calls.add("pollImport " + handle.id());
    return new Handles.ImportStatus(Handles.Phase.READY, Optional.empty(), Optional.empty());
  }

  @Override
  public void cancelImport(Handles.ImportHandle handle) {
    calls.add("cancelImport " + handle.id());
  }

  @Override
  public KeystoreInfo keystore() {
    calls.add("keystore");
    return keystore;
  }

  @Override
  public Set<Capability> capabilities() {
    calls.add("capabilities");
    return capabilities;
  }

  @Override
  public HealthReport health() {
    calls.add("health");
    return health;
  }

  @Override
  public List<String> listFolder(String folderUri) {
    calls.add("listFolder " + folderUri);
    List<String> children = folders.get(folderUri);
    if (children == null) {
      throw new IllegalStateException("404 folder " + folderUri + " not found");
    }
    return List.copyOf(children);
  }

  /** URIs the fake server answers 404 for; everything else exists. */
  public final Set<String> missingResources = new HashSet<>();

  @Override
  public boolean resourceExists(String uri) {
    calls.add("resourceExists " + uri);
    return !missingResources.contains(uri);
  }

  @Override
  public Path runReportToPdf(String reportUri, Path target) {
    calls.add("runReportToPdf " + reportUri);
    if (missingReports.contains(reportUri)) {
      throw new IllegalStateException("404 report " + reportUri + " not found");
    }
    write(target, reportBytes);
    return target;
  }

  @Override
  public boolean schedulerReachable() {
    calls.add("schedulerReachable");
    return schedulerReachable;
  }

  @Override
  public void createFolder(String folderUri, String label) {
    calls.add("createFolder " + folderUri);
    String parent = parentOf(folderUri);
    folders.computeIfAbsent(parent, k -> new ArrayList<>()).add(folderUri);
    folders.putIfAbsent(folderUri, new ArrayList<>());
  }

  @Override
  public void uploadJrxmlReport(String folderUri, String label, Path jrxml) {
    calls.add("uploadJrxmlReport " + folderUri + "/" + label);
    if (!Files.isRegularFile(jrxml)) {
      throw new IllegalStateException("no jrxml at " + jrxml);
    }
    folders.computeIfAbsent(folderUri, k -> new ArrayList<>()).add(folderUri + "/" + label);
  }

  @Override
  public void deleteResource(String uri) {
    calls.add("deleteResource " + uri);
    folders.remove(uri);
    List<String> siblings = folders.get(parentOf(uri));
    if (siblings != null) {
      siblings.remove(uri);
    }
  }

  private static String parentOf(String uri) {
    int slash = uri.lastIndexOf('/');
    return slash <= 0 ? "/" : uri.substring(0, slash);
  }

  private static void write(Path target, byte[] bytes) {
    try (OutputStream out = Files.newOutputStream(target)) {
      out.write(bytes);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
