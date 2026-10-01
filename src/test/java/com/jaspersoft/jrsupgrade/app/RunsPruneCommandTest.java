package com.jaspersoft.jrsupgrade.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.RunLock;
import com.jaspersoft.jrsupgrade.core.engine.TerminalState;
import com.jaspersoft.jrsupgrade.core.json.Json;
import com.jaspersoft.jrsupgrade.core.platform.FileOps;
import com.jaspersoft.jrsupgrade.core.platform.Platforms;
import com.jaspersoft.jrsupgrade.core.snapshot.Snapshot;
import com.jaspersoft.jrsupgrade.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsupgrade.core.state.SnapshotRecord;
import com.jaspersoft.jrsupgrade.core.state.StateStore;
import com.jaspersoft.jrsupgrade.core.state.StoredPlan;
import com.jaspersoft.jrsupgrade.ops.PlanJson;
import com.jaspersoft.jrsupgrade.ops.PlanRegistry;
import com.jaspersoft.jrsupgrade.ops.exim.ExportImportOperations;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunsPruneCommandTest {

  @TempDir Path tmp;

  private final FileOps files = Platforms.detect().files();
  private FakeExportImportOperations fake;
  private Path home;
  private JrsUpgradeHome jrsupgradeHome;
  private Path bundle;

  @BeforeEach
  void setUp() throws Exception {
    fake = new FakeExportImportOperations();
    EximOps.factory = services -> fake;
    home = Files.createDirectories(tmp.resolve("home"));
    jrsupgradeHome = new JrsUpgradeHome(home);
    bundle = tmp.resolve("export.zip");
  }

  @AfterEach
  void tearDown() {
    EximOps.factory = EximOps.DEFAULT_FACTORY;
  }

  private StateStore open() {
    return StateStore.open(jrsupgradeHome, Clock.systemUTC());
  }

  /** A real snapshot of one file created {@code age} ago, with its {@code snapshots} row. */
  private Snapshot seed(String runId, Duration age) throws Exception {
    Path src = Files.createDirectories(tmp.resolve("src"));
    Path file = src.resolve(runId + ".txt");
    Files.writeString(file, "content " + runId, StandardCharsets.UTF_8);
    Clock then = Clock.fixed(Instant.now().minus(age), ZoneOffset.UTC);
    Snapshot s =
        new SnapshotStore(jrsupgradeHome, files, then)
            .create(runId, "snapshot", List.of(file), src);
    try (StateStore store = open()) {
      store.recordSnapshot(
          new SnapshotRecord(
              runId + "/snapshot",
              runId,
              "snapshot",
              s.dir(),
              files.sha256(s.manifestFile()),
              Optional.empty()));
    }
    return s;
  }

  /**
   * A pending export run whose plan the fake rebuilds; {@code runs recover --resume} finishes it.
   */
  private void seedPendingExport() {
    ExportImportOperations.ExportOptions options =
        new ExportImportOperations.ExportOptions(
            Set.of("/public"),
            false,
            false,
            false,
            false,
            false,
            false,
            bundle,
            Optional.empty(),
            false,
            Optional.empty(),
            Optional.empty(),
            false,
            false);
    Instant t0 = Instant.now().minus(Duration.ofMinutes(5));
    try (StateStore store = open()) {
      Plan plan = fake.planExport(options);
      store.savePlan(
          new StoredPlan(
              "plan-pending",
              PlanRegistry.EXPORT,
              PlanRegistry.exportArgs(options),
              PlanJson.toJson(plan),
              plan.fingerprint().value(),
              t0,
              t0.plus(Duration.ofMinutes(30)),
              Optional.of("r-pending")));
      store.recordRunStart("r-pending", "export", Optional.of("plan-pending"), t0);
      store.appendTransition(
          "r-pending", "export.start", "export", Optional.empty(), "PENDING", Optional.empty());
      store.appendTransition(
          "r-pending",
          "export.start",
          "export",
          Optional.of("PENDING"),
          "RUNNING",
          Optional.empty());
    }
  }

  private InitCommandTest.Run prune(String... extra) {
    List<String> args = new ArrayList<>(List.of("runs", "prune"));
    args.addAll(List.of(extra));
    args.addAll(List.of("--home", home.toString(), "--no-color", "--ascii"));
    return InitCommandTest.run(args.toArray(String[]::new));
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  @Test
  void should_remove_oldest_snapshot_and_its_row_when_cap_is_one_and_json_requested()
      throws Exception {
    Snapshot old = seed("r-old", Duration.ofDays(2));
    Snapshot fresh = seed("r-new", Duration.ofDays(1));

    InitCommandTest.Run run = prune("--json", "--set", "backups.maxSnapshots=1");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    JsonNode root = Json.mapper().readTree(run.out());
    assertThat(fieldNames(root)).containsExactly("dryRun", "removed", "kept", "protected");
    assertThat(root.get("dryRun").asBoolean()).isFalse();
    assertThat(root.get("removed")).hasSize(1);
    JsonNode removed = root.get("removed").get(0);
    assertThat(fieldNames(removed)).containsExactly("id", "runId", "stepId", "path");
    assertThat(removed.get("id").asText()).isEqualTo("r-old/snapshot");
    assertThat(removed.get("runId").asText()).isEqualTo("r-old");
    assertThat(removed.get("stepId").asText()).isEqualTo("snapshot");
    assertThat(removed.get("path").asText()).isEqualTo(old.dir().toString());
    assertThat(root.get("kept").asInt()).isEqualTo(1);
    assertThat(root.get("protected").asInt()).isZero();
    assertThat(old.dir()).doesNotExist();
    assertThat(fresh.manifestFile()).exists();
    try (StateStore store = open()) {
      assertThat(store.snapshot("r-old/snapshot")).isEmpty();
      assertThat(store.snapshot("r-new/snapshot")).isPresent();
      assertThat(store.auditRows(5)).anyMatch(a -> a.action().equals("runs.prune"));
      assertThat(store.runs(10)).as("pruning is not a journaled run").isEmpty();
    }
  }

  @Test
  void should_list_candidates_and_change_nothing_when_dry_run() throws Exception {
    Snapshot old = seed("r-old", Duration.ofDays(2));
    seed("r-new", Duration.ofDays(1));
    seedPendingExport();

    InitCommandTest.Run run = prune("--dry-run", "--json", "--set", "backups.maxSnapshots=1");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    JsonNode root = Json.mapper().readTree(run.out());
    assertThat(root.get("dryRun").asBoolean()).isTrue();
    assertThat(root.get("removed")).hasSize(1);
    assertThat(root.get("removed").get(0).get("runId").asText()).isEqualTo("r-old");
    assertThat(root.get("kept").asInt()).isEqualTo(1);
    assertThat(old.manifestFile()).exists();
    try (StateStore store = open()) {
      assertThat(store.snapshots()).hasSize(2);
      assertThat(store.auditRows(5)).noneMatch(a -> a.action().equals("runs.prune"));
    }
  }

  @Test
  void should_print_table_and_summary_when_text_output() throws Exception {
    Snapshot old = seed("r-old", Duration.ofDays(2));
    seed("r-new", Duration.ofDays(1));

    InitCommandTest.Run dry = prune("--dry-run", "--set", "backups.maxSnapshots=1");
    InitCommandTest.Run real = prune("--set", "backups.maxSnapshots=1");
    InitCommandTest.Run again = prune("--set", "backups.maxSnapshots=1");

    assertThat(dry.code()).isZero();
    assertThat(dry.out())
        .contains("SNAPSHOT")
        .contains("r-old/snapshot")
        .contains(old.dir().toString())
        .contains("would remove 1 snapshot(s); keeping 1 (0 protected)")
        .contains("nothing has changed");
    assertThat(real.code()).isZero();
    assertThat(real.out()).contains("removed 1 snapshot(s); kept 1 (0 protected)");
    assertThat(again.code()).isZero();
    assertThat(again.out()).contains("nothing to prune; kept 1 (0 protected)");
  }

  @Test
  void should_exit_9_when_run_lock_is_held() throws Exception {
    seed("r-old", Duration.ofDays(2));
    seed("r-new", Duration.ofDays(1));

    InitCommandTest.Run run;
    try (RunLock held = new RunLock(jrsupgradeHome, "r-holder", Instant.now())) {
      run = prune("--set", "backups.maxSnapshots=1");
    }

    assertThat(run.code()).isEqualTo(ExitCodes.LOCK_HELD);
    assertThat(run.err()).contains("r-holder").contains("pid");
    try (StateStore store = open()) {
      assertThat(store.snapshots()).hasSize(2);
    }
  }

  @Test
  void should_prune_automatically_when_a_mutating_run_succeeds() throws Exception {
    Snapshot old = seed("r-old", Duration.ofDays(2));
    Snapshot fresh = seed("r-new", Duration.ofDays(1));
    seedPendingExport();

    InitCommandTest.Run run =
        InitCommandTest.run(
            "runs",
            "recover",
            "r-pending",
            "--resume",
            "--yes",
            "--set",
            "backups.maxSnapshots=1",
            "--home",
            home.toString(),
            "--no-color",
            "--ascii");

    assertThat(run.code()).as(run.out() + run.err()).isZero();
    assertThat(old.dir()).doesNotExist();
    assertThat(fresh.manifestFile()).exists();
    try (StateStore store = open()) {
      assertThat(store.runs(10).get(0).terminalState()).contains(TerminalState.SUCCEEDED);
      assertThat(store.snapshot("r-old/snapshot")).isEmpty();
      assertThat(store.auditRows(10)).anyMatch(a -> a.action().equals("runs.prune"));
    }
  }

  @Test
  void should_not_prune_when_the_run_fails() throws Exception {
    fake.failStep = Optional.of("export.download");
    Snapshot old = seed("r-old", Duration.ofDays(2));
    seed("r-new", Duration.ofDays(1));
    seedPendingExport();

    InitCommandTest.Run run =
        InitCommandTest.run(
            "runs",
            "recover",
            "r-pending",
            "--resume",
            "--yes",
            "--set",
            "backups.maxSnapshots=1",
            "--home",
            home.toString(),
            "--no-color",
            "--ascii");

    assertThat(run.code()).isEqualTo(ExitCodes.FAILED_ROLLED_BACK);
    assertThat(old.manifestFile()).exists();
    try (StateStore store = open()) {
      assertThat(store.snapshots()).hasSize(2);
      assertThat(store.auditRows(10)).noneMatch(a -> a.action().equals("runs.prune"));
    }
  }
}
