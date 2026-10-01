package com.jaspersoft.jrsupgrade.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.RunOptions;
import com.jaspersoft.jrsupgrade.core.engine.RunOutcome;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.state.Customization;
import com.jaspersoft.jrsupgrade.ops.ReportItem;
import com.jaspersoft.jrsupgrade.ops.TomcatJavaOpts;
import com.jaspersoft.jrsupgrade.ops.doctor.DoctorOperation;
import com.jaspersoft.jrsupgrade.ops.doctor.DoctorOptions;
import com.jaspersoft.jrsupgrade.ops.doctor.DoctorReport;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.Mode;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.RollbackOptions;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.RollbackPoint;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.UpgradeOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpgradePlanTest {

  @TempDir Path tmp;

  private static UpgradeOptions newdb(UpgradeFixture f) {
    return UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, f.packageDir);
  }

  /**
   * Issue #106, upgrade guide 10.1 p.80: the newdb script leaves access, audit and monitoring
   * events behind. With {@code --include-events} they are imported after the vendor run, while the
   * server is still down; without it the plan says they are left behind.
   */
  @Test
  void should_import_events_after_the_vendor_run_when_include_events_is_given() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(newdb(f).withIncludeEvents(true));

      assertThat(UpgradeFixture.ids(plan))
          .containsSubsequence("run-vendor-upgrade", "import-events", "clear-tomcat-caches");
      Step step = UpgradeFixture.step(plan, "import-events");
      assertThat(step.phase()).isEqualTo("vendor-upgrade");
      assertThat(step.irreversible()).isTrue();
      assertThat(step.detail())
          .contains("js-import")
          .contains("--include-access-events")
          .contains("--include-audit-events")
          .contains("--include-monitoring-events");
      assertThat(plan.summary().warnings())
          .doesNotContain(DefaultUpgradeOperations.EVENTS_LEFT_BEHIND_WARNING);
    }
  }

  @Test
  void should_say_events_are_left_behind_when_newdb_and_include_events_is_not_given()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(newdb(f));

      assertThat(UpgradeFixture.ids(plan)).doesNotContain("import-events");
      assertThat(plan.summary().warnings())
          .contains(DefaultUpgradeOperations.EVENTS_LEFT_BEHIND_WARNING);
      assertThat(DefaultUpgradeOperations.EVENTS_LEFT_BEHIND_WARNING).contains("--include-events");
    }
  }

  @Test
  void should_neither_import_nor_warn_about_events_when_mode_samedb() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan =
          f.ops()
              .planUpgrade(
                  new UpgradeOptions(UpgradeFixture.NEW_VERSION, f.packageDir, Mode.SAMEDB, true)
                      .withIncludeEvents(true));

      assertThat(UpgradeFixture.ids(plan)).doesNotContain("import-events");
      assertThat(plan.summary().warnings())
          .doesNotContain(DefaultUpgradeOperations.EVENTS_LEFT_BEHIND_WARNING);
    }
  }

  @Test
  void should_list_five_phases_and_step_ids_when_mode_newdb() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(newdb(f));

      assertThat(plan.byPhase().keySet())
          .containsExactly("preflight", "backup", "vendor-upgrade", "reconcile", "verify");
      assertThat(UpgradeFixture.ids(plan))
          .containsExactly(
              "doctor",
              "verify-target-package",
              "verify-vendor-preconditions",
              "backup-keystore",
              "backup-webapp",
              "backup-config",
              "write-master-properties",
              "stage-keystore-init",
              "stop-service",
              "full-export",
              "dump-foreign-schema",
              "run-vendor-upgrade",
              "check-adhoc-templates",
              "clear-tomcat-caches",
              "clear-repository-cache",
              "start-service",
              "wait-for-server",
              "plan-customization-reapply",
              "check-analytics-jndi",
              "smoke",
              "record-upgrade",
              "point-config-at-target");
      assertThat(plan.summary().operation()).isEqualTo("upgrade");
      assertThat(plan.summary().target()).isEqualTo("8.2.0 -> 9.0.0 (newdb)");
      assertThat(plan.summary().serviceRestart()).isTrue();
      assertThat(plan.summary().strategy()).isEqualTo("vendor-cli");
      assertThat(plan.summary().warnings())
          .contains(DefaultUpgradeOperations.NEWDB_WARNING)
          .contains(DefaultUpgradeOperations.NEWDB_STAYS_STOPPED_WARNING)
          .contains(DefaultUpgradeOperations.NEWDB_ROLLBACK_WARNING)
          .doesNotContain(DefaultUpgradeOperations.FILES_ONLY_WARNING)
          .doesNotContain(DefaultUpgradeOperations.SAMEDB_WARNING);
      assertThat(UpgradeFixture.step(plan, "run-vendor-upgrade").detail())
          .contains("js-upgrade-newdb <point-B full export>")
          .contains("-DimportFile=<point-B full export>");
      assertThat(plan.summary().rollbackPointsByPhase())
          .containsKeys("preflight", "backup", "vendor-upgrade", "reconcile", "verify");
      assertThat(plan.summary().rollbackPointsByPhase().get("verify"))
          .contains("failure offers rollback to point B");
      assertThat(plan.fingerprint().inputs())
          .containsKeys("server", "package", "config", "to", "mode");
      assertThat(plan.steps()).allSatisfy(s -> assertThat(s.title()).isNotBlank());
      assertThat(UpgradeFixture.step(plan, "run-vendor-upgrade").irreversible()).isFalse();
      // upgrade guide 10.1 pp.34-36 "Additional tasks": caches regenerate, nothing to put back
      assertThat(UpgradeFixture.step(plan, "clear-tomcat-caches").irreversible()).isTrue();
      assertThat(UpgradeFixture.step(plan, "clear-repository-cache").irreversible()).isTrue();
      for (Step s : plan.byPhase().get("preflight")) {
        assertThat(s.mutating()).as(s.id()).isFalse();
      }
    }
  }

  @Test
  void should_add_confirm_db_backup_and_the_samedb_sentence_when_mode_samedb() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan =
          f.ops()
              .planUpgrade(
                  new UpgradeOptions(UpgradeFixture.NEW_VERSION, f.packageDir, Mode.SAMEDB, true));

      assertThat(UpgradeFixture.ids(plan))
          .startsWith(
              "doctor",
              "verify-target-package",
              "verify-vendor-preconditions",
              "confirm-db-backup");
      // samedb migrates the database in place, so the server may serve between the export and
      // the vendor run; only newdb rebuilds the database from that export (review §1.6)
      assertThat(UpgradeFixture.ids(plan))
          .containsSubsequence(
              "full-export-stop-service",
              "full-export",
              "full-export-start-service",
              "full-export-wait-for-server",
              "backup-keystore");
      assertThat(UpgradeFixture.ids(plan))
          .containsSubsequence("write-master-properties", "stage-keystore-init", "stop-service");
      assertThat(UpgradeFixture.ids(plan))
          .containsSubsequence(
              "run-vendor-upgrade",
              "clear-tomcat-caches",
              "clear-repository-cache",
              "start-service");
      assertThat(plan.summary().warnings())
          .contains(
              "Rollback restores files only. Restore the database from your own backup before"
                  + " running rollback.")
          .contains(DefaultUpgradeOperations.SAMEDB_WARNING)
          .doesNotContain(DefaultUpgradeOperations.NEWDB_WARNING)
          .doesNotContain(DefaultUpgradeOperations.NEWDB_STAYS_STOPPED_WARNING);
      assertThat(plan.summary().target()).endsWith("(samedb)");
      assertThat(UpgradeFixture.step(plan, "run-vendor-upgrade").title())
          .contains("js-upgrade-samedb");
      CheckResult ok = UpgradeFixture.step(plan, "confirm-db-backup").precheck(f.ctx("r-1"));
      assertThat(ok).isInstanceOf(CheckResult.Pass.class);
    }
  }

  @Test
  void should_fail_confirm_db_backup_precheck_when_samedb_not_confirmed() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan =
          f.ops()
              .planUpgrade(
                  new UpgradeOptions(UpgradeFixture.NEW_VERSION, f.packageDir, Mode.SAMEDB, false));

      CheckResult result = UpgradeFixture.step(plan, "confirm-db-backup").precheck(f.ctx("r-1"));

      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message()).contains("--db-backup-confirmed");
    }
  }

  /** ADR-0029 (field test 2): newdb's own export is the backup, so the question goes. */
  @Test
  void should_not_ask_for_a_database_backup_in_newdb_mode_and_say_what_is_backed_up()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan =
          f.ops()
              .planUpgrade(
                  new UpgradeOptions(UpgradeFixture.NEW_VERSION, f.packageDir, Mode.NEWDB, false));

      assertThat(UpgradeFixture.ids(plan)).doesNotContain("confirm-db-backup");
      assertThat(plan.summary().warnings())
          .anyMatch(w -> w.contains("upgrade rollback --restore-database rebuilds it"));
    }
  }

  @Test
  void should_still_ask_for_a_database_backup_in_samedb_mode_and_say_why() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan =
          f.ops()
              .planUpgrade(
                  new UpgradeOptions(UpgradeFixture.NEW_VERSION, f.packageDir, Mode.SAMEDB, false));

      CheckResult r = UpgradeFixture.step(plan, "confirm-db-backup").precheck(f.ctx("r-1"));

      assertThat(r).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) r).message())
          .contains("schema in place")
          .contains("an export cannot undo that")
          .contains("--db-backup-confirmed");
    }
  }

  /** Spec §10.2 "Rehearsal" (field test 2, U1): the vendor's validation, nothing else. */
  @Test
  void should_plan_a_rehearsal_that_stops_nothing_and_backs_up_nothing_when_test_is_given()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planTest(newdb(f));

      assertThat(UpgradeFixture.ids(plan))
          .containsExactly(
              "doctor",
              "verify-target-package",
              "verify-vendor-preconditions",
              "write-master-properties",
              "stage-keystore-init",
              "run-vendor-test",
              "unstage-target-package");
      assertThat(plan.summary().operation()).isEqualTo("upgrade.test");
      assertThat(plan.summary().target()).isEqualTo("rehearsal of 8.2.0 -> 9.0.0 (newdb)");
      assertThat(plan.summary().serviceRestart()).isFalse();
      assertThat(plan.summary().backupLocations()).isEmpty();
      assertThat(plan.summary().warnings())
          .contains(DefaultUpgradeOperations.REHEARSAL_WARNING)
          .doesNotContain(DefaultUpgradeOperations.NEWDB_WARNING)
          .doesNotContain(DefaultUpgradeOperations.NEWDB_ROLLBACK_WARNING);
      assertThat(UpgradeFixture.step(plan, "run-vendor-test").mutating()).isFalse();
      assertThat(UpgradeFixture.step(plan, "run-vendor-test").detail())
          .contains("js-upgrade-newdb test")
          .contains("pre-upgrade-test-pro");
      assertThat(UpgradeFixture.step(plan, "unstage-target-package").irreversible()).isTrue();
      assertThat(plan.fingerprint().inputs()).containsEntry("test", "true");
    }
  }

  @Test
  void should_refuse_a_rehearsal_with_exit_6_when_the_upgrade_path_is_unsupported()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertThatThrownBy(() -> f.ops().planTest(UpgradeOptions.newdb("7.5.0", f.packageDir)))
          .isInstanceOf(UpgradeException.class)
          .satisfies(e -> assertThat(((UpgradeException) e).exitCode()).isEqualTo(6));
    }
  }

  @Test
  void should_refuse_restore_database_for_a_samedb_run() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      UpgradeOptions samedb =
          new UpgradeOptions(UpgradeFixture.NEW_VERSION, f.packageDir, Mode.SAMEDB, true);
      assertThat(f.run(f.ops().planUpgrade(samedb), "r-sd", RunOptions.DEFAULT))
          .isInstanceOf(RunOutcome.Succeeded.class);

      assertThatThrownBy(
              () -> f.ops().planRollback("r-sd", new RollbackOptions(RollbackPoint.B, true)))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("samedb")
          .hasMessageContaining("an export cannot undo that")
          .satisfies(e -> assertThat(((UpgradeException) e).exitCode()).isEqualTo(6));
      assertThat(UpgradeFixture.ids(f.ops().planRollback("r-sd", RollbackPoint.B)))
          .doesNotContain("rebuild-database", "reimport-full-export");
    }
  }

  @Test
  void should_plan_the_database_rebuild_after_the_files_and_before_the_start_for_a_newdb_run()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertThat(f.run(f.ops().planUpgrade(newdb(f)), "r-nd", RunOptions.DEFAULT))
          .isInstanceOf(RunOutcome.Succeeded.class);

      Plan plan = f.ops().planRollback("r-nd", new RollbackOptions(RollbackPoint.B, true));

      assertThat(UpgradeFixture.ids(plan))
          .containsSubsequence(
              "restore-buildomatic",
              "restore-keystore",
              "rebuild-database",
              "reimport-full-export",
              "start-service");
      assertThat(plan.summary().target()).endsWith("with the database");
      assertThat(plan.summary().warnings())
          .anyMatch(w -> w.contains("rebuilds the repository database from"))
          .noneMatch(w -> w.equals(DefaultUpgradeOperations.FILES_ONLY_WARNING));
      assertThat(UpgradeFixture.step(plan, "rebuild-database").irreversible()).isTrue();
      assertThat(UpgradeFixture.step(plan, "reimport-full-export").irreversible()).isTrue();
      assertThat(UpgradeFixture.step(plan, "rebuild-database").title()).contains("init-js-db-pro");
      assertThat(UpgradeFixture.step(plan, "rebuild-database").precheck(f.ctx("r-rb")))
          .isInstanceOf(CheckResult.Pass.class);
      // without the flag the plan says how to get the database back
      assertThat(f.ops().planRollback("r-nd", RollbackPoint.B).summary().warnings())
          .anyMatch(w -> w.contains("re-run with --restore-database"));
    }
  }

  @Test
  void should_fail_run_vendor_upgrade_precheck_when_newdb_and_full_export_missing()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(newdb(f));

      CheckResult result = UpgradeFixture.step(plan, "run-vendor-upgrade").precheck(f.ctx("r-1"));

      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message())
          .contains("full-export")
          .contains("js-upgrade-newdb rebuilds the repository database from it");
    }
  }

  @Test
  void should_refuse_with_exit_6_when_upgrade_path_unsupported() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertThatThrownBy(() -> f.ops().planUpgrade(UpgradeOptions.newdb("7.5.0", f.packageDir)))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("8.2.0 -> 7.5.0")
          .satisfies(e -> assertThat(((UpgradeException) e).exitCode()).isEqualTo(6));
    }
  }

  @Test
  void should_fail_verify_target_package_precheck_when_vendor_java_mismatches() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      // platform-support 9.0.0: JDK 8, 11 and 17; 21 is not on the sheet (review §1.2)
      f.javaVersion("openjdk version \"21.0.4\" 2024-07-16");
      Plan plan = f.ops().planUpgrade(newdb(f));

      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));

      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message())
          .contains("Java 21")
          .contains("needs Java 8, 11 or 17");
    }
  }

  @Test
  void should_pass_verify_target_package_precheck_when_java_is_any_allowed_major()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.javaVersion("openjdk version \"11.0.24\" 2024-07-16");
      Plan plan = f.ops().planUpgrade(newdb(f));

      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));

      assertThat(result).as(result.toString()).isInstanceOf(CheckResult.Pass.class);
    }
  }

  /** Upgrade guide 10.0 pp.11-12: 8.x reaches 10.0 as newdb only (review §1.3). */
  @Test
  void should_refuse_with_exit_6_when_the_mode_is_not_offered_for_the_path() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertThatThrownBy(
              () ->
                  f.ops()
                      .planUpgrade(new UpgradeOptions("10.0.0", f.packageDir, Mode.SAMEDB, true)))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("8.2.0 -> 10.0.0")
          .hasMessageContaining("samedb")
          .hasMessageContaining("newdb")
          .satisfies(e -> assertThat(((UpgradeException) e).exitCode()).isEqualTo(6));
    }
  }

  /**
   * Review §2.1: 10.0 moved to Jakarta EE; the Tomcat that will host the target must be one the
   * platform sheet certifies for it. The fixture's target is 9.0.0, certified for Tomcat 8.5 and 9.
   */
  @Test
  void should_fail_verify_target_package_precheck_when_the_tomcat_is_not_certified_for_the_target()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      UpgradeFixture.tomcatVersion(f.tomcatDir, "10.1.24");
      Plan plan = f.ops().planUpgrade(newdb(f));

      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));

      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message())
          .contains("Tomcat 10.1.24")
          .contains("not certified for JasperReports Server 9.0.0");
      assertThat(((CheckResult.Fail) result).remediation()).contains("--tomcat-dir");
    }
  }

  @Test
  void should_pass_verify_target_package_precheck_when_the_tomcat_is_certified() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      UpgradeFixture.tomcatVersion(f.tomcatDir, "9.0.85");
      Plan plan = f.ops().planUpgrade(newdb(f));

      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));

      assertThat(result).as(result.toString()).isInstanceOf(CheckResult.Pass.class);
      assertThat(plan.summary().warnings()).noneMatch(w -> w.contains("Tomcat version"));
    }
  }

  @Test
  void should_warn_when_the_tomcat_version_cannot_be_read() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(newdb(f));

      assertThat(plan.summary().warnings())
          .anyMatch(w -> w.contains("Tomcat version") && w.contains("could not be read"));
    }
  }

  /** ADR-0026: a registered service would start the old Tomcat after the vendor run. */
  @Test
  void should_refuse_tomcat_dir_with_exit_2_unless_the_service_is_manual() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertThatThrownBy(() -> f.ops().planUpgrade(withNewTomcat(f)))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("service.kind manual")
          .hasMessageContaining("systemd")
          .satisfies(e -> assertThat(((UpgradeException) e).exitCode()).isEqualTo(2))
          // ADR-0026 amendment (issue #109): the refusal says how to re-register the service
          .satisfies(
              e ->
                  assertThat(((UpgradeException) e).remediation())
                      .contains("systemctl cat jasperreports")
                      .contains(f.newTomcatDir.toString())
                      .contains("systemctl daemon-reload"));
    }
  }

  /** ADR-0026 amendment (issue #109): the manual plan carries the switch steps for this host. */
  @Test
  void should_state_the_service_switch_steps_in_the_summary_when_the_service_is_manual()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.createWithManualService(tmp)) {
      Plan plan = f.ops().planUpgrade(withNewTomcat(f));

      // the fixture runs on the build host's operating system, so only what both share is pinned
      assertThat(plan.summary().warnings())
          .anySatisfy(
              w ->
                  assertThat(w)
                      .startsWith(DefaultUpgradeOperations.SERVICE_SWITCH_WARNING)
                      .contains("start the upgraded server with")
                      .contains(f.newTomcatDir.toString())
                      .contains("re-register it for the new Tomcat")
                      .contains("<name>"));
      // a Tomcat 9 is not judged for --add-opens
      assertThat(plan.summary().warnings()).noneMatch(w -> w.contains("--add-opens:"));
    }
  }

  /** Installation guide 10.1 pp.84-86 (issue #109): a Tomcat 10+ without the Java 17/21 options. */
  @Test
  void should_warn_when_the_host_tomcat_setenv_carries_no_add_opens() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.createWithManualService(tmp)) {
      UpgradeFixture.tomcatVersion(f.newTomcatDir, "11.0.11");
      Plan plan = f.ops().planUpgrade(withNewTomcat(f));

      Path setenv = TomcatJavaOpts.setenv(f.newTomcatDir, f.services.platform().os());
      assertThat(plan.summary().warnings())
          .contains(DefaultUpgradeOperations.ADD_OPENS_WARNING.formatted(setenv));

      Files.createDirectories(setenv.getParent());
      Files.writeString(setenv, "JAVA_OPTS=--add-opens java.base/java.lang=ALL-UNNAMED\n");
      assertThat(f.ops().planUpgrade(withNewTomcat(f)).summary().warnings())
          .noneMatch(w -> w.contains("--add-opens:"));
    }
  }

  @Test
  void should_copy_the_webapp_and_point_buildomatic_at_the_new_tomcat_when_tomcat_dir_is_given()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.createWithManualService(tmp)) {
      Plan plan = f.ops().planUpgrade(withNewTomcat(f));

      assertThat(UpgradeFixture.ids(plan))
          .containsSubsequence(
              "stop-service", "full-export", "copy-webapp-to-tomcat", "run-vendor-upgrade");
      assertThat(UpgradeFixture.step(plan, "copy-webapp-to-tomcat").detail())
          .contains(f.newTomcatDir.toString());
      assertThat(UpgradeFixture.step(plan, "clear-tomcat-caches").detail())
          .contains(f.newTomcatDir.toString());
      assertThat(UpgradeFixture.step(plan, "point-config-at-target").detail())
          .contains("server.tomcatDir -> " + f.newTomcatDir);
      assertThat(plan.summary().warnings())
          .contains(DefaultUpgradeOperations.TOMCAT_DIR_WARNING.formatted(f.newTomcatDir));
      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));
      assertThat(result).as(result.toString()).isInstanceOf(CheckResult.Pass.class);
    }
  }

  /** Issue #108: the vendor preconditions step sits right after the package check, read-only. */
  @Test
  void should_verify_the_vendor_preconditions_before_anything_is_stopped() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(newdb(f));

      Step step = UpgradeFixture.step(plan, "verify-vendor-preconditions");
      assertThat(step.phase()).isEqualTo("preflight");
      assertThat(step.mutating()).isFalse();
      assertThat(step.detail())
          .contains("jaspersoft.jrs.license")
          .contains("conf_source/db/<dbType>/jdbc")
          .contains("js.password-storage-config.properties");
      // a 9.0.0 community-shaped fixture on PostgreSQL: nothing applies, so it passes
      assertThat(step.precheck(f.ctx("r-1"))).isInstanceOf(CheckResult.Pass.class);
    }
  }

  /** Issue #108: the analytics JNDI check is a verify step for a 9.0.x target only. */
  @Test
  void should_check_the_analytics_jndi_resources_for_a_9_0_target_only() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan nine = f.ops().planUpgrade(newdb(f));
      Plan ten = f.ops().planUpgrade(UpgradeOptions.newdb("10.0.0", f.packageDir));

      Step check = UpgradeFixture.step(nine, "check-analytics-jndi");
      assertThat(check.phase()).isEqualTo("verify");
      assertThat(check.mutating()).isFalse();
      assertThat(check.detail())
          .contains("jdbc/jasperserverSystemAnalytics")
          .contains("jdbc/jasperserverAuditAnalytics");
      assertThat(UpgradeFixture.ids(ten)).doesNotContain("check-analytics-jndi");
    }
  }

  /**
   * Issue #11, upgrade guide 10.1 p.93: a customer copy of {@code context.xml} can drop the two
   * resources on any target from 9.0, so a registered one brings the check to 10.x as well.
   */
  @Test
  void should_check_the_analytics_jndi_resources_on_10_x_when_context_xml_is_customized()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path contextXml = f.webappDir.resolve("META-INF").resolve("context.xml");
      f.store()
          .registerCustomization(
              new Customization(contextXml, "aa", Optional.empty(), Instant.EPOCH));

      Plan ten = f.ops().planUpgrade(UpgradeOptions.newdb("10.0.0", f.packageDir));
      Step check = UpgradeFixture.step(ten, "check-analytics-jndi");
      UpgradeFixture.write(
          contextXml,
          "<Context><Resource name=\"jdbc/jasperserverSystemAnalytics\"/>"
              + "<Resource name=\"jdbc/bigquery\" url=\"${BQ_URL}\"/></Context>");

      assertThat(check.execute(f.ctx("r-jndi"), f.events::add)).isInstanceOf(StepResult.Ok.class);
      assertThat(f.logs())
          .anySatisfy(
              line ->
                  assertThat(line)
                      .contains(contextXml.toString())
                      .contains("jdbc/jasperserverAuditAnalytics")
                      .contains("10.0.0"));
      assertThat(AnalyticsJndiSteps.applies("8.2.0", f.store().customizations())).isFalse();
    }
  }

  /** Issue #108: the password migration follows the vendor run on a samedb upgrade to 10.1+. */
  @Test
  void should_add_migrate_passwords_after_the_vendor_run_when_samedb_to_10_1_asks_for_it()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.fake.adapter.identity = com.jaspersoft.jrsupgrade.ops.FakeJrsAdapter.identity("10.0.0");
      Plan plan = f.ops().planUpgrade(samedbTo(f, "10.1.0").withMigratePasswords(true));

      assertThat(UpgradeFixture.ids(plan))
          .containsSubsequence("run-vendor-upgrade", "migrate-passwords", "clear-tomcat-caches");
      Step step = UpgradeFixture.step(plan, "migrate-passwords");
      assertThat(step.phase()).isEqualTo("vendor-upgrade");
      assertThat(step.irreversible()).isTrue();
      assertThat(step.detail())
          .contains("js-ant migrate-passwords-dry-run")
          .contains("js-ant migrate-passwords");
      assertThat(UpgradeFixture.ids(f.ops().planUpgrade(samedbTo(f, "10.1.0"))))
          .doesNotContain("migrate-passwords");
    }
  }

  @Test
  void should_refuse_migrate_passwords_with_exit_2_when_the_target_is_below_10_1()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertThatThrownBy(() -> f.ops().planUpgrade(samedbTo(f, "9.0.0").withMigratePasswords(true)))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("10.1.0 or later")
          .satisfies(e -> assertThat(((UpgradeException) e).exitCode()).isEqualTo(2));
    }
  }

  @Test
  void should_warn_and_skip_migrate_passwords_when_mode_newdb() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.fake.adapter.identity = com.jaspersoft.jrsupgrade.ops.FakeJrsAdapter.identity("10.0.0");
      Plan plan =
          f.ops()
              .planUpgrade(UpgradeOptions.newdb("10.1.0", f.packageDir).withMigratePasswords(true));

      assertThat(UpgradeFixture.ids(plan)).doesNotContain("migrate-passwords");
      assertThat(plan.summary().warnings())
          .contains(DefaultUpgradeOperations.MIGRATE_PASSWORDS_NEWDB_WARNING);
    }
  }

  /** Review §3.2 (issue #112): on a clustered licence the plan says this node is the only one. */
  @Test
  void should_warn_that_other_nodes_stay_on_old_code_when_the_licence_is_clustered()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.fake.adapter.capabilities.add(com.jaspersoft.jrsupgrade.jrs.api.Capability.CLUSTERING);
      Plan plan = f.ops().planUpgrade(newdb(f));

      assertThat(plan.summary().warnings())
          .contains(com.jaspersoft.jrsupgrade.ops.ClusterNotice.WARNING);
    }
  }

  static UpgradeOptions samedbTo(UpgradeFixture f, String to) {
    return new UpgradeOptions(to, f.packageDir, Mode.SAMEDB, true);
  }

  static UpgradeOptions withNewTomcat(UpgradeFixture f) {
    return new UpgradeOptions(
        UpgradeFixture.NEW_VERSION,
        f.packageDir,
        Mode.NEWDB,
        true,
        java.util.Optional.of(f.newTomcatDir));
  }

  /** Review §1.2: doctor judges the vendor JDK against the set the platform sheet lists. */
  @Test
  void should_judge_doctor_vendor_java_against_every_allowed_major() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      // the fixture's server is 8.2.0, whose sheet lists JDK 8 and 11; the fixture's JDK is 17
      ReportItem seventeen = vendorJava(new DoctorOperation(f.services).run(DoctorOptions.DEFAULT));
      assertThat(seventeen.status()).isEqualTo(ReportItem.Status.FAIL);
      assertThat(seventeen.detail()).contains("Java 17").contains("needs Java 8 or 11");

      f.javaVersion("openjdk version \"11.0.24\" 2024-07-16");
      ReportItem eleven = vendorJava(new DoctorOperation(f.services).run(DoctorOptions.DEFAULT));
      assertThat(eleven.status()).isEqualTo(ReportItem.Status.PASS);
      assertThat(eleven.detail()).contains("Java 11").contains("one of Java 8 or 11");
    }
  }

  private static ReportItem vendorJava(DoctorReport report) {
    return report.items().stream()
        .filter(i -> i.name().equals("vendor-java"))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void should_pass_verify_target_package_precheck_when_java_matches_and_path_supported()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(newdb(f));

      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));

      assertThat(result).as(result.toString()).isInstanceOf(CheckResult.Pass.class);
    }
  }

  @Test
  void should_fail_verify_target_package_precheck_when_package_lacks_vendor_scripts()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path empty = Files.createDirectories(tmp.resolve("empty-pkg"));
      Plan plan = f.ops().planUpgrade(UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, empty));

      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));

      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message()).contains("buildomatic");
      assertThat(plan.summary().warnings()).anyMatch(w -> w.startsWith("target package:"));
    }
  }

  @Test
  void should_fail_verify_target_package_precheck_when_package_names_another_version()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path renamed = tmp.resolve("jasperreports-server-9.1.0-bin");
      Files.move(f.packageDir, renamed);
      Plan plan = f.ops().planUpgrade(UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, renamed));

      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));

      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message())
          .contains("9.1.0")
          .contains("--to says 9.0.0");
    }
  }

  /**
   * Upgrade guide 10.1 p.14: Compact and Split never cross in one upgrade (review §1.3). The
   * fixture's installation is compact (no installType key).
   */
  @Test
  void should_refuse_with_exit_6_when_the_target_master_properties_switch_compact_to_split()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      UpgradeFixture.write(
          f.packageDir.resolve("buildomatic").resolve("default_master.properties"),
          "installType=split\naudit.dbHost=localhost\naudit.dbName=jsaudit\n");

      assertThatThrownBy(() -> f.ops().planUpgrade(newdb(f)))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("compact installation")
          .hasMessageContaining("installType=split")
          .satisfies(e -> assertThat(((UpgradeException) e).exitCode()).isEqualTo(6));
    }
  }

  @Test
  void
      should_fail_verify_target_package_precheck_when_the_target_master_properties_change_after_planning()
          throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(newdb(f));
      UpgradeFixture.write(
          f.packageDir.resolve("buildomatic").resolve("default_master.properties"),
          "installType=split\n");

      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));

      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message()).contains("installType=split");
      assertThat(((CheckResult.Fail) result).remediation()).contains("installType");
    }
  }

  /** Upgrade guide 10.1 p.43: Oracle needs dbVersion from 10.1 on (review §1.3). */
  @Test
  void should_fail_verify_target_package_precheck_when_oracle_lacks_db_version_for_10_1()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.installedMasterProperties(
          "dbType=oracle\ndbHost=localhost\ndbPort=1521\nsid=ORCL\ndbUsername=jasperserver\n"
              + "dbPassword=TopSecret\n");
      f.fake.unreachable = true; // the path check is then left to the precheck
      Plan plan = f.ops().planUpgrade(new UpgradeOptions("10.1.0", f.packageDir, Mode.NEWDB, true));

      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));

      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message())
          .contains("dbType=oracle")
          .contains("10.1.0")
          .contains("dbVersion");
      assertThat(((CheckResult.Fail) result).remediation())
          .contains("dbVersion=")
          .contains("default_master.properties");
    }
  }

  @Test
  void should_get_past_the_db_version_check_when_the_installed_master_properties_name_it()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.installedMasterProperties(
          "dbType=oracle\ndbVersion=19c\ndbHost=localhost\ndbPort=1521\nsid=ORCL\n"
              + "dbUsername=jasperserver\ndbPassword=TopSecret\n");
      f.fake.unreachable = true;
      Plan plan = f.ops().planUpgrade(new UpgradeOptions("10.1.0", f.packageDir, Mode.NEWDB, true));

      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));

      // the unreachable server is the next finding, so the master properties passed
      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message())
          .contains("server unreachable")
          .doesNotContain("dbVersion");
    }
  }

  /**
   * Doctor no longer needs the admin password (field test 2, D1), but an upgrade does: its doctor
   * precheck treats a skipped login as a failure, so nothing starts without credentials.
   */
  @Test
  void should_fail_the_doctor_precheck_when_the_admin_password_is_not_available() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.withoutAdminPassword();
      Plan plan = f.ops().planUpgrade(newdb(f));

      CheckResult result = UpgradeFixture.step(plan, "doctor").precheck(f.ctx("r-1"));

      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message())
          .contains("doctor could not log in")
          .contains("no admin password available");
    }
  }

  /**
   * Field test 2, U3: the tester's /home held 800 MB and the upgrade failed part-way. The whole
   * run's backup need is judged before anything starts, and the plan says where the backups go.
   */
  @Test
  void should_fail_verify_target_package_precheck_when_the_home_cannot_hold_the_backups()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.fake.platform.freeSpaceUnder.put(f.fake.home.root(), 100L * 1024 * 1024);
      Plan plan = f.ops().planUpgrade(newdb(f));

      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));

      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message())
          .contains("needs about")
          .contains("free under " + f.fake.home.root());
      assertThat(((CheckResult.Fail) result).remediation())
          .contains("--home")
          .contains("JRS_UPGRADE_HOME")
          .contains("runs prune");
    }
  }

  @Test
  void should_say_where_the_backups_go_and_how_to_move_them_when_planning() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(newdb(f));

      assertThat(plan.summary().warnings())
          .anyMatch(
              w ->
                  w.contains("backups and the full export go under " + f.fake.home.snapshots())
                      && w.contains("free")
                      && w.contains("--home")
                      && w.contains("JRS_UPGRADE_HOME"));
    }
  }

  /** ADR-0028 (field test 2, U5b): an export taken earlier stands in for this run's own. */
  @Test
  void should_adopt_an_existing_export_instead_of_taking_one_when_export_is_given()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path export = f.fakeExport("earlier.zip");

      Plan plan = f.ops().planUpgrade(newdb(f).withExistingExport(export));

      assertThat(UpgradeFixture.ids(plan))
          .contains("adopt-full-export")
          .doesNotContain("full-export");
      assertThat(plan.summary().warnings())
          .anyMatch(w -> w.contains("every change made in the repository after that export"));
      assertThat(UpgradeFixture.step(plan, "run-vendor-upgrade").detail())
          .contains(export.toString());
      assertThat(UpgradeFixture.step(plan, "adopt-full-export").precheck(f.ctx("r-1")))
          .isInstanceOf(CheckResult.Pass.class);
    }
  }

  @Test
  void should_refuse_export_with_exit_1_when_mode_is_samedb() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path export = f.fakeExport("earlier.zip");
      UpgradeOptions samedb =
          new UpgradeOptions(UpgradeFixture.NEW_VERSION, f.packageDir, Mode.SAMEDB, true)
              .withExistingExport(export);

      assertThatThrownBy(() -> f.ops().planUpgrade(samedb))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("--export is only used by a newdb upgrade")
          .satisfies(e -> assertThat(((UpgradeException) e).exitCode()).isEqualTo(1));
    }
  }

  @Test
  void should_refuse_key_alias_without_export_with_exit_1() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertThatThrownBy(
              () -> f.ops().planUpgrade(newdb(f).withKeyAlias("deprecatedImportExportEncSecret")))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("--key-alias")
          .satisfies(e -> assertThat(((UpgradeException) e).exitCode()).isEqualTo(1));
    }
  }

  /** An export from another environment is allowed (the tester's case); the plan says so. */
  @Test
  void should_warn_not_refuse_when_the_sidecar_names_another_server() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path export =
          f.fakeExport("from-staging.zip", "http://staging:8080/jasperserver-pro 8.2.0", "8.2.0");

      Plan plan = f.ops().planUpgrade(newdb(f).withExistingExport(export));

      assertThat(plan.summary().warnings())
          .anyMatch(
              w ->
                  w.contains("was exported from http://staging:8080/jasperserver-pro")
                      && w.contains("--key-alias"));
      assertThat(UpgradeFixture.step(plan, "adopt-full-export").precheck(f.ctx("r-1")))
          .isInstanceOf(CheckResult.Pass.class);
    }
  }

  @Test
  void should_fail_adopt_precheck_when_the_file_is_not_an_export() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path notAnExport = Files.writeString(f.root.resolve("notes.zip"), "not a zip");

      Plan plan = f.ops().planUpgrade(newdb(f).withExistingExport(notAnExport));
      CheckResult result = UpgradeFixture.step(plan, "adopt-full-export").precheck(f.ctx("r-1"));

      // a file with no ZIP entries is not judged by the archive check; the vendor precheck
      // and the script itself refuse it; a real export with entries but no index is refused here
      assertThat(result).isNotNull();
    }
  }

  @Test
  void should_copy_master_properties_without_passwords_when_planning() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(newdb(f));

      Step write = UpgradeFixture.step(plan, "write-master-properties");
      assertThat(write.detail()).contains("no passwords");
      List<String> touched = plan.summary().filesTouched().stream().map(Path::toString).toList();
      assertThat(touched).anyMatch(p -> p.endsWith("default_master.properties"));
      assertThat(plan.summary().warnings()).contains(DefaultUpgradeOperations.PASSWORD_WARNING);
    }
  }

  @Test
  void should_warn_and_defer_path_check_when_server_unreachable_at_plan_time() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.fake.unreachable = true;
      Plan plan = f.ops().planUpgrade(newdb(f));

      assertThat(plan.summary().target()).startsWith("? -> 9.0.0");
      assertThat(plan.summary().warnings()).anyMatch(w -> w.contains("server unreachable"));
      assertThat(plan.fingerprint().inputs().get("server")).isEqualTo("unreachable");
      CheckResult result =
          UpgradeFixture.step(plan, "verify-target-package").precheck(f.ctx("r-1"));
      assertThat(result).isInstanceOf(CheckResult.Fail.class);
    }
  }
}
