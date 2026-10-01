package com.jaspersoft.jrsupgrade.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.secrets.SecretRef;
import com.jaspersoft.jrsupgrade.core.state.StateStore;
import com.jaspersoft.jrsupgrade.jrs.api.BrokenDependencies;
import com.jaspersoft.jrsupgrade.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsupgrade.ops.PlanRegistry;
import com.jaspersoft.jrsupgrade.ops.exim.DefaultExportImportOperations;
import com.jaspersoft.jrsupgrade.ops.exim.ExportImportOperations;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportCommandTest {

  @TempDir Path tmp;

  private FakeExportImportOperations fake;
  private Path home;
  private Path archive;

  @BeforeEach
  void setUp() throws Exception {
    fake = new FakeExportImportOperations();
    EximOps.factory = services -> fake;
    home = Files.createDirectories(tmp.resolve("home"));
    Files.writeString(
        home.resolve("config.yaml"),
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
          auth:
            username: jasperadmin
            passwordRef: env:JRS_PASSWORD
        """,
        StandardCharsets.UTF_8);
    archive = tmp.resolve("public.zip");
    Files.writeString(archive, "zip");
  }

  @AfterEach
  void tearDown() {
    EximOps.factory = EximOps.DEFAULT_FACTORY;
  }

  private InitCommandTest.Run importOf(String... extra) {
    List<String> args = new ArrayList<>(List.of("import", archive.toString()));
    args.addAll(List.of(extra));
    args.addAll(List.of("--home", home.toString(), "--no-color", "--ascii"));
    return InitCommandTest.run(args.toArray(String[]::new));
  }

  @Test
  void should_print_snapshot_step_and_best_effort_warning_when_plan_flag_given() {
    InitCommandTest.Run run = importOf("--update", "--plan");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(run.out())
        .contains("Plan  import  public.zip")
        .contains("! " + DefaultExportImportOperations.ROLLBACK_WARNING)
        .contains("  precheck")
        .contains("  backup")
        .contains("  import")
        .contains("02  Pre-import snapshot")
        .contains("04  Enable snapshot rollback")
        .contains("backups")
        .contains("pre-import-x.zip")
        .contains("nothing has changed");
    assertThat(fake.executed).isEmpty();
    ExportImportOperations.ImportOptions options = fake.lastImport.orElseThrow();
    assertThat(options.archive()).isEqualTo(archive);
    assertThat(options.update()).isTrue();
    assertThat(options.sourceKeystore()).isEmpty();
    assertThat(options.brokenDependencies()).isEqualTo(BrokenDependencies.FAIL);
    assertThat(options.strategy()).isEmpty();
  }

  /** Field test 2, I1: the key the archive was exported with. */
  @Test
  void should_pass_the_key_alias_to_the_operation_when_given() {
    InitCommandTest.Run run = importOf("--key-alias", "deprecatedImportExportEncSecret", "--plan");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(fake.lastImport.orElseThrow().keyAlias())
        .contains("deprecatedImportExportEncSecret");
  }

  /** Issue #107: the override for an archive from a newer version than this server. */
  @Test
  void should_pass_force_version_to_the_operation_when_given() {
    assertThat(importOf("--force-version", "--plan").code()).isZero();
    assertThat(fake.lastImport.orElseThrow().forceVersion()).isTrue();

    assertThat(importOf("--plan").code()).isZero();
    assertThat(fake.lastImport.orElseThrow().forceVersion()).isFalse();
  }

  /**
   * ADR-0040: {@code --no-snapshot} reaches the operation; every command-line plan takes the
   * snapshot with the server running.
   */
  @Test
  void should_pass_no_snapshot_to_the_operation_when_given() {
    assertThat(importOf("--no-snapshot", "--plan").code()).isZero();
    assertThat(fake.lastImport.orElseThrow().noSnapshot()).isTrue();
    assertThat(fake.lastImport.orElseThrow().snapshotStopsService()).isFalse();

    assertThat(importOf("--plan").code()).isZero();
    assertThat(fake.lastImport.orElseThrow().noSnapshot()).isFalse();
  }

  /** Issue #115: themes are skipped across a major version unless {@code --themes} says not. */
  @Test
  void should_pass_themes_to_the_operation_and_refuse_it_with_skip_themes() {
    assertThat(importOf("--themes", "--plan").code()).isZero();
    assertThat(fake.lastImport.orElseThrow().keepThemes()).isTrue();

    assertThat(importOf("--plan").code()).isZero();
    assertThat(fake.lastImport.orElseThrow().keepThemes()).isFalse();

    InitCommandTest.Run both = importOf("--themes", "--skip-themes", "--plan");
    assertThat(both.code()).isEqualTo(ExitCodes.USAGE);
    assertThat(both.err()).contains("--themes and --skip-themes contradict");
  }

  /** Field test 2, I4: the target organisation, merged when the ids differ. */
  @Test
  void should_pass_the_organisation_and_merge_switch_and_refuse_merge_alone() {
    assertThat(importOf("--organization", "org1", "--merge-organization", "--plan").code())
        .isZero();
    ExportImportOperations.ImportOptions options = fake.lastImport.orElseThrow();
    assertThat(options.organization()).contains("org1");
    assertThat(options.mergeOrganization()).isTrue();

    InitCommandTest.Run alone = importOf("--merge-organization", "--plan");
    assertThat(alone.code()).isEqualTo(ExitCodes.USAGE);
    assertThat(alone.err()).contains("--merge-organization needs --organization");
  }

  @Test
  void should_pass_every_flag_to_the_operation_when_all_given() {
    Path keystore = tmp.resolve("source.jrsks");
    InitCommandTest.Run run =
        importOf(
            "--update",
            "--skip-user-update",
            "--access-events",
            "--audit-events",
            "--monitoring",
            "--settings",
            "--skip-themes",
            "--broken-dependencies",
            "skip",
            "--source-keystore",
            keystore.toString(),
            "--source-keystore-password-ref",
            "env:KS_PASS",
            "--strategy",
            "rest",
            "--plan");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    ExportImportOperations.ImportOptions options = fake.lastImport.orElseThrow();
    assertThat(options.skipUserUpdate()).isTrue();
    assertThat(options.accessEvents()).isTrue();
    assertThat(options.auditEvents()).isTrue();
    assertThat(options.monitoring()).isTrue();
    assertThat(options.settings()).isTrue();
    assertThat(options.skipThemes()).isTrue();
    assertThat(options.brokenDependencies()).isEqualTo(BrokenDependencies.SKIP);
    assertThat(options.sourceKeystore()).contains(keystore);
    assertThat(options.sourceKeystorePassword()).contains(new SecretRef.Env("KS_PASS"));
    assertThat(options.strategy()).contains(ExportImportStrategy.Kind.REST);
  }

  @Test
  void should_run_every_step_and_exit_0_when_yes_given() {
    InitCommandTest.Run run = importOf("--yes");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(fake.executed).containsExactlyElementsOf(FakeExportImportOperations.IMPORT_STEPS);
    assertThat(run.out()).contains("OK    02  Pre-import snapshot").contains("succeeded");
  }

  @Test
  void should_compensate_import_phase_and_exit_3_when_import_step_fails() {
    fake.failStep = Optional.of("import.poll");

    InitCommandTest.Run run = importOf("--yes");

    assertThat(run.code()).as(run.out() + run.err()).isEqualTo(ExitCodes.FAILED_ROLLED_BACK);
    assertThat(fake.compensated)
        .containsExactly("import.poll", "import.start", "import.snapshot-rollback");
    assertThat(run.out())
        .contains("FAIL  06  Wait for import task")
        .contains("UNDO  06  Wait for import task")
        .contains("UNDO  04  Enable snapshot rollback")
        .contains("rolled back to phase import")
        .contains("pre-import-x.zip");
  }

  @Test
  void should_emit_parseable_plan_with_warning_when_json_given() throws Exception {
    InitCommandTest.Run run = importOf("--plan", "--json");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    JsonNode plan = JsonDocs.documents(run.out()).get(0);
    assertThat(plan.get("operation").asText()).isEqualTo("import");
    assertThat(plan.get("summary").get("warnings").get(0).asText())
        .isEqualTo(DefaultExportImportOperations.ROLLBACK_WARNING);
    assertThat(plan.get("summary").get("backupLocations")).hasSize(1);
    assertThat(plan.get("steps").get(1).get("phase").asText()).isEqualTo("backup");
  }

  @Test
  void should_exit_8_when_a_run_is_pending() {
    try (StateStore store = StateStore.open(new JrsUpgradeHome(home), Clock.systemUTC())) {
      store.recordRunStart("r-pending", "export", Optional.empty(), Instant.now());
    }

    InitCommandTest.Run run = importOf("--yes");

    assertThat(run.code()).isEqualTo(ExitCodes.RECOVERY_REQUIRED);
    assertThat(run.err()).contains("jrs-upgrade runs recover r-pending --rollback");
    assertThat(fake.executed).isEmpty();
  }

  @Test
  void should_exit_1_when_password_reference_is_unparseable() {
    InitCommandTest.Run run =
        importOf("--source-keystore-password-ref", "plaintext-password", "--plan");

    assertThat(run.code()).isEqualTo(ExitCodes.USAGE);
    assertThat(run.err()).contains("error:");
    assertThat(fake.lastImport).isEmpty();
  }

  @Test
  void should_exit_2_when_planning_fails() {
    fake.planFailure = Optional.of(new IllegalArgumentException("archive x.zip does not exist"));

    InitCommandTest.Run run = importOf("--yes");

    assertThat(run.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(run.err()).contains("does not exist");
  }

  @Test
  void should_rebuild_import_plan_from_stored_arguments_when_registry_asked() {
    Path keystore = tmp.resolve("k.jrsks");
    ExportImportOperations.ImportOptions options =
        new ExportImportOperations.ImportOptions(
            archive,
            true,
            true,
            false,
            true,
            false,
            true,
            true,
            Optional.of(keystore),
            Optional.of(new SecretRef.Env("KS")),
            Optional.of(ExportImportStrategy.Kind.VENDOR_CLI));
    PlanRegistry registry =
        new PlanRegistry(
            () -> fake,
            () -> {
              throw new UnsupportedOperationException("upgrade not used");
            });

    Plan plan = registry.rebuild(PlanRegistry.IMPORT, PlanRegistry.importArgs(options));

    assertThat(plan.summary().operation()).isEqualTo("import");
    ExportImportOperations.ImportOptions rebuilt = fake.lastImport.orElseThrow();
    assertThat(rebuilt.archive()).isEqualTo(archive.toAbsolutePath().normalize());
    assertThat(rebuilt.update()).isTrue();
    assertThat(rebuilt.skipUserUpdate()).isTrue();
    assertThat(rebuilt.accessEvents()).isFalse();
    assertThat(rebuilt.auditEvents()).isTrue();
    assertThat(rebuilt.settings()).isTrue();
    assertThat(rebuilt.skipThemes()).isTrue();
    assertThat(rebuilt.sourceKeystore()).contains(keystore.toAbsolutePath().normalize());
    assertThat(rebuilt.sourceKeystorePassword()).contains(new SecretRef.Env("KS"));
    assertThat(rebuilt.strategy()).contains(ExportImportStrategy.Kind.VENDOR_CLI);
    assertThat(PlanRegistry.importArgs(options)).doesNotContain("\"strategy\":\"VENDOR_CLI\"");
  }
}
