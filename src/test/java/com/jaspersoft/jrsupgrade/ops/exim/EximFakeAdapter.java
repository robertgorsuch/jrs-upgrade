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
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A scriptable {@link JrsAdapter} for export/import tests: records every export and import start,
 * answers export polls with {@code READY}, and answers import polls from a queue of phases (empty
 * queue means {@code READY}) so a test can make the first import fail and the restore succeed.
 */
final class EximFakeAdapter implements JrsAdapter {

  static final URI BASE = URI.create("http://localhost:8080/jasperserver-pro");
  static final String FINGERPRINT =
      "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

  /** One recorded {@link #startImport} call. */
  record ImportCall(ImportRequest request, Path archive) {}

  final List<ExportRequest> exports = new ArrayList<>();
  final List<ImportCall> imports = new ArrayList<>();
  final Deque<Handles.Phase> importPhases = new ArrayDeque<>();
  Set<Capability> capabilities =
      EnumSet.of(Capability.EXPORT_ASYNC, Capability.IMPORT_ASYNC, Capability.KEYSTORE_ENCRYPTION);
  KeystoreInfo keystore =
      new KeystoreInfo(
          true,
          Optional.of(Path.of("/home/jasperserver/.jrsks")),
          Optional.of(Path.of("/home/jasperserver/.jrsksp")),
          Optional.of(FINGERPRINT),
          Optional.empty());
  int exportBytes = 4096;

  /**
   * When set, the exact archive every download writes instead of {@link #exportBytes} of filler.
   */
  byte[] exportArchive;

  @Override
  public ServerIdentity identity() {
    return new ServerIdentity(
        BASE,
        "8.2.0",
        ServerIdentity.Edition.PRO,
        ServerIdentity.Tenancy.MULTI,
        Set.of("Fusion", "MT"),
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
    exports.add(request);
    return new Handles.ExportHandle("exp-" + exports.size());
  }

  @Override
  public Handles.ExportStatus pollExport(Handles.ExportHandle handle) {
    return new Handles.ExportStatus(
        Handles.Phase.READY, Optional.empty(), Optional.of("export.zip"), Optional.empty());
  }

  @Override
  public Path downloadExport(Handles.ExportHandle handle, Path target) {
    byte[] bytes = exportArchive;
    if (bytes == null) {
      bytes = new byte[exportBytes];
      bytes[0] = 'P';
      bytes[1] = 'K';
    }
    try (OutputStream out = Files.newOutputStream(target)) {
      out.write(bytes);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return target;
  }

  /**
   * URIs that come to exist when an import starts: the fake's stand-in for what an import creates.
   * Only meaningful together with {@link #existing}.
   */
  Set<String> createdOnImport = Set.of();

  @Override
  public Handles.ImportHandle startImport(ImportRequest request, Path archive) {
    imports.add(new ImportCall(request, archive));
    if (existing.isPresent() && !createdOnImport.isEmpty()) {
      Set<String> now = new java.util.HashSet<>(existing.get());
      now.addAll(createdOnImport);
      existing = Optional.of(now);
    }
    return new Handles.ImportHandle("imp-" + imports.size());
  }

  @Override
  public Handles.ImportStatus pollImport(Handles.ImportHandle handle) {
    Handles.Phase phase = importPhases.isEmpty() ? Handles.Phase.READY : importPhases.poll();
    return new Handles.ImportStatus(
        phase,
        phase == Handles.Phase.FAILED ? Optional.of("simulated import failure") : Optional.empty(),
        Optional.empty());
  }

  @Override
  public void cancelImport(Handles.ImportHandle handle) {}

  @Override
  public KeystoreInfo keystore() {
    return keystore;
  }

  @Override
  public Set<Capability> capabilities() {
    return capabilities;
  }

  @Override
  public HealthReport health() {
    return new HealthReport(true, Duration.ofMillis(1), List.of());
  }

  @Override
  public List<String> listFolder(String folderUri) {
    return List.of("/public");
  }

  /** The URIs that exist on the fake server; empty means every URI exists. */
  Optional<Set<String>> existing = Optional.empty();

  final List<String> existenceChecks = new ArrayList<>();

  /** When set, every existence check fails with this message. */
  Optional<String> existsFailure = Optional.empty();

  @Override
  public boolean resourceExists(String uri) {
    existenceChecks.add(uri);
    requireUp("exists " + uri);
    if (existsFailure.isPresent()) {
      throw new IllegalStateException(existsFailure.get());
    }
    return existing.map(s -> s.contains(uri)).orElse(true);
  }

  @Override
  public Path runReportToPdf(String reportUri, Path target) {
    throw new UnsupportedOperationException("not used by export/import");
  }

  @Override
  public boolean schedulerReachable() {
    return true;
  }

  @Override
  public void createFolder(String folderUri, String label) {}

  @Override
  public void uploadJrxmlReport(String folderUri, String label, Path jrxml) {}

  /**
   * Issue #100: the recursive listings the fake answers, in order; the last one is repeated, minus
   * whatever was deleted since, so a second rollback finds nothing new.
   */
  final Deque<List<String>> trees = new ArrayDeque<>();

  /** URIs deleted through {@link #deleteResource}, in order. */
  final List<String> deleted = new ArrayList<>();

  /** When set, every recursive listing fails with this message. */
  Optional<String> listFailure = Optional.empty();

  /** The listing whose ordinal exceeds this fails; the default never does. */
  int failListingAfter = Integer.MAX_VALUE;

  private int listings;
  private List<String> lastTree = List.of();

  /**
   * ADR-0040: whether the web application answers; a test ties it to the fake service's state so a
   * REST listing or deletion made while the service is stopped fails as it would on a real server.
   */
  java.util.function.BooleanSupplier serverUp = () -> true;

  /** REST calls refused because {@link #serverUp} said the server was down, in order. */
  final List<String> refusedWhileDown = new ArrayList<>();

  private void requireUp(String call) {
    if (!serverUp.getAsBoolean()) {
      refusedWhileDown.add(call);
      throw new IllegalStateException("connection refused: the server is down (" + call + ")");
    }
  }

  @Override
  public List<String> listTree(String folderUri) {
    requireUp("list " + folderUri);
    if (listFailure.isPresent() || ++listings > failListingAfter) {
      throw new IllegalStateException(listFailure.orElse("listing refused by the fake"));
    }
    if (!trees.isEmpty()) {
      lastTree = trees.poll();
    }
    return lastTree.stream().filter(u -> !deleted.contains(u)).toList();
  }

  /** URIs whose deletion the fake refuses. */
  final Set<String> undeletable = new java.util.HashSet<>();

  @Override
  public void deleteResource(String uri) {
    requireUp("delete " + uri);
    if (undeletable.contains(uri)) {
      throw new IllegalStateException("deletion of " + uri + " refused by the fake");
    }
    deleted.add(uri);
    // a deleted folder takes its content with it, so a second rollback finds nothing there
    existing =
        existing.map(
            all ->
                all.stream()
                    .filter(u -> !u.equals(uri) && !u.startsWith(uri + "/"))
                    .collect(java.util.stream.Collectors.toSet()));
  }
}
