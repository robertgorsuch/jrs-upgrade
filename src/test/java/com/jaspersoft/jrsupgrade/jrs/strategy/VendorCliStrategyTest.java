package com.jaspersoft.jrsupgrade.jrs.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.RunOutcome;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.platform.ProcessRunner;
import com.jaspersoft.jrsupgrade.jrs.FakeJrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsupgrade.jrs.api.ExportRequest;
import com.jaspersoft.jrsupgrade.jrs.api.ImportRequest;
import com.jaspersoft.jrsupgrade.jrs.vendor.BuildomaticLocator;
import com.jaspersoft.jrsupgrade.jrs.vendor.VendorFlags;
import com.jaspersoft.jrsupgrade.jrs.vendor.VendorTools;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VendorCliStrategyTest {

  @TempDir Path tmp;
  private StrategyFixture fx;
  private Path installDir;
  private Path javaHome;
  private Path output;
  private VendorCliStrategy strategy;

  @BeforeEach
  void setUp() throws IOException {
    fx = new StrategyFixture(tmp);
    installDir = tmp.resolve("jrs");
    Path buildomatic = installDir.resolve("buildomatic");
    Files.createDirectories(buildomatic);
    Files.writeString(buildomatic.resolve("js-export.sh"), "#!/bin/sh\n");
    Files.writeString(buildomatic.resolve("js-import.sh"), "#!/bin/sh\n");
    javaHome = tmp.resolve("jdk");
    Files.createDirectories(javaHome);
    output = tmp.resolve("out").resolve("full.zip");
    strategy =
        new VendorCliStrategy(
            new BuildomaticLocator(fx.platform),
            new VendorTools(fx.processes, fx.platform.files(), fx.redactor),
            fx.polling);
  }

  @AfterEach
  void tearDown() {
    fx.close();
  }

  private ExportRequest exportRequest() {
    return new ExportRequest(
        ExportRequest.Scope.EVERYTHING, Set.of(), true, false, false, false, true, true, output);
  }

  private ImportRequest importRequest(Optional<Path> sourceKeystore) {
    return new ImportRequest(
        tmp.resolve("in.zip"),
        true,
        false,
        false,
        false,
        false,
        false,
        false,
        sourceKeystore,
        Optional.empty());
  }

  private static List<String> ids(List<Step> steps) {
    return steps.stream().map(Step::id).toList();
  }

  @Test
  void should_order_export_steps_locate_stop_run_start_wait_sidecar() {
    assertThat(ids(strategy.exportSteps(exportRequest())))
        .containsExactly(
            "export.locate-vendor-tools",
            "export.stop-service",
            "export.js-export",
            "export.start-service",
            "export.wait-for-server",
            "export.sidecar");
    assertThat(strategy.requiresServiceStop()).isTrue();
    assertThat(strategy.kind()).isEqualTo(ExportImportStrategy.Kind.VENDOR_CLI);
  }

  /**
   * Issue #67: js-export reads the repository database and runs against a live server, so the
   * service is stopped for an export only when the operator asks for a quiet export.
   */
  @Test
  void should_leave_the_service_running_when_the_export_does_not_ask_for_a_stop() {
    ExportRequest live =
        new ExportRequest(
            ExportRequest.Scope.EVERYTHING,
            Set.of(),
            true,
            false,
            false,
            false,
            true,
            true,
            output,
            false);

    assertThat(ids(strategy.exportSteps(live)))
        .containsExactly("export.locate-vendor-tools", "export.js-export", "export.sidecar");
  }

  @Test
  void should_order_import_steps_with_keystore_step_when_source_keystore_present() {
    assertThat(ids(strategy.importSteps(importRequest(Optional.of(tmp.resolve("s.jrsks"))))))
        .containsExactly(
            "import.check-keystore",
            "import.locate-vendor-tools",
            "import.stop-service",
            "import.source-keystore",
            "import.js-import",
            "import.start-service",
            "import.wait-for-server");
    assertThat(ids(strategy.importSteps(importRequest(Optional.empty()))))
        .doesNotContain("import.source-keystore");
  }

  @Test
  void should_stop_run_js_export_and_start_when_export_runs_through_runner() throws IOException {
    fx.processes.answer(
        (request, onLine) -> {
          int i = request.command().indexOf("--output-zip");
          try {
            Files.writeString(Path.of(request.command().get(i + 1)), "PK-archive-bytes");
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
          onLine.accept(
              new ProcessRunner.OutputLine(
                  ProcessRunner.OutputLine.Stream.STDOUT, "Processing started"));
          onLine.accept(
              new ProcessRunner.OutputLine(ProcessRunner.OutputLine.Stream.STDOUT, "Done"));
          return new ProcessRunner.Result(0, false, Duration.ofSeconds(2));
        });
    Config config = StrategyFixture.vendorConfig(installDir, Optional.of(javaHome));
    Context ctx = fx.context(config, new FakeJrsAdapter());

    RunOutcome outcome = fx.run(strategy.exportSteps(exportRequest()), ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
    assertThat(fx.service.calls()).containsExactly("stop", "start");
    assertThat(Files.readString(output)).isEqualTo("PK-archive-bytes");
    assertThat(Files.exists(RunFiles.partOf(output))).isFalse();
    assertThat(Sidecar.read(Sidecar.pathFor(output)))
        .isPresent()
        .get()
        .satisfies(s -> assertThat(s.strategy()).isEqualTo(ExportImportStrategy.Kind.VENDOR_CLI));
    ProcessRunner.Request req = lastVendorRequest();
    assertThat(req.command().get(0))
        .isEqualTo(installDir.resolve("buildomatic").resolve("js-export.sh").toString());
    assertThat(req.command())
        .contains("--everything", "--users", "--roles", "--include-server-settings");
    assertThat(req.environment()).containsEntry("JAVA_HOME", javaHome.toString());
    assertThat(req.workingDir()).contains(installDir.resolve("buildomatic"));
    assertThat(fx.sink.logMessages()).contains("Done");
  }

  @Test
  void should_restart_service_and_remove_output_when_js_export_fails() {
    fx.processes.exit(2, "BUILD FAILED");
    Config config = StrategyFixture.vendorConfig(installDir, Optional.of(javaHome));
    Context ctx = fx.context(config, new FakeJrsAdapter());

    RunOutcome outcome = fx.run(strategy.exportSteps(exportRequest()), ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(((RunOutcome.RolledBack) outcome).cause()).contains("BUILD FAILED");
    assertThat(fx.service.calls()).containsExactly("stop", "start");
    assertThat(Files.exists(output)).isFalse();
  }

  /** Issue #43: the vendor copy wrote its stop marker only after a successful stop. */
  @Test
  void should_restart_service_during_rollback_when_export_stop_fails_half_way() {
    fx.service.failStopHalfway();
    Config config = StrategyFixture.vendorConfig(installDir, Optional.of(javaHome));
    Context ctx = fx.context(config, new FakeJrsAdapter());

    RunOutcome outcome = fx.run(strategy.exportSteps(exportRequest()), ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(fx.service.calls()).containsExactly("stop", "start");
    assertThat(fx.service.state())
        .isEqualTo(com.jaspersoft.jrsupgrade.core.platform.ServiceController.State.RUNNING);
    // #113: the start step lists services to find a bundled database; no vendor tool ran
    assertThat(vendorRequests()).isEmpty();
  }

  @Test
  void should_fail_precheck_without_mutation_when_java_home_missing() {
    Config config = StrategyFixture.vendorConfig(installDir, Optional.empty());
    Context ctx = fx.context(config, new FakeJrsAdapter());

    RunOutcome outcome = fx.run(strategy.exportSteps(exportRequest()), ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.PrecheckFailed.class);
    assertThat(((RunOutcome.PrecheckFailed) outcome).message()).contains("vendor.javaHome");
    assertThat(fx.service.calls()).isEmpty();
    assertThat(fx.processes.requests()).isEmpty();
  }

  @Test
  void should_run_js_import_between_stop_and_start_when_import_runs_through_runner()
      throws IOException {
    Files.writeString(tmp.resolve("in.zip"), "PK");
    fx.processes.exit(0, "VALIDATION COMPLETED", "Processing started", "Done");
    Config config = StrategyFixture.vendorConfig(installDir, Optional.of(javaHome));
    Context ctx = fx.context(config, new FakeJrsAdapter());

    RunOutcome outcome = fx.run(strategy.importSteps(importRequest(Optional.empty())), ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.Succeeded.class);
    assertThat(fx.service.calls()).containsExactly("stop", "start");
    assertThat(lastVendorRequest().command())
        .containsSequence("--input-zip", tmp.resolve("in.zip").toString(), "--update");
    assertThat(fx.journal())
        .containsSubsequence(
            "import.stop-service:SUCCEEDED",
            "import.js-import:SUCCEEDED",
            "import.start-service:SUCCEEDED",
            "import.wait-for-server:SUCCEEDED");
  }

  /**
   * Review finding 2.6: the keystore step ran {@code js-import} a second time with only {@code
   * --keystore --storepass} and no archive, an invocation the 10.0.0 importer has no use for (its
   * option bundle documents {@code keystore} as "import the key from a java keystore", an option of
   * an archive import). The options ride on the one archive import; the step keeps the backup.
   */
  @Test
  void should_run_js_import_once_with_the_keystore_options_when_a_source_keystore_is_given()
      throws IOException {
    Files.writeString(tmp.resolve("in.zip"), "PK");
    Path keystore = Files.writeString(tmp.resolve("source.jrsks"), "keystore-bytes");
    fx.processes.exit(0, "VALIDATION COMPLETED", "Processing started", "Done");
    Config config = StrategyFixture.vendorConfig(installDir, Optional.of(javaHome));
    Context ctx = fx.context(config, new FakeJrsAdapter());

    RunOutcome outcome = fx.run(strategy.importSteps(importRequest(Optional.of(keystore))), ctx);

    assertThat(outcome)
        .as(String.join("\n", fx.journal()))
        .isInstanceOf(RunOutcome.Succeeded.class);
    List<ProcessRunner.Request> imports =
        fx.processes.requests().stream()
            .filter(r -> r.command().get(0).endsWith("js-import.sh"))
            .toList();
    assertThat(imports).hasSize(1);
    assertThat(imports.get(0).command())
        .containsSequence("--input-zip", tmp.resolve("in.zip").toString())
        .containsSequence(VendorFlags.KEYSTORE, keystore.toString());
  }

  /**
   * Review finding 2.8: {@code js-import.sh} expands {@code $*} unquoted, so a path with a space
   * reaches the importer as two arguments; the vendor steps refuse such paths before stopping the
   * service.
   */
  @Test
  void should_refuse_an_archive_path_with_a_space_when_the_wrapper_is_a_shell_script()
      throws IOException {
    Path archive = tmp.resolve("my exports").resolve("in.zip");
    Files.createDirectories(archive.getParent());
    Files.writeString(archive, "PK");
    ImportRequest request =
        new ImportRequest(
            archive,
            true,
            false,
            false,
            false,
            false,
            false,
            false,
            Optional.empty(),
            Optional.empty());
    Config config = StrategyFixture.vendorConfig(installDir, Optional.of(javaHome));
    Context ctx = fx.context(config, new FakeJrsAdapter());
    Step run =
        strategy.importSteps(request).stream()
            .filter(s -> s.id().equals("import.js-import"))
            .findFirst()
            .orElseThrow();

    CheckResult result = run.precheck(ctx);

    assertThat(result).isInstanceOf(CheckResult.Fail.class);
    assertThat(((CheckResult.Fail) result).message()).contains("space");
  }

  @Test
  void should_refuse_an_output_path_with_a_space_when_the_wrapper_is_a_shell_script() {
    Path spaced = tmp.resolve("my exports").resolve("full.zip");
    ExportRequest request =
        new ExportRequest(
            ExportRequest.Scope.EVERYTHING,
            Set.of(),
            true,
            false,
            false,
            false,
            true,
            true,
            spaced);
    Config config = StrategyFixture.vendorConfig(installDir, Optional.of(javaHome));
    Context ctx = fx.context(config, new FakeJrsAdapter());
    Step run =
        strategy.exportSteps(request).stream()
            .filter(s -> s.id().equals("export.js-export"))
            .findFirst()
            .orElseThrow();

    CheckResult result = run.precheck(ctx);

    assertThat(result).isInstanceOf(CheckResult.Fail.class);
    assertThat(((CheckResult.Fail) result).message()).contains("space");
  }

  @Test
  void should_fail_the_import_when_js_import_exits_zero_after_a_failed_build() throws IOException {
    Files.writeString(tmp.resolve("in.zip"), "PK");
    // js-import.sh guards the import with `if [ $? -eq 0 ]` and has no else branch, so a failed
    // validate-database/validate-keystore imports nothing and still exits 0.
    fx.processes.exit(0, "BUILD FAILED", "keystore validation failed");
    Config config = StrategyFixture.vendorConfig(installDir, Optional.of(javaHome));
    Context ctx = fx.context(config, new FakeJrsAdapter());

    RunOutcome outcome = fx.run(strategy.importSteps(importRequest(Optional.empty())), ctx);

    assertThat(outcome).isNotInstanceOf(RunOutcome.Succeeded.class);
    assertThat(fx.journal()).contains("import.js-import:FAILED");
  }

  /**
   * Issue #40: the validation succeeded, the import command threw, and the wrapper exited 0. The
   * same step re-imports the pre-import snapshot in a rollback, which then reported the run as
   * rolled back (exit 3) instead of rollback incomplete (exit 4).
   */
  @Test
  void should_fail_the_import_when_the_import_command_throws_after_validation_and_exits_zero()
      throws IOException {
    Files.writeString(tmp.resolve("in.zip"), "PK");
    fx.processes.exit(
        0,
        "BUILD SUCCESSFUL",
        "Processing started",
        "2026-09-14T17:33:15,565 ERROR BaseExportImportCommand:45 -"
            + " java.lang.NullPointerException: entry");
    Config config = StrategyFixture.vendorConfig(installDir, Optional.of(javaHome));
    Context ctx = fx.context(config, new FakeJrsAdapter());

    RunOutcome outcome = fx.run(strategy.importSteps(importRequest(Optional.empty())), ctx);

    assertThat(outcome).isNotInstanceOf(RunOutcome.Succeeded.class);
    assertThat(fx.journal()).contains("import.js-import:FAILED");
  }

  @Test
  void should_remove_the_partial_archive_when_the_export_command_starts_and_never_prints_done() {
    fx.processes.answer(
        (request, onLine) -> {
          int i = request.command().indexOf("--output-zip");
          try {
            Files.writeString(Path.of(request.command().get(i + 1)), "PK-half-an-archive");
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
          onLine.accept(
              new ProcessRunner.OutputLine(
                  ProcessRunner.OutputLine.Stream.STDOUT, "Processing started"));
          return new ProcessRunner.Result(0, false, Duration.ofSeconds(1));
        });
    Config config = StrategyFixture.vendorConfig(installDir, Optional.of(javaHome));
    Context ctx = fx.context(config, new FakeJrsAdapter());

    RunOutcome outcome = fx.run(strategy.exportSteps(exportRequest()), ctx);

    assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
    assertThat(((RunOutcome.RolledBack) outcome).cause()).contains("never printed Done");
    assertThat(Files.exists(output)).isFalse();
    assertThat(Files.exists(RunFiles.partOf(output))).isFalse();
  }

  @Test
  void should_fail_the_import_when_js_import_exits_zero_without_a_build_banner()
      throws IOException {
    Files.writeString(tmp.resolve("in.zip"), "PK");
    fx.processes.exit(0);
    Config config = StrategyFixture.vendorConfig(installDir, Optional.of(javaHome));
    Context ctx = fx.context(config, new FakeJrsAdapter());

    RunOutcome outcome = fx.run(strategy.importSteps(importRequest(Optional.empty())), ctx);

    assertThat(outcome).isNotInstanceOf(RunOutcome.Succeeded.class);
    assertThat(fx.journal()).contains("import.js-import:FAILED");
  }

  /** The process requests other than the service-manager listings the start step makes (#113). */
  private List<ProcessRunner.Request> vendorRequests() {
    return fx.processes.requests().stream()
        .filter(r -> !List.of("systemctl", "sc.exe").contains(r.command().get(0)))
        .toList();
  }

  private ProcessRunner.Request lastVendorRequest() {
    List<ProcessRunner.Request> requests = vendorRequests();
    return requests.get(requests.size() - 1);
  }
}
