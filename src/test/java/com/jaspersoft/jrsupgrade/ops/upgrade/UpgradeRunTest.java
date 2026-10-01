package com.jaspersoft.jrsupgrade.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.config.ConfigLoader;
import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.RunOptions;
import com.jaspersoft.jrsupgrade.core.engine.RunOutcome;
import com.jaspersoft.jrsupgrade.core.platform.Trees;
import com.jaspersoft.jrsupgrade.core.snapshot.Snapshot;
import com.jaspersoft.jrsupgrade.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsupgrade.core.state.HotfixInstalled;
import com.jaspersoft.jrsupgrade.core.state.HotfixState;
import com.jaspersoft.jrsupgrade.core.state.SnapshotRecord;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.RollbackOptions;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.RollbackPoint;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.UpgradeOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpgradeRunTest {

  @TempDir Path tmp;

  private static UpgradeOptions newdb(UpgradeFixture f) {
    return UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, f.packageDir);
  }

  /**
   * Issue #69: after an upgrade jrs-upgrade works with the new version's buildomatic without the
   * operator editing its configuration, and rolling the upgrade back puts the old configuration
   * back.
   */
  @Test
  void should_point_config_at_the_upgraded_buildomatic_and_restore_it_on_rollback()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      String before = UpgradeFixture.read(f.fake.home.configFile());
      Path upgraded = f.packageDir.resolve("buildomatic").toAbsolutePath().normalize();

      RunOutcome up = f.run(f.ops().planUpgrade(newdb(f)), "r-up-cfg", RunOptions.DEFAULT);

      assertThat(up).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
      Config after = new ConfigLoader().load(f.fake.home, Map.of(), Map.of());
      assertThat(after.server().buildomaticDir()).contains(upgraded);
      assertThat(after.server().installDir()).contains(f.installDir.toAbsolutePath().normalize());
      assertThat(f.logs())
          .anyMatch(m -> m.contains("server.buildomaticDir") && m.contains(upgraded.toString()));

      RunOutcome back =
          f.run(f.ops().planRollback("r-up-cfg", RollbackPoint.B), "r-rb-cfg", RunOptions.DEFAULT);

      assertThat(back).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(UpgradeFixture.read(f.fake.home.configFile())).isEqualTo(before);
    }
  }

  /** Spec §10.2 "Rehearsal": the wrapper gets {@code test}, the package is left as found. */
  @Test
  void should_pass_test_to_the_vendor_wrapper_and_leave_the_package_as_it_was() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path master = f.packageDir.resolve("buildomatic").resolve("default_master.properties");
      Path keystoreInit = f.packageDir.resolve("buildomatic").resolve("keystore.init.properties");

      RunOutcome outcome = f.run(f.ops().planTest(newdb(f)), "r-test", RunOptions.DEFAULT);

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.logs())
          .anyMatch(m -> m.contains("js-ant fake target=pre-upgrade-test-pro"))
          .anyMatch(m -> m.contains("vendor validation passed"));
      assertThat(master).doesNotExist();
      assertThat(keystoreInit).doesNotExist();
      assertThat(f.vendorLogText()).doesNotContain("upgrade-minimal-pro");
      assertThat(UpgradeFixture.read(f.webappDir.resolve("version.txt"))).isEqualTo("8.2.0");
      assertThat(f.fake.platform.controller.events).doesNotContain("stop");
      assertThat(f.fake.home.snapshots().resolve("r-test")).doesNotExist();
    }
  }

  @Test
  void should_fail_the_rehearsal_with_the_vendor_lines_and_leave_the_package_as_it_was()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.failVendorTest();

      RunOutcome outcome = f.run(f.ops().planTest(newdb(f)), "r-test-fail", RunOptions.DEFAULT);

      assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
      assertThat(((RunOutcome.RolledBack) outcome).cause())
          .contains("BUILD FAILED: cannot connect");
      assertThat(f.packageDir.resolve("buildomatic").resolve("default_master.properties"))
          .doesNotExist();
      assertThat(UpgradeFixture.read(f.webappDir.resolve("version.txt"))).isEqualTo("8.2.0");
      assertThat(f.fake.platform.controller.events).doesNotContain("stop");
    }
  }

  /**
   * ADR-0029: after a newdb run failed inside the vendor script, the database it left behind is
   * dropped and the old one rebuilt from the point-B export with the restored buildomatic.
   */
  @Test
  void should_rebuild_the_database_and_reimport_the_export_when_rolling_back_a_newdb_run()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.failVendorScriptAfterCopy();
      RunOutcome failed = f.run(f.ops().planUpgrade(newdb(f)), "r-up", RunOptions.DEFAULT);
      assertThat(failed).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.RolledBack.class);
      Plan rollback = f.ops().planRollback("r-up", new RollbackOptions(RollbackPoint.B, true));
      assertThat(UpgradeFixture.ids(rollback))
          .containsSubsequence(
              "restore-buildomatic", "rebuild-database", "reimport-full-export", "start-service");

      RunOutcome outcome = f.run(rollback, "r-rb", RunOptions.DEFAULT);

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
      SnapshotSet set = SnapshotSet.of(f.fake.home, "r-up", f.os);
      assertThat(f.vendorLogText())
          .contains("init-js-db-pro")
          .contains("js-import --input-zip " + set.fullExport() + " --update")
          .contains("--include-server-settings");
      assertThat(f.vendorLogText().indexOf("init-js-db-pro"))
          .isLessThan(f.vendorLogText().indexOf("js-import"));
      assertThat(f.store().auditRows(20)).anyMatch(a -> a.action().equals("upgrade.rolled-back"));
    }
  }

  /**
   * Issue #106: with {@code --include-events} the target's js-import brings the three event kinds
   * over from the point-B export after the vendor run, with the vendor's own flags (installation
   * guide p.256) and never {@code --update}, and runs once per run.
   */
  @Test
  void should_import_the_events_from_the_point_b_export_with_the_targets_js_import()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(newdb(f).withIncludeEvents(true));

      RunOutcome outcome = f.run(plan, "r-events", RunOptions.DEFAULT);

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
      SnapshotSet set = SnapshotSet.of(f.fake.home, "r-events", f.os);
      String importLine =
          f.vendorLogText().lines().filter(l -> l.contains("js-import")).findFirst().orElse("");
      assertThat(importLine)
          .contains("--input-zip " + set.fullExport())
          .contains("--include-access-events")
          .contains("--include-audit-events")
          .contains("--include-monitoring-events")
          .doesNotContain("--update")
          .doesNotContain("--include-server-settings");
      assertThat(f.vendorLogText().lines().filter(l -> l.contains("js-import")).count())
          .isEqualTo(1);
      assertThat(f.fake.home.runDir("r-events").resolve("import-events.done")).exists();
    }
  }

  /**
   * ADR-0028: the adopted export is what js-upgrade-newdb receives, the key alias and its password
   * reach the vendor import through the staged properties and never the command line, and the
   * rollback plan names the archive the operator supplied.
   */
  @Test
  void should_pass_the_adopted_export_to_the_wrapper_and_stage_the_key_alias() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path export = f.fakeExport("portable.zip");
      Plan plan =
          f.ops()
              .planUpgrade(
                  newdb(f)
                      .withExistingExport(export)
                      .withKeyAlias("deprecatedImportExportEncSecret")
                      .withKeyPassword(
                          com.jaspersoft.jrsupgrade.core.secrets.SecretRef.parse(
                              "env:JRS_PASSWORD")));

      RunOutcome outcome = f.run(plan, "r-adopt", RunOptions.DEFAULT);

      assertThat(outcome).as(outcome.toString()).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.vendorLogText()).contains(export.toString()).doesNotContain("keyalias");
      assertThat(f.stagedMasterProperties())
          .containsEntry(
              "deprecatedImportExportEncSecret.keyalias", "deprecatedImportExportEncSecret")
          .containsEntry("deprecatedImportExportEncSecret.keypass", "s3cret-pass");
      assertThat(f.fake.home.snapshots().resolve("r-adopt").resolve("full-export.external"))
          .exists();
      assertThat(f.fake.home.snapshots().resolve("r-adopt").resolve("full-export.zip"))
          .doesNotExist();
      Plan rollback = f.ops().planRollback("r-adopt", RollbackPoint.B);
      assertThat(rollback.summary().warnings()).anyMatch(w -> w.contains(export.toString()));
    }
  }

  @Test
  void should_back_up_upgrade_and_record_when_vendor_script_succeeds() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.store()
          .recordHotfixInstalled(
              new HotfixInstalled(
                  "JRS-8.2.0-HF-0001",
                  "1",
                  "old fix",
                  "r-old",
                  Optional.empty(),
                  HotfixState.INSTALLED,
                  Instant.EPOCH),
              List.of());
      String oldWebappHash = f.sha(f.webappDir.resolve("scripts").resolve("app.js"));
      Plan plan = f.ops().planUpgrade(newdb(f));

      RunOutcome outcome = f.run(plan, "r-up-1", RunOptions.DEFAULT);

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
      SnapshotSet set = SnapshotSet.of(f.fake.home, "r-up-1", f.os);
      assertThat(set.fullExport()).exists();
      assertThat(SnapshotSet.shaFileFor(set.fullExport())).exists();
      assertThat(set.webappArchive()).exists();
      assertThat(set.buildomaticArchive()).exists();
      assertThat(set.manifest()).exists();
      assertThat(f.snapshots().find("r-up-1", "backup-keystore")).isPresent();
      assertThat(f.snapshots().find("r-up-1", "backup-config")).isPresent();
      assertThat(UpgradeFixture.read(f.webappDir.resolve("version.txt"))).isEqualTo("9.0.0");
      assertThat(f.sha(f.webappDir.resolve("scripts").resolve("app.js")))
          .isNotEqualTo(oldWebappHash);
      // The package ships js-upgrade-newdb, whose fake enforces the vendor's contract: it refuses
      // to run without an existing export file (ADR-0012), so reaching here proves the point-B
      // full export was passed.
      assertThat(UpgradeFixture.read(f.vendorLog))
          .contains("upgrade-minimal-pro")
          .contains("-Dstrategy=standard")
          .contains("-DimportFile=" + set.fullExport().toAbsolutePath().normalize());
      assertThat(f.logs()).noneMatch(m -> m.contains("using js-ant"));
      Path staged = f.packageDir.resolve("buildomatic").resolve("default_master.properties");
      String master = UpgradeFixture.read(staged);
      assertThat(master)
          .contains("dbHost=localhost")
          .contains("appServerType=tomcat")
          .doesNotContain("TopSecret");
      assertThat(master).contains("appServerDir=");
      List<SnapshotRecord> rows = f.store().snapshots("r-up-1");
      assertThat(rows).anyMatch(r -> r.referencedBy().equals(Optional.of("upgrade")));
      assertThat(f.store().auditRows(20)).anyMatch(a -> a.action().equals("upgrade.completed"));
      assertThat(f.fake.platform.controller.events).contains("stop", "start");
    }
  }

  @Test
  void should_pass_the_vendor_strategy_and_export_to_js_ant_when_package_ships_no_wrapper()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.removeVendorWrappers();
      Plan plan = f.ops().planUpgrade(newdb(f));

      RunOutcome outcome = f.run(plan, "r-up-ant", RunOptions.DEFAULT);

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
      SnapshotSet set = SnapshotSet.of(f.fake.home, "r-up-ant", f.os);
      assertThat(f.logs()).anyMatch(m -> m.contains("using js-ant upgrade-minimal-pro"));
      assertThat(UpgradeFixture.read(f.vendorLog))
          .contains("upgrade-minimal-pro")
          .contains("-Dstrategy=standard")
          .contains("-DimportFile=" + set.fullExport().toAbsolutePath().normalize());
    }
  }

  @Test
  void should_restore_webapp_from_backup_when_smoke_fails_and_rollback_all_requested()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      String oldHash = f.sha(f.webappDir.resolve("scripts").resolve("app.js"));
      f.fake.adapter.schedulerReachable = false;
      Plan plan = f.ops().planUpgrade(newdb(f));

      RunOutcome outcome = f.run(plan, "r-up-2", RunOptions.withRollbackAll());

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.RolledBack.class);
      RunOutcome.RolledBack rolled = (RunOutcome.RolledBack) outcome;
      assertThat(rolled.cause()).contains("smoke failed").contains("scheduler");
      assertThat(UpgradeFixture.read(f.webappDir.resolve("version.txt"))).isEqualTo("8.2.0");
      assertThat(f.sha(f.webappDir.resolve("scripts").resolve("app.js"))).isEqualTo(oldHash);
      assertThat(f.webappDir.resolve("WEB-INF").resolve("lib").resolve("jasperserver-9.0.0.jar"))
          .doesNotExist();
      assertThat(f.packageDir.resolve("buildomatic").resolve("default_master.properties"))
          .doesNotExist();
      assertThat(SnapshotSet.of(f.fake.home, "r-up-2", f.os).webappArchive())
          .as("backups kept")
          .exists();
      assertThat(f.logs()).anyMatch(m -> m.contains("webapp restored from"));
    }
  }

  @Test
  void should_restore_point_b_and_restart_the_service_when_the_vendor_script_fails_after_copying()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      String oldHash = f.sha(f.webappDir.resolve("scripts").resolve("app.js"));
      f.failVendorScriptAfterCopy();
      Plan plan = f.ops().planUpgrade(newdb(f));

      RunOutcome outcome = f.run(plan, "r-up-vendor-fail", RunOptions.DEFAULT);

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.RolledBack.class);
      assertThat(((RunOutcome.RolledBack) outcome).cause())
          .contains("vendor upgrade exited with 3");
      assertThat(UpgradeFixture.read(f.webappDir.resolve("version.txt")))
          .as("the half-migrated webapp is put back to point B")
          .isEqualTo(UpgradeFixture.OLD_VERSION);
      assertThat(f.sha(f.webappDir.resolve("scripts").resolve("app.js"))).isEqualTo(oldHash);
      assertThat(f.webappDir.resolve("WEB-INF").resolve("lib").resolve("jasperserver-9.0.0.jar"))
          .doesNotExist();
      assertThat(f.logs()).anyMatch(m -> m.contains("webapp restored from"));
      assertThat(f.fake.platform.controller.events).endsWith("stop", "start");
      assertThat(f.fake.platform.serviceState)
          .isEqualTo(com.jaspersoft.jrsupgrade.core.platform.ServiceController.State.RUNNING);
      List<String> journal =
          f.store().transitions("r-up-vendor-fail").stream()
              .map(t -> t.stepId() + ":" + t.toState())
              .toList();
      assertThat(journal)
          .containsSubsequence(
              "run-vendor-upgrade:FAILED",
              "run-vendor-upgrade:ROLLED_BACK",
              "stop-service:ROLLED_BACK");
    }
  }

  /**
   * Review finding 1.13, the half of it the upgrade copy got wrong: the "this run stopped the
   * service" marker was written after the stop, so a stop that went wrong half-way left no marker
   * and the rollback left the service in whatever state the failed stop produced. The marker is
   * written before the stop is attempted; compensation then converges the service to running.
   */
  @Test
  void should_restart_the_service_on_rollback_when_the_stop_itself_went_wrong_half_way()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.fake.platform.controller.stopLeaves =
          Optional.of(com.jaspersoft.jrsupgrade.core.platform.ServiceController.State.UNKNOWN);
      Plan plan = f.ops().planUpgrade(newdb(f));

      RunOutcome outcome = f.run(plan, "r-up-halfstop", RunOptions.DEFAULT);

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.RolledBack.class);
      assertThat(((RunOutcome.RolledBack) outcome).cause()).contains("did not stop");
      assertThat(f.fake.platform.controller.events).endsWith("stop", "start");
      assertThat(f.fake.platform.serviceState)
          .isEqualTo(com.jaspersoft.jrsupgrade.core.platform.ServiceController.State.RUNNING);
    }
  }

  /**
   * Review §1.4: a vendor script that announces a new keystore has made the repository's passwords
   * undecryptable, whatever it exited with; the run must roll back to point B, which puts the saved
   * keystore files back.
   */
  @Test
  void should_roll_back_to_point_b_when_the_vendor_script_creates_a_new_keystore()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.vendorScriptCreatesKeystore();
      Plan plan = f.ops().planUpgrade(newdb(f));

      RunOutcome outcome = f.run(plan, "r-up-new-ks", RunOptions.DEFAULT);

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.RolledBack.class);
      assertThat(((RunOutcome.RolledBack) outcome).cause()).contains("created a new keystore");
      assertThat(f.logs()).anyMatch(m -> m.contains("backup-keystore restored from"));
      assertThat(f.fake.platform.controller.events).endsWith("stop", "start");
    }
  }

  /** Review §2.1: the upgraded server runs in the new Tomcat and jrs-upgrade follows it. */
  @Test
  void should_upgrade_into_the_new_tomcat_and_repoint_config_when_tomcat_dir_is_given()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.createWithManualService(tmp)) {
      Plan plan = f.ops().planUpgrade(UpgradePlanTest.withNewTomcat(f));

      RunOutcome outcome = f.run(plan, "r-up-tomcat", RunOptions.DEFAULT);

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
      Path staged = f.packageDir.resolve("buildomatic").resolve("default_master.properties");
      java.util.Properties master = new java.util.Properties();
      try (var in = Files.newBufferedReader(staged, java.nio.charset.StandardCharsets.ISO_8859_1)) {
        master.load(in);
      }
      assertThat(Path.of(master.getProperty("appServerDir")))
          .isEqualTo(f.newTomcatDir.toAbsolutePath().normalize());
      assertThat(f.newTomcatDir.resolve("webapps").resolve("jasperserver-pro").resolve("WEB-INF"))
          .isDirectory();
      Config after = new ConfigLoader().load(f.fake.home, Map.of(), Map.of());
      assertThat(after.server().tomcatDir()).contains(f.newTomcatDir.toAbsolutePath().normalize());
    }
  }

  /** Review finding 1.13: a service the operator had stopped is not this run's to start. */
  @Test
  void should_leave_a_service_the_operator_had_stopped_stopped_when_rolling_back()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.fake.platform.serviceState =
          com.jaspersoft.jrsupgrade.core.platform.ServiceController.State.STOPPED;
      f.failVendorScriptAfterCopy();
      Plan plan = f.ops().planUpgrade(newdb(f));

      // --rollback-all: the default rollback is phase-scoped and leaves the backup phase's own
      // service start in place; rolling back everything must end where the operator left it
      RunOutcome outcome = f.run(plan, "r-up-prestopped", RunOptions.withRollbackAll());

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.RolledBack.class);
      // the run found the service stopped, so it neither stopped nor started it
      assertThat(f.fake.platform.controller.events).doesNotContain("start");
      assertThat(f.fake.platform.serviceState)
          .isEqualTo(com.jaspersoft.jrsupgrade.core.platform.ServiceController.State.STOPPED);
    }
  }

  /**
   * The hotfix copy learnt that {@code identity()} is memoised and a wait-for-server polling it
   * passes without reaching the server; the upgrade copy never did. One implementation, one fix.
   */
  @Test
  void should_probe_the_server_uncached_when_waiting_after_a_restart() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(newdb(f));
      f.fake.adapter.calls.clear();

      RunOutcome outcome = f.run(plan, "r-up-wait", RunOptions.DEFAULT);

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.fake.adapter.calls).contains("refreshIdentity");
    }
  }

  @Test
  void should_only_roll_back_the_verify_phase_when_smoke_fails_without_rollback_all()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.fake.adapter.schedulerReachable = false;
      Plan plan = f.ops().planUpgrade(newdb(f));

      RunOutcome outcome = f.run(plan, "r-up-3", RunOptions.DEFAULT);

      assertThat(outcome).isInstanceOf(RunOutcome.RolledBack.class);
      assertThat(((RunOutcome.RolledBack) outcome).rolledBackToPhase()).isEqualTo("verify");
      assertThat(UpgradeFixture.read(f.webappDir.resolve("version.txt"))).isEqualTo("9.0.0");
      assertThat(f.events)
          .anyMatch(e -> e.toString().contains("upgrade rollback r-up-3 --to-point B"));
    }
  }

  @Test
  void should_refuse_to_plan_a_rollback_when_the_config_snapshot_was_pruned() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan up = f.ops().planUpgrade(newdb(f));
      assertThat(f.run(up, "r-up-pruned", RunOptions.DEFAULT))
          .isInstanceOf(RunOutcome.Succeeded.class);
      Path config = f.fake.home.snapshots().resolve("r-up-pruned").resolve(SnapshotSet.CONFIG_STEP);
      assertThat(config).isDirectory();
      Trees.deleteRecursively(config);

      assertThatThrownBy(() -> f.ops().planRollback("r-up-pruned", RollbackPoint.B))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining(SnapshotSet.CONFIG_STEP)
          .hasMessageContaining("point B")
          .asInstanceOf(InstanceOfAssertFactories.type(UpgradeException.class))
          .satisfies(e -> assertThat(e.exitCode()).isEqualTo(UpgradeException.PRECHECK))
          .satisfies(
              e -> assertThat(e.remediation()).contains("old webapp against the new database"));
    }
  }

  @Test
  void should_refuse_to_plan_a_rollback_when_the_webapp_archive_was_corrupted() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan up = f.ops().planUpgrade(newdb(f));
      assertThat(f.run(up, "r-up-bad", RunOptions.DEFAULT))
          .isInstanceOf(RunOutcome.Succeeded.class);
      SnapshotSet set = SnapshotSet.of(f.fake.home, "r-up-bad", f.fake.platform.os());
      UpgradeFixture.write(set.webappArchive(), "not the archive that was recorded");

      assertThatThrownBy(() -> f.ops().planRollback("r-up-bad", RollbackPoint.B))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("point B")
          .hasMessageContaining("was recorded");
    }
  }

  @Test
  void should_prune_every_snapshot_of_a_run_together_or_none_of_them() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan up = f.ops().planUpgrade(newdb(f));
      assertThat(f.run(up, "r-up-unit", RunOptions.DEFAULT))
          .isInstanceOf(RunOutcome.Succeeded.class);
      SnapshotStore snapshots = f.snapshots();
      int total = snapshots.list().size();
      assertThat(total).as("the upgrade keeps more than one snapshot").isGreaterThan(1);

      // A cap that would cut the run in half must take the whole run or leave it whole.
      List<Snapshot> candidates = snapshots.pruneCandidates(Duration.ZERO, total - 1, Set.of());

      assertThat(candidates).isNotEmpty();
      assertThat(candidates.stream().map(Snapshot::runId).distinct()).containsExactly("r-up-unit");
      assertThat(candidates).hasSize(total);
    }
  }

  @Test
  void should_restore_archived_webapp_when_rolling_back_a_successful_upgrade() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      String oldHash = f.sha(f.webappDir.resolve("scripts").resolve("app.js"));
      Plan up = f.ops().planUpgrade(newdb(f));
      assertThat(f.run(up, "r-up-4", RunOptions.DEFAULT)).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(UpgradeFixture.read(f.webappDir.resolve("version.txt"))).isEqualTo("9.0.0");
      UpgradeFixture.write(
          f.installDir.resolve("buildomatic").resolve("new-file.txt"), "from 9.0.0");

      Plan back = f.ops().planRollback("r-up-4", RollbackPoint.C);
      assertThat(UpgradeFixture.ids(back))
          .containsExactly(
              "stop-service",
              "restore-webapp",
              "restore-buildomatic",
              "restore-config",
              "restore-keystore",
              "restore-jrs-upgrade-config",
              "start-service",
              "wait-for-server",
              "record-rollback");
      assertThat(back.summary().warnings())
          .contains(DefaultUpgradeOperations.FILES_ONLY_WARNING)
          .anyMatch(w -> w.contains("point C"))
          .anyMatch(
              w ->
                  w.contains("NEWDB mode")
                      && w.contains("dropped and recreated")
                      && w.contains("full-export.zip"));
      RunOutcome outcome = f.run(back, "r-rb-4", RunOptions.DEFAULT);

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(UpgradeFixture.read(f.webappDir.resolve("version.txt"))).isEqualTo("8.2.0");
      assertThat(f.sha(f.webappDir.resolve("scripts").resolve("app.js"))).isEqualTo(oldHash);
      assertThat(f.installDir.resolve("buildomatic").resolve("new-file.txt")).doesNotExist();
      assertThat(
              f.fake
                  .home
                  .runDir("r-rb-4")
                  .resolve("aside")
                  .resolve("jasperserver-pro")
                  .resolve("version.txt"))
          .as("replaced tree kept aside")
          .hasContent("9.0.0");
      assertThat(f.store().auditRows(20)).anyMatch(a -> a.action().equals("upgrade.rolled-back"));
    }
  }

  @Test
  void should_refuse_rollback_plan_when_run_is_unknown_or_not_an_upgrade() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> f.ops().planRollback("r-nope", RollbackPoint.B))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("unknown run");
      f.store().recordRunStart("r-hf", "hotfix.apply", Optional.empty(), Instant.EPOCH);
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> f.ops().planRollback("r-hf", RollbackPoint.B))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("not an upgrade");
    }
  }

  @Test
  void should_fail_at_doctor_precheck_and_mutate_nothing_when_layout_is_broken() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.fake.platform.writable = false;
      Plan plan = f.ops().planUpgrade(newdb(f));

      RunOutcome outcome = f.run(plan, "r-up-5", RunOptions.DEFAULT);

      assertThat(outcome).isInstanceOf(RunOutcome.PrecheckFailed.class);
      assertThat(((RunOutcome.PrecheckFailed) outcome).stepId()).isEqualTo("doctor");
      assertThat(((RunOutcome.PrecheckFailed) outcome).message()).contains("permissions");
      assertThat(Files.exists(SnapshotSet.of(f.fake.home, "r-up-5", f.os).dir())).isFalse();
    }
  }
}
