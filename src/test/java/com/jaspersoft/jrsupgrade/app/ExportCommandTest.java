package com.jaspersoft.jrsupgrade.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.TerminalState;
import com.jaspersoft.jrsupgrade.core.state.StateStore;
import com.jaspersoft.jrsupgrade.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsupgrade.ops.PlanRegistry;
import com.jaspersoft.jrsupgrade.ops.exim.ExportImportOperations;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportCommandTest {

  @TempDir Path tmp;

  private FakeExportImportOperations fake;
  private Path home;
  private Path out;

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
    out = tmp.resolve("exports").resolve("x.zip");
  }

  @AfterEach
  void tearDown() {
    EximOps.factory = EximOps.DEFAULT_FACTORY;
  }

  private InitCommandTest.Run export(String... extra) {
    List<String> args = new ArrayList<>(List.of("export", "--out", out.toString()));
    args.addAll(List.of(extra));
    args.addAll(List.of("--home", home.toString(), "--no-color", "--ascii"));
    return InitCommandTest.run(args.toArray(String[]::new));
  }

  @Test
  void should_print_steps_and_strategy_line_and_exit_0_when_plan_flag_given() {
    InitCommandTest.Run run = export("--uri", "/public", "--users-roles", "--plan");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(run.out())
        .contains("Plan  export  /public")
        .contains("strategy")
        .contains("rest (REST: EXPORT_ASYNC probe passed, service stays up)")
        .contains("  export")
        .contains("01  Start export task")
        .contains("04  Write export sidecar")
        .contains("x.zip.jrs-upgrade.json")
        .contains("nothing has changed");
    assertThat(fake.executed).isEmpty();
    ExportImportOperations.ExportOptions options = fake.lastExport.orElseThrow();
    assertThat(options.uris()).containsExactly("/public");
    assertThat(options.usersRoles()).isTrue();
    assertThat(options.fullServer()).isFalse();
    assertThat(options.strategy()).isEmpty();
    assertThat(options.out()).isEqualTo(out);
    try (StateStore store = StateStore.open(new JrsUpgradeHome(home), Clock.systemUTC())) {
      assertThat(store.loadPlan(fake.lastPlanId).orElseThrow().operation()).isEqualTo("export");
      assertThat(store.runs(10)).isEmpty();
    }
  }

  @Test
  void should_pass_every_flag_to_the_operation_when_all_given() {
    InitCommandTest.Run run =
        export(
            "--uri",
            "/a",
            "--uri",
            "/b",
            "--access-events",
            "--audit-events",
            "--monitoring",
            "--settings",
            "--full-server",
            "--strategy",
            "vendor",
            "--plan");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    ExportImportOperations.ExportOptions options = fake.lastExport.orElseThrow();
    assertThat(options.uris()).containsExactlyInAnyOrder("/a", "/b");
    assertThat(options.accessEvents()).isTrue();
    assertThat(options.auditEvents()).isTrue();
    assertThat(options.monitoring()).isTrue();
    assertThat(options.settings()).isTrue();
    assertThat(options.fullServer()).isTrue();
    assertThat(options.strategy()).contains(ExportImportStrategy.Kind.VENDOR_CLI);
  }

  /** Field test 2, E4: a named key, or --portable for the alias every keystore holds. */
  @Test
  void should_pass_the_key_alias_and_treat_portable_as_the_shared_alias() {
    assertThat(export("--key-alias", "k1", "--plan").code()).isZero();
    assertThat(fake.lastExport.orElseThrow().keyAlias()).contains("k1");

    assertThat(export("--portable", "--plan").code()).isZero();
    assertThat(fake.lastExport.orElseThrow().keyAlias())
        .contains("deprecatedImportExportEncSecret");

    InitCommandTest.Run both = export("--portable", "--key-alias", "k1", "--plan");
    assertThat(both.code()).isEqualTo(ExitCodes.USAGE);
    assertThat(both.err()).contains("--legacy-key (--portable) is --key-alias");
  }

  /** Jaspersoft calls deprecatedImportExportEncSecret the Legacy key; --portable stays an alias. */
  @Test
  void should_encrypt_with_the_legacy_key_when_legacy_key_is_given() {
    assertThat(export("--legacy-key", "--plan").code()).isZero();
    assertThat(fake.lastExport.orElseThrow().keyAlias())
        .contains("deprecatedImportExportEncSecret");
  }

  /** Field test 2, E5: one organisation. */
  @Test
  void should_pass_the_organisation_to_the_operation_when_given() {
    assertThat(export("--organization", "org1", "--plan").code()).isZero();
    assertThat(fake.lastExport.orElseThrow().organization()).contains("org1");
  }

  /** Issue #144: dependent and favorite resources are included unless skipped. */
  @Test
  void should_default_skip_dependent_and_favorite_resources_to_false() {
    assertThat(export("--uri", "/public", "--plan").code()).isZero();
    assertThat(fake.lastExport.orElseThrow().skipDependentResources()).isFalse();
    assertThat(fake.lastExport.orElseThrow().skipFavoriteResources()).isFalse();
  }

  @Test
  void should_pass_skip_dependent_and_favorite_resources_to_the_operation_when_given() {
    assertThat(
            export(
                    "--uri",
                    "/public",
                    "--skip-dependent-resources",
                    "--skip-favorite-resources",
                    "--plan")
                .code())
        .isZero();
    assertThat(fake.lastExport.orElseThrow().skipDependentResources()).isTrue();
    assertThat(fake.lastExport.orElseThrow().skipFavoriteResources()).isTrue();
  }

  @Test
  void should_run_every_step_and_exit_0_when_yes_given() {
    InitCommandTest.Run run = export("--uri", "/public", "--yes");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(fake.executed).containsExactlyElementsOf(FakeExportImportOperations.EXPORT_STEPS);
    assertThat(run.out()).contains("OK    01  Start export task").contains("succeeded");
    try (StateStore store = StateStore.open(new JrsUpgradeHome(home), Clock.systemUTC())) {
      assertThat(store.runs(10)).hasSize(1);
      assertThat(store.runs(10).get(0).operation()).isEqualTo("export");
      assertThat(store.runs(10).get(0).terminalState()).contains(TerminalState.SUCCEEDED);
    }
  }

  @Test
  void should_emit_parseable_plan_and_outcome_when_json_given() throws Exception {
    InitCommandTest.Run run = export("--uri", "/public", "--yes", "--json");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    List<JsonNode> docs = JsonDocs.documents(run.out());
    JsonNode plan = docs.get(0);
    assertThat(plan.get("operation").asText()).isEqualTo("export");
    assertThat(plan.get("steps")).hasSize(4);
    assertThat(plan.get("summary").get("strategy").asText()).startsWith("rest (");
    assertThat(plan.get("summary").get("filesTouched")).hasSize(2);
    JsonNode last = docs.get(docs.size() - 1);
    assertThat(last.get("outcome").get("type").asText()).isEqualTo("Succeeded");
  }

  @Test
  void should_exit_8_and_name_recover_command_when_a_run_is_pending() {
    try (StateStore store = StateStore.open(new JrsUpgradeHome(home), Clock.systemUTC())) {
      store.recordRunStart("r-pending", "import", Optional.empty(), Instant.now());
    }

    InitCommandTest.Run run = export("--uri", "/public", "--yes");

    assertThat(run.code()).isEqualTo(ExitCodes.RECOVERY_REQUIRED);
    assertThat(run.err()).contains("jrs-upgrade runs recover r-pending --resume");
    assertThat(fake.executed).isEmpty();
  }

  @Test
  void should_exit_1_before_bootstrap_when_strategy_is_unknown() {
    InitCommandTest.Run run = export("--strategy", "ftp", "--plan");

    assertThat(run.code()).isEqualTo(ExitCodes.USAGE);
    assertThat(run.err()).contains("--strategy").contains("ftp");
    assertThat(fake.lastExport).isEmpty();
  }

  @Test
  void should_exit_1_when_out_is_missing() {
    InitCommandTest.Run run =
        InitCommandTest.run("export", "--uri", "/public", "--home", home.toString());

    assertThat(run.code()).isEqualTo(ExitCodes.USAGE);
    assertThat(run.err()).contains("--out");
  }

  @Test
  void should_exit_2_when_planning_fails() {
    fake.planFailure = Optional.of(new IllegalStateException("server unreachable"));

    InitCommandTest.Run run = export("--uri", "/public", "--yes");

    assertThat(run.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(run.err()).contains("server unreachable");
  }

  @Test
  void should_rebuild_export_plan_from_stored_arguments_when_registry_asked() {
    ExportImportOperations.ExportOptions options =
        new ExportImportOperations.ExportOptions(
            Set.of("/public", "/adhoc"),
            true,
            false,
            true,
            false,
            true,
            false,
            out,
            Optional.of(ExportImportStrategy.Kind.REST));
    PlanRegistry registry =
        new PlanRegistry(
            () -> fake,
            () -> {
              throw new UnsupportedOperationException("upgrade not used");
            });

    Plan plan = registry.rebuild(PlanRegistry.EXPORT, PlanRegistry.exportArgs(options));

    assertThat(plan.summary().operation()).isEqualTo("export");
    ExportImportOperations.ExportOptions rebuilt = fake.lastExport.orElseThrow();
    assertThat(rebuilt.uris()).containsExactlyInAnyOrder("/public", "/adhoc");
    assertThat(rebuilt.usersRoles()).isTrue();
    assertThat(rebuilt.auditEvents()).isTrue();
    assertThat(rebuilt.settings()).isTrue();
    assertThat(rebuilt.strategy()).contains(ExportImportStrategy.Kind.REST);
    assertThat(rebuilt.out()).isEqualTo(out.toAbsolutePath().normalize());
  }
}
