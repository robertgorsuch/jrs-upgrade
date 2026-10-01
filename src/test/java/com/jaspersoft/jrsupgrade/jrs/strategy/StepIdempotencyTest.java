package com.jaspersoft.jrsupgrade.jrs.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.core.platform.ProcessRunner;
import com.jaspersoft.jrsupgrade.core.platform.ServiceController;
import com.jaspersoft.jrsupgrade.jrs.FakeJrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.api.Capability;
import com.jaspersoft.jrsupgrade.jrs.api.Credentials;
import com.jaspersoft.jrsupgrade.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsupgrade.jrs.api.ExportRequest;
import com.jaspersoft.jrsupgrade.jrs.api.Handles;
import com.jaspersoft.jrsupgrade.jrs.api.HealthReport;
import com.jaspersoft.jrsupgrade.jrs.api.ImportRequest;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.api.KeystoreInfo;
import com.jaspersoft.jrsupgrade.jrs.api.ServerIdentity;
import com.jaspersoft.jrsupgrade.jrs.api.Session;
import com.jaspersoft.jrsupgrade.jrs.service.ContextServiceRuntime;
import com.jaspersoft.jrsupgrade.jrs.service.ServiceRuntime;
import com.jaspersoft.jrsupgrade.jrs.service.ServiceSteps;
import com.jaspersoft.jrsupgrade.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsupgrade.jrs.vendor.BuildomaticLocator;
import com.jaspersoft.jrsupgrade.jrs.vendor.VendorTools;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 8 idempotency of every strategy step (spec §6.1, §7.3, §7.4): re-executing a step after a
 * complete or partial first execution (what {@code runs recover --resume} does after a crash)
 * converges on the state one clean execution leaves, and every compensation run twice equals one.
 * Vendor tools are re-invoked by design (the archive is rewritten, {@code js-import} runs again
 * with the same arguments); REST task starts and the keystore backup are performed once thanks to
 * the run-scoped files; read-only steps touch nothing.
 */
class StepIdempotencyTest {

  private static final String FP = "a".repeat(64);
  private static final String ARCHIVE = "PK-archive-bytes";

  @TempDir Path tmp;
  private StrategyFixture fx;
  private Path installDir;
  private Path javaHome;
  private Path output;
  private Path archive;
  private Path serverKeystore;
  private Path serverKeystoreProps;
  private Path sourceKeystore;
  private Config config;
  private AsyncAdapter adapter;
  private VendorAccess vendor;

  @BeforeEach
  void setUp() throws IOException {
    fx = new StrategyFixture(tmp);
    installDir = tmp.resolve("jrs");
    Path buildomatic = Files.createDirectories(installDir.resolve("buildomatic"));
    Files.writeString(buildomatic.resolve("js-export.sh"), "#!/bin/sh\n");
    Files.writeString(buildomatic.resolve("js-import.sh"), "#!/bin/sh\n");
    javaHome = Files.createDirectories(tmp.resolve("jdk"));
    output = tmp.resolve("out").resolve("export.zip");
    archive = tmp.resolve("in.zip");
    Files.writeString(archive, "PK".repeat(100));
    Path serverHome = Files.createDirectories(tmp.resolve("server-home"));
    serverKeystore = serverHome.resolve(".jrsks");
    serverKeystoreProps = serverHome.resolve(".jrsksp");
    Files.writeString(serverKeystore, "server-ks");
    Files.writeString(serverKeystoreProps, "server-ksp");
    sourceKeystore = tmp.resolve("source.jrsks");
    Files.writeString(sourceKeystore, "source-ks");
    config = StrategyFixture.vendorConfig(installDir, Optional.of(javaHome));
    adapter = new AsyncAdapter(serverKeystore, serverKeystoreProps);
    vendor =
        VendorAccess.fixed(
            new BuildomaticLocator(fx.platform),
            new VendorTools(fx.processes, fx.platform.files(), fx.redactor));
  }

  @AfterEach
  void tearDown() {
    fx.close();
  }

  private Context ctx() {
    return fx.context(config, adapter);
  }

  private ExportRequest exportRequest() {
    return new ExportRequest(
        ExportRequest.Scope.REPOSITORY,
        Set.of("/public"),
        true,
        false,
        false,
        false,
        false,
        false,
        output);
  }

  private ImportRequest importRequest(Optional<Path> source) {
    return new ImportRequest(
        archive, true, false, false, false, false, false, false, source, Optional.empty());
  }

  private static void executeOk(Step step, Context ctx) {
    assertThat(step.precheck(ctx))
        .as("precheck " + step.id())
        .isNotInstanceOf(CheckResult.Fail.class);
    StepResult result = step.execute(ctx, EventSink.discard());
    assertThat(result).as("execute " + step.id() + ": " + result).isInstanceOf(StepResult.Ok.class);
    assertThat(step.postcheck(ctx))
        .as("postcheck " + step.id())
        .isNotInstanceOf(CheckResult.Fail.class);
  }

  private static void compensateOk(Step step, Context ctx) {
    StepResult result = step.compensate(ctx, EventSink.discard());
    assertThat(result)
        .as("compensate " + step.id() + ": " + result)
        .isInstanceOf(StepResult.Ok.class);
  }

  /** The tool writes the archive to the path after {@code --output-zip} and says it is done. */
  private void vendorWritesArchive() {
    fx.processes.answer(
        (request, onLine) -> {
          int i = request.command().indexOf("--output-zip");
          try {
            Files.writeString(Path.of(request.command().get(i + 1)), ARCHIVE);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          onLine.accept(
              new ProcessRunner.OutputLine(
                  ProcessRunner.OutputLine.Stream.STDOUT, "Processing started"));
          onLine.accept(
              new ProcessRunner.OutputLine(ProcessRunner.OutputLine.Stream.STDOUT, "Done"));
          return new ProcessRunner.Result(0, false, Duration.ofMillis(1));
        });
  }

  private Map<String, String> files() throws IOException {
    Map<String, String> out = new TreeMap<>();
    List<Path> all = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(tmp)) {
      walk.filter(Files::isRegularFile)
          .filter(p -> !p.getFileName().toString().contains("state.db"))
          .forEach(all::add);
    }
    for (Path p : all) {
      out.put(tmp.relativize(p).toString().replace('\\', '/'), fx.platform.files().sha256(p));
    }
    return out;
  }

  // ---------------------------------------------------------------- REST export/import

  @Test
  void should_start_one_task_when_start_export_and_start_import_execute_twice() throws IOException {
    Context ctx = ctx();
    Step startExport = new StartExport(exportRequest());
    Step startImport = new StartImport(importRequest(Optional.empty()));

    executeOk(startExport, ctx);
    executeOk(startExport, ctx);
    executeOk(startImport, ctx);
    executeOk(startImport, ctx);

    assertThat(adapter.exportStarts).isEqualTo(1);
    assertThat(adapter.importStarts).isEqualTo(1);
    assertThat(RunFiles.read(RunFiles.in(ctx, RunFiles.EXPORT_HANDLE))).contains("exp-1");
    assertThat(RunFiles.read(RunFiles.in(ctx, RunFiles.IMPORT_HANDLE))).contains("imp-1");
  }

  @Test
  void should_leave_one_archive_when_download_export_executes_twice_after_a_partial_write()
      throws IOException {
    Context ctx = ctx();
    RunFiles.write(RunFiles.in(ctx, RunFiles.EXPORT_HANDLE), "exp-1");
    Files.createDirectories(output.getParent());
    Files.writeString(RunFiles.partOf(output), "half-written before the crash");
    Step download = new DownloadExport(output);

    executeOk(download, ctx);
    String once = fx.platform.files().sha256(output);
    executeOk(download, ctx);

    assertThat(adapter.downloads).as("the download is repeated by design").isEqualTo(2);
    assertThat(fx.platform.files().sha256(output)).isEqualTo(once);
    assertThat(Files.readAllBytes(output)).isEqualTo(AsyncAdapter.BYTES);
    assertThat(RunFiles.partOf(output)).doesNotExist();
    try (Stream<Path> siblings = Files.list(output.getParent())) {
      assertThat(siblings).containsExactly(output);
    }
  }

  @Test
  void should_leave_nothing_when_download_export_compensates_twice() throws IOException {
    Context ctx = ctx();
    Files.createDirectories(output.getParent());
    Files.writeString(output, "archive");
    Files.writeString(RunFiles.partOf(output), "partial");
    Step download = new DownloadExport(output);

    compensateOk(download, ctx);
    compensateOk(download, ctx);

    assertThat(output).doesNotExist();
    assertThat(RunFiles.partOf(output)).doesNotExist();
  }

  @Test
  void should_write_the_same_sidecar_when_write_sidecar_executes_twice() throws IOException {
    Context ctx = ctx();
    Files.createDirectories(output.getParent());
    Files.writeString(output, ARCHIVE);
    Step sidecar =
        new WriteSidecar("export", exportRequest(), ExportImportStrategy.Kind.REST, fx.clock);

    executeOk(sidecar, ctx);
    String once = fx.platform.files().sha256(Sidecar.pathFor(output));
    executeOk(sidecar, ctx);

    assertThat(fx.platform.files().sha256(Sidecar.pathFor(output))).isEqualTo(once);
    assertThat(Sidecar.read(Sidecar.pathFor(output)))
        .isPresent()
        .get()
        .satisfies(s -> assertThat(s.sha256()).isEqualTo(fx.platform.files().sha256(output)));
    try (Stream<Path> siblings = Files.list(output.getParent())) {
      assertThat(siblings).containsExactlyInAnyOrder(output, Sidecar.pathFor(output));
    }
  }

  @Test
  void should_leave_nothing_when_write_sidecar_compensates_twice() throws IOException {
    Context ctx = ctx();
    Files.createDirectories(output.getParent());
    Files.writeString(output, ARCHIVE);
    Step sidecar =
        new WriteSidecar("export", exportRequest(), ExportImportStrategy.Kind.REST, fx.clock);
    executeOk(sidecar, ctx);

    compensateOk(sidecar, ctx);
    compensateOk(sidecar, ctx);

    assertThat(Sidecar.pathFor(output)).doesNotExist();
    assertThat(output).exists();
  }

  // ---------------------------------------------------------------- vendor export/import

  @Test
  void should_replace_the_partial_archive_when_run_js_export_executes_twice() throws IOException {
    vendorWritesArchive();
    Context ctx = ctx();
    Files.createDirectories(output.getParent());
    Files.writeString(RunFiles.partOf(output), "half-written before the crash");
    Step export = new RunJsExport(exportRequest(), vendor);

    executeOk(export, ctx);
    executeOk(export, ctx);

    assertThat(fx.processes.requests()).hasSize(2);
    assertThat(fx.processes.requests().get(0).command())
        .isEqualTo(fx.processes.requests().get(1).command());
    assertThat(Files.readString(output)).isEqualTo(ARCHIVE);
    assertThat(RunFiles.partOf(output)).doesNotExist();
    try (Stream<Path> siblings = Files.list(output.getParent())) {
      assertThat(siblings).containsExactly(output);
    }
  }

  @Test
  void should_leave_nothing_when_run_js_export_compensates_twice() throws IOException {
    vendorWritesArchive();
    Context ctx = ctx();
    Step export = new RunJsExport(exportRequest(), vendor);
    executeOk(export, ctx);
    Files.writeString(RunFiles.partOf(output), "stray partial");

    compensateOk(export, ctx);
    compensateOk(export, ctx);

    assertThat(output).doesNotExist();
    assertThat(RunFiles.partOf(output)).doesNotExist();
  }

  @Test
  void should_invoke_js_import_identically_when_run_js_import_executes_twice() throws IOException {
    fx.processes.exit(0, "VALIDATION COMPLETED", "Processing started", "Done");
    Context ctx = ctx();
    Step importStep = new RunJsImport(importRequest(Optional.empty()), vendor);

    executeOk(importStep, ctx);
    executeOk(importStep, ctx);
    compensateOk(importStep, ctx);
    compensateOk(importStep, ctx);

    List<ProcessRunner.Request> requests = fx.processes.requests();
    assertThat(requests)
        .as("the vendor import is repeated by design; compensation runs nothing")
        .hasSize(2);
    assertThat(requests.get(0).command()).isEqualTo(requests.get(1).command());
    assertThat(requests.get(0).environment()).isEqualTo(requests.get(1).environment());
    assertThat(requests.get(0).command()).contains("--input-zip", archive.toString(), "--update");
  }

  @Test
  void should_back_up_the_keystore_once_when_import_source_keystore_executes_twice()
      throws IOException {
    Context ctx = ctx();
    Step step =
        new ImportSourceKeystore("import", importRequest(Optional.of(sourceKeystore)), vendor);
    Path manifest = RunFiles.in(ctx, ImportSourceKeystore.MANIFEST);
    Path backup = RunFiles.in(ctx, ImportSourceKeystore.BACKUP_DIR).resolve(".jrsks");

    executeOk(step, ctx);
    String manifestOnce = fx.platform.files().sha256(manifest);
    assertThat(Files.readString(backup)).isEqualTo("server-ks");
    executeOk(step, ctx);

    assertThat(fx.processes.requests())
        .as("the keystore options ride on the archive import, not on a run of their own")
        .isEmpty();
    assertThat(Files.readString(backup))
        .as("the pristine copy is never overwritten")
        .isEqualTo("server-ks");
    assertThat(fx.platform.files().sha256(manifest)).isEqualTo(manifestOnce);
    assertThat(Files.readString(serverKeystore)).isEqualTo("server-ks");
  }

  @Test
  void should_restore_the_original_keystore_when_import_source_keystore_compensates_twice()
      throws IOException {
    Context ctx = ctx();
    Step step =
        new ImportSourceKeystore("import", importRequest(Optional.of(sourceKeystore)), vendor);
    executeOk(step, ctx);
    // the archive import (not this step) may have replaced the server keystore
    Files.writeString(serverKeystore, "source-ks");

    compensateOk(step, ctx);
    assertThat(Files.readString(serverKeystore)).isEqualTo("server-ks");
    compensateOk(step, ctx);

    assertThat(Files.readString(serverKeystore)).isEqualTo("server-ks");
    assertThat(Files.readString(serverKeystoreProps)).isEqualTo("server-ksp");
  }

  // ---------------------------------------------------------------- service steps

  /**
   * The shared service steps (jrs.service) resolved from the context, as the vendor strategy does.
   */
  private ServiceRuntime.Source runtime() {
    return ContextServiceRuntime.source(fx.polling.clock(), fx.polling.sleeper());
  }

  @Test
  void should_stop_once_when_stop_service_executes_twice() {
    Step stop = ServiceSteps.stop(runtime(), "export", "export.stop-service");
    Context ctx = ctx();

    executeOk(stop, ctx);
    executeOk(stop, ctx);

    assertThat(fx.service.calls()).containsExactly("stop");
    assertThat(fx.service.state()).isEqualTo(ServiceController.State.STOPPED);
    assertThat(RunFiles.in(ctx, "export.stop-service.stopped")).exists();
  }

  @Test
  void should_start_once_when_stop_service_compensates_twice() {
    Step stop = ServiceSteps.stop(runtime(), "export", "export.stop-service");
    Context ctx = ctx();
    executeOk(stop, ctx);

    compensateOk(stop, ctx);
    compensateOk(stop, ctx);

    assertThat(fx.service.calls()).containsExactly("stop", "start");
    assertThat(fx.service.state()).isEqualTo(ServiceController.State.RUNNING);
    assertThat(RunFiles.in(ctx, "export.stop-service.stopped")).doesNotExist();
  }

  @Test
  void should_start_once_when_start_service_executes_twice() {
    fx.service.stop(Duration.ZERO);
    Step start = ServiceSteps.start(runtime(), "export", "export.start-service");
    Context ctx = ctx();

    executeOk(start, ctx);
    executeOk(start, ctx);

    assertThat(fx.service.calls()).containsExactly("stop", "start");
    assertThat(fx.service.state()).isEqualTo(ServiceController.State.RUNNING);
  }

  @Test
  void should_stop_once_when_start_service_compensates_twice() {
    fx.service.stop(Duration.ZERO);
    Step start = ServiceSteps.start(runtime(), "export", "export.start-service");
    Context ctx = ctx();
    executeOk(start, ctx);

    compensateOk(start, ctx);
    compensateOk(start, ctx);

    assertThat(fx.service.calls()).containsExactly("stop", "start", "stop");
    assertThat(fx.service.state()).isEqualTo(ServiceController.State.STOPPED);
  }

  // ---------------------------------------------------------------- read-only steps

  @Test
  void should_mutate_nothing_when_read_only_steps_execute_twice() throws IOException {
    Context ctx = ctx();
    RunFiles.write(RunFiles.in(ctx, RunFiles.EXPORT_HANDLE), "exp-1");
    RunFiles.write(RunFiles.in(ctx, RunFiles.IMPORT_HANDLE), "imp-1");
    Sidecar.write(
        Sidecar.pathFor(archive),
        new Sidecar(
            StrategyFixture.NOW,
            "srv",
            "8.2.0",
            Optional.of(FP),
            new Sidecar.Flags(
                ExportRequest.Scope.REPOSITORY,
                List.of("/public"),
                false,
                false,
                false,
                false,
                false,
                false),
            "deadbeef",
            ExportImportStrategy.Kind.REST));
    List<Step> readOnly =
        List.of(
            new CheckKeystoreFingerprint("import", importRequest(Optional.empty())),
            new LocateVendorTools("export", List.of(Buildomatic.EXPORT_SCRIPT), vendor),
            new PollExport(fx.polling),
            new PollImport(fx.polling),
            new VerifyImport(),
            ServiceSteps.waitForServer(runtime(), "export", "export.wait-for-server"));
    Map<String, String> before = files();

    for (Step step : readOnly) {
      assertThat(step.mutating()).as(step.id()).isFalse();
      executeOk(step, ctx);
      executeOk(step, ctx);
      compensateOk(step, ctx);
      assertThat(files()).as(step.id()).isEqualTo(before);
    }
    assertThat(fx.processes.requests()).isEmpty();
    assertThat(fx.service.calls()).isEmpty();
  }

  /**
   * A {@link JrsAdapter} whose asynchronous export and import really answer: every start yields a
   * fresh task id, polls report READY, downloads write {@link #BYTES}, and the keystore points at
   * real files so the source-keystore step can back them up.
   */
  private static final class AsyncAdapter implements JrsAdapter {
    static final byte[] BYTES = "PK downloaded archive".getBytes(StandardCharsets.UTF_8);

    int exportStarts;
    int importStarts;
    int downloads;
    private final KeystoreInfo keystore;

    AsyncAdapter(Path keystoreFile, Path propertiesFile) {
      this.keystore =
          new KeystoreInfo(
              true,
              Optional.of(keystoreFile),
              Optional.of(propertiesFile),
              Optional.of(FP),
              Optional.empty());
    }

    @Override
    public ServerIdentity identity() {
      return FakeJrsAdapter.IDENTITY;
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
      exportStarts++;
      return new Handles.ExportHandle("exp-" + exportStarts);
    }

    @Override
    public Handles.ExportStatus pollExport(Handles.ExportHandle handle) {
      return new Handles.ExportStatus(
          Handles.Phase.READY, Optional.empty(), Optional.of("export.zip"), Optional.empty());
    }

    @Override
    public Path downloadExport(Handles.ExportHandle handle, Path target) {
      downloads++;
      try (OutputStream out = Files.newOutputStream(target)) {
        out.write(BYTES);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return target;
    }

    @Override
    public Handles.ImportHandle startImport(ImportRequest request, Path archive) {
      importStarts++;
      return new Handles.ImportHandle("imp-" + importStarts);
    }

    @Override
    public Handles.ImportStatus pollImport(Handles.ImportHandle handle) {
      return new Handles.ImportStatus(Handles.Phase.READY, Optional.empty(), Optional.empty());
    }

    @Override
    public void cancelImport(Handles.ImportHandle handle) {}

    @Override
    public KeystoreInfo keystore() {
      return keystore;
    }

    @Override
    public Set<Capability> capabilities() {
      return EnumSet.of(Capability.EXPORT_ASYNC, Capability.IMPORT_ASYNC);
    }

    @Override
    public HealthReport health() {
      return new HealthReport(true, Duration.ZERO, List.of());
    }

    @Override
    public List<String> listFolder(String folderUri) {
      return List.of("/public");
    }

    @Override
    public boolean resourceExists(String uri) {
      return true;
    }

    @Override
    public Path runReportToPdf(String reportUri, Path target) {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public boolean schedulerReachable() {
      return true;
    }

    @Override
    public void createFolder(String folderUri, String label) {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public void uploadJrxmlReport(String folderUri, String label, Path jrxml) {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public void deleteResource(String uri) {
      throw new UnsupportedOperationException("not used");
    }
  }
}
