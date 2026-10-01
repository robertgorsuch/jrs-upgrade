package com.jaspersoft.jrsupgrade.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.RunOptions;
import com.jaspersoft.jrsupgrade.core.engine.RunOutcome;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.platform.ServiceController;
import com.jaspersoft.jrsupgrade.core.state.HotfixInstalled;
import com.jaspersoft.jrsupgrade.core.state.HotfixState;
import com.jaspersoft.jrsupgrade.jrs.api.KeystoreInfo;
import com.jaspersoft.jrsupgrade.ops.FakeJrsAdapter;
import com.jaspersoft.jrsupgrade.ops.Idempotency;
import com.jaspersoft.jrsupgrade.ops.customizations.DefaultCustomizationOperations;
import com.jaspersoft.jrsupgrade.ops.hotfix.HotfixPaths;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.Mode;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.RollbackOptions;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.RollbackPoint;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.UpgradeOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 8 idempotency of every upgrade and upgrade-rollback step (spec §6.1, §10): re-executing a
 * step after a complete first execution, and re-running a compensation, leave the install tree, the
 * target package, the backups, the run directory, the keystore, the store rows and the service as
 * one execution did. {@code default_master.properties} copies are compared without their date
 * comment (the vendor format writes one); audit rows are an append-only log and not part of the
 * compared state.
 */
class UpgradeStepIdempotencyTest {

  private static final String ORIGINAL = "a=1\nb=2\n";
  private static final String CUSTOMIZED = "a=1\nb=two\n";

  @TempDir Path tmp;

  // ---------------------------------------------------------------- helpers

  private static Optional<String> normalised(Path file) throws IOException {
    String name = file.getFileName().toString();
    return name.startsWith("default_master.properties")
        ? Idempotency.withoutComments(file)
        : Optional.empty();
  }

  private static Map<String, String> state(UpgradeFixture f, String runId) throws IOException {
    Map<String, String> m = new TreeMap<>();
    m.putAll(Idempotency.tree("install", f.installDir, UpgradeStepIdempotencyTest::normalised));
    m.putAll(Idempotency.tree("package", f.packageDir, UpgradeStepIdempotencyTest::normalised));
    m.putAll(Idempotency.tree("snapshots", f.fake.home.snapshots()));
    m.putAll(Idempotency.tree("runs", f.fake.home.runs(), UpgradeStepIdempotencyTest::normalised));
    m.putAll(Idempotency.tree("keystore", f.keystoreDir));
    m.put("service", f.fake.platform.serviceState.name());
    m.put("hotfixes", f.store().hotfixes().toString());
    m.put("customizations", f.store().customizations().toString());
    m.put("snapshot-rows", f.store().snapshots(runId).toString());
    return m;
  }

  private static UpgradeOptions newdb(UpgradeFixture f) {
    return UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, f.packageDir);
  }

  /** The backup phase restarts the server only in samedb mode (review §1.6). */
  private static UpgradeOptions samedb(UpgradeFixture f) {
    return new UpgradeOptions(UpgradeFixture.NEW_VERSION, f.packageDir, Mode.SAMEDB, true);
  }

  private static Context start(UpgradeFixture f, Plan plan, String runId) {
    f.store().recordRunStart(runId, plan.summary().operation(), Optional.empty(), Instant.EPOCH);
    return f.ctx(runId);
  }

  private static void assertReexecutionConverges(
      UpgradeFixture f, Plan plan, String runId, String stepId) throws IOException {
    Context ctx = start(f, plan, runId);
    Idempotency.runUpTo(plan, ctx, stepId, f.events::add);
    Map<String, String> once = state(f, runId);
    Idempotency.executeOk(Idempotency.step(plan, stepId), ctx, f.events::add);
    assertThat(state(f, runId)).as(String.join("\n", f.logs())).isEqualTo(once);
  }

  private static void assertCompensationConverges(
      UpgradeFixture f, Plan plan, String runId, String stepId) throws IOException {
    Context ctx = start(f, plan, runId);
    Idempotency.runAll(plan, ctx, f.events::add);
    Step step = Idempotency.step(plan, stepId);
    Idempotency.compensateOk(step, ctx, f.events::add);
    Map<String, String> once = state(f, runId);
    Idempotency.compensateOk(step, ctx, f.events::add);
    assertThat(state(f, runId)).as(String.join("\n", f.logs())).isEqualTo(once);
  }

  /**
   * A configuration file outside the webapp and buildomatic trees, so only restore-config owns it.
   */
  private static Path contextXml(UpgradeFixture f) {
    return f.tomcatDir
        .resolve("conf")
        .resolve("Catalina")
        .resolve("localhost")
        .resolve("jasperserver-pro.xml");
  }

  private static final String CHANGED_XML =
      "<Context docBase=\"jasperserver-pro\" changed=\"yes\"/>";

  private static HotfixInstalled installed(String id, String runId) {
    return new HotfixInstalled(
        id, "1", "old fix " + id, runId, Optional.empty(), HotfixState.INSTALLED, Instant.EPOCH);
  }

  private static UpgradeInput input(UpgradeFixture f) throws IOException {
    return new UpgradeInput(
        newdb(f),
        HotfixPaths.from(f.services.config(), f.services.platform()),
        "jasperserver-pro",
        TargetPackage.inspect(f.packageDir, f.runtime().locator()),
        Optional.of(FakeJrsAdapter.identity("8.2.0")),
        Map.of(),
        f.installDir.resolve("buildomatic"));
  }

  /** A successful upgrade run, followed by changes the operator would see after it. */
  private static Plan rollbackPlan(UpgradeFixture f) throws Exception {
    Plan up = f.ops().planUpgrade(newdb(f));
    assertThat(f.run(up, "r-up", RunOptions.DEFAULT))
        .as(String.join("\n", f.logs()))
        .isInstanceOf(RunOutcome.Succeeded.class);
    UpgradeFixture.write(contextXml(f), CHANGED_XML);
    UpgradeFixture.write(f.keystoreDir.resolve(".jrsks"), "keystore-changed-by-the-upgrade");
    f.fake.platform.controller.events.clear();
    return f.ops().planRollback("r-up", RollbackPoint.C);
  }

  private static Path registerCustomization(UpgradeFixture f, String name) throws Exception {
    Path file = f.webappDir.resolve("WEB-INF").resolve("classes").resolve(name);
    Path pristine = f.root.resolve("pristine-" + name);
    UpgradeFixture.write(pristine, ORIGINAL);
    UpgradeFixture.write(file, CUSTOMIZED);
    new DefaultCustomizationOperations(f.services, f.snapshots())
        .register(file, Optional.of(pristine));
    UpgradeFixture.write(file, ORIGINAL);
    return file;
  }

  // ---------------------------------------------------------------- upgrade plan

  /** Each read-only step is re-executed at its own position in the plan, as a resume would. */
  @Test
  void should_not_mutate_when_read_only_upgrade_steps_execute_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(samedb(f));
      Context ctx = start(f, plan, "r-ro");
      List<String> readOnly =
          List.of(
              "doctor",
              "verify-target-package",
              "verify-vendor-preconditions",
              "full-export-wait-for-server",
              "wait-for-server",
              "check-analytics-jndi",
              "smoke");
      List<String> seen = new ArrayList<>();
      for (Step step : plan.steps()) {
        Idempotency.executeOk(step, ctx, f.events::add);
        if (readOnly.contains(step.id())) {
          assertThat(step.mutating()).as(step.id()).isFalse();
          Map<String, String> before = state(f, "r-ro");
          Idempotency.executeOk(step, ctx, f.events::add);
          Idempotency.compensateOk(step, ctx, f.events::add);
          assertThat(state(f, "r-ro")).as(step.id()).isEqualTo(before);
          seen.add(step.id());
        }
      }
      assertThat(seen).containsExactlyInAnyOrderElementsOf(readOnly);
      Step confirm = new PreflightSteps.ConfirmDbBackup(f.runtime(), input(f));
      assertThat(confirm.mutating()).isFalse();
      Map<String, String> before = state(f, "r-ro");
      Idempotency.executeOk(confirm, ctx, f.events::add);
      Idempotency.executeOk(confirm, ctx, f.events::add);
      assertThat(state(f, "r-ro")).isEqualTo(before);
    }
  }

  @Test
  void should_reuse_the_export_when_full_export_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertReexecutionConverges(f, f.ops().planUpgrade(newdb(f)), "r-fe", "full-export");
      assertThat(f.logs()).anyMatch(m -> m.contains("reusing it"));
    }
  }

  /** ADR-0028: the record names the operator's export; rewriting it is the same as writing it. */
  @Test
  void should_rewrite_the_record_when_adopt_full_export_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path export = f.fakeExport("earlier.zip");
      Plan plan = f.ops().planUpgrade(newdb(f).withExistingExport(export));

      assertReexecutionConverges(f, plan, "r-ae", "adopt-full-export");

      SnapshotSet set = SnapshotSet.of(f.fake.home, "r-ae", f.os);
      assertThat(set.externalExport()).isPresent();
      assertThat(set.externalExport().orElseThrow().path()).isEqualTo(export);
      assertThat(set.resolveFullExport()).isEqualTo(export);
      assertThat(set.fullExport()).doesNotExist();
    }
  }

  @Test
  void should_converge_when_adopt_full_export_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path export = f.fakeExport("earlier.zip");
      Plan plan = f.ops().planUpgrade(newdb(f).withExistingExport(export));

      assertCompensationConverges(f, plan, "r-ac", "adopt-full-export");

      assertThat(SnapshotSet.of(f.fake.home, "r-ac", f.os).externalExport()).isEmpty();
      assertThat(export).exists();
    }
  }

  private static int count(String text, String token) {
    int n = 0;
    for (int i = text.indexOf(token); i >= 0; i = text.indexOf(token, i + token.length())) {
      n++;
    }
    return n;
  }

  private static Plan databaseRollback(UpgradeFixture f, String upgradeRunId) throws Exception {
    assertThat(f.run(f.ops().planUpgrade(newdb(f)), upgradeRunId, RunOptions.DEFAULT))
        .isInstanceOf(RunOutcome.Succeeded.class);
    return f.ops().planRollback(upgradeRunId, new RollbackOptions(RollbackPoint.B, true));
  }

  /** The rehearsal's validation is read-only: running it twice leaves everything as it was. */
  @Test
  void should_not_mutate_when_run_vendor_test_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planTest(newdb(f));
      Context ctx = start(f, plan, "r-vt");
      Idempotency.runUpTo(plan, ctx, "run-vendor-test", f.events::add);
      Step step = Idempotency.step(plan, "run-vendor-test");
      assertThat(step.mutating()).isFalse();
      Map<String, String> before = state(f, "r-vt");

      Idempotency.executeOk(step, ctx, f.events::add);
      Idempotency.compensateOk(step, ctx, f.events::add);

      assertThat(state(f, "r-vt")).isEqualTo(before);
    }
  }

  @Test
  void should_converge_when_unstage_target_package_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planTest(newdb(f));

      assertReexecutionConverges(f, plan, "r-ut", "unstage-target-package");

      assertThat(f.packageDir.resolve("buildomatic").resolve("default_master.properties"))
          .doesNotExist();
    }
  }

  @Test
  void should_converge_when_unstage_target_package_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertCompensationConverges(f, f.ops().planTest(newdb(f)), "r-utc", "unstage-target-package");
    }
  }

  /**
   * Issue #108: the dry run and the migration each run once; a resume after the marker runs neither
   * again, and a second js-ant migrate-passwords would only skip users already migrated.
   */
  @Test
  void should_migrate_once_when_migrate_passwords_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.fake.adapter.identity = com.jaspersoft.jrsupgrade.ops.FakeJrsAdapter.identity("10.0.0");
      // the fake vendor run deploys webapp-new; the target must be what --to says
      Files.writeString(f.packageDir.resolve("webapp-new").resolve("version.txt"), "10.1.0");
      // a 10.1 commercial target wants the licence in the user home, and the migration utility
      // its settings file in the target webapp (issue #108)
      Files.writeString(f.userHome.resolve("jaspersoft.jrs.license"), "lic");
      Files.writeString(
          f.packageDir
              .resolve("jasperserver-pro")
              .resolve("WEB-INF")
              .resolve("js.password-storage-config.properties"),
          "password.strategy=modern\n");
      Plan plan =
          f.ops()
              .planUpgrade(
                  new UpgradeOptions("10.1.0", f.packageDir, Mode.SAMEDB, true)
                      .withMigratePasswords(true));

      assertReexecutionConverges(f, plan, "r-pw", "migrate-passwords");

      assertThat(count(f.vendorLogText(), "migrate-passwords-dry-run")).isEqualTo(1);
      assertThat(count(f.vendorLogText(), "migrate-passwords")).isEqualTo(2);
      assertThat(f.vendorLogText().indexOf("migrate-passwords-dry-run"))
          .isLessThan(f.vendorLogText().lastIndexOf("migrate-passwords"));
    }
  }

  /** ADR-0029: a resume never drops a database it already rebuilt. */
  @Test
  void should_run_init_once_when_rebuild_database_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan rollback = databaseRollback(f, "r-up-rd");

      assertReexecutionConverges(f, rollback, "r-rb-rd", "rebuild-database");

      assertThat(count(f.vendorLogText(), "init-js-db-pro")).isEqualTo(1);
    }
  }

  @Test
  void should_converge_when_rebuild_database_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan rollback = databaseRollback(f, "r-up-rdc");

      assertCompensationConverges(f, rollback, "r-rb-rdc", "rebuild-database");

      assertThat(count(f.vendorLogText(), "init-js-db-pro")).isEqualTo(1);
    }
  }

  /** Issue #106: the events import runs the target's js-import once per run. */
  @Test
  void should_import_once_when_import_events_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(newdb(f).withIncludeEvents(true));

      assertReexecutionConverges(f, plan, "r-ev", "import-events");

      assertThat(count(f.vendorLogText(), "js-import --input-zip")).isEqualTo(1);
    }
  }

  @Test
  void should_import_once_when_reimport_full_export_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan rollback = databaseRollback(f, "r-up-ri");

      assertReexecutionConverges(f, rollback, "r-rb-ri", "reimport-full-export");

      assertThat(count(f.vendorLogText(), "js-import --input-zip")).isEqualTo(1);
    }
  }

  @Test
  void should_converge_when_reimport_full_export_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan rollback = databaseRollback(f, "r-up-ric");

      assertCompensationConverges(f, rollback, "r-rb-ric", "reimport-full-export");

      assertThat(count(f.vendorLogText(), "js-import --input-zip")).isEqualTo(1);
    }
  }

  @Test
  void should_converge_when_backup_keystore_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertReexecutionConverges(f, f.ops().planUpgrade(newdb(f)), "r-bk", "backup-keystore");
      assertThat(f.store().snapshots("r-bk")).hasSize(1);
    }
  }

  @Test
  void should_reuse_the_archives_when_backup_webapp_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertReexecutionConverges(f, f.ops().planUpgrade(newdb(f)), "r-bw", "backup-webapp");
      assertThat(f.logs()).anyMatch(m -> m.contains("already present and verified"));
    }
  }

  @Test
  void should_converge_when_backup_config_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertReexecutionConverges(f, f.ops().planUpgrade(newdb(f)), "r-bc", "backup-config");
    }
  }

  /**
   * {@code MasterPropertiesTest#should_keep_pristine_backup_when_staged_twice} covers the file op.
   */
  @Test
  void should_keep_the_pristine_backup_when_write_master_properties_executes_twice()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path target = f.packageDir.resolve("buildomatic").resolve("default_master.properties");
      UpgradeFixture.write(target, "pristine=yes\n");
      assertReexecutionConverges(
          f, f.ops().planUpgrade(newdb(f)), "r-wm", "write-master-properties");
      assertThat(f.fake.home.runDir("r-wm").resolve("default_master.properties.bak"))
          .hasContent("pristine=yes\n");
      assertThat(UpgradeFixture.read(target)).contains("pristine=yes").contains("dbHost=localhost");
    }
  }

  @Test
  void should_converge_when_write_master_properties_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path target = f.packageDir.resolve("buildomatic").resolve("default_master.properties");
      UpgradeFixture.write(target, "pristine=yes\n");
      Plan plan = f.ops().planUpgrade(newdb(f));
      Context ctx = start(f, plan, "r-wm-c");
      Idempotency.runUpTo(plan, ctx, "write-master-properties");
      Step step = Idempotency.step(plan, "write-master-properties");
      Idempotency.compensateOk(step, ctx);
      Map<String, String> once = state(f, "r-wm-c");
      Idempotency.compensateOk(step, ctx);
      assertThat(state(f, "r-wm-c")).isEqualTo(once);
      assertThat(target).hasContent("pristine=yes\n");
    }
  }

  /**
   * Review §1.4: buildomatic finds the keystore through {@code keystore.init.properties}; a fresh
   * target package has none, and the vendor scripts then create a new keystore (setup.xml {@code
   * create-ks}) and the repository's passwords become undecryptable.
   */
  @Test
  void
      should_point_the_target_buildomatic_at_the_server_keystore_when_stage_keystore_init_executes_twice()
          throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path target = f.packageDir.resolve("buildomatic").resolve("keystore.init.properties");
      assertReexecutionConverges(f, f.ops().planUpgrade(newdb(f)), "r-ksi", "stage-keystore-init");
      Properties written = load(target);
      assertThat(Path.of(written.getProperty("ks"))).isEqualTo(f.keystoreDir);
      assertThat(Path.of(written.getProperty("ksp"))).isEqualTo(f.keystoreDir);
      // no Properties.store timestamp: a re-execution in a later second must write the same bytes
      assertThat(Files.readAllLines(target, StandardCharsets.ISO_8859_1))
          .allMatch(line -> line.startsWith("ks=") || line.startsWith("ksp="));
      assertThat(f.fake.home.runDir("r-ksi").resolve("keystore.init.properties.bak"))
          .doesNotExist();
    }
  }

  @Test
  void should_copy_the_installed_keystore_init_properties_verbatim_when_the_installation_has_one()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      UpgradeFixture.write(
          f.installDir.resolve("buildomatic").resolve("keystore.init.properties"),
          "#installer\nks=/srv/jrs/home\nksp=/srv/jrs/home\n");
      Path target = f.packageDir.resolve("buildomatic").resolve("keystore.init.properties");
      assertReexecutionConverges(
          f, f.ops().planUpgrade(newdb(f)), "r-ksi-copy", "stage-keystore-init");
      assertThat(target).hasContent("#installer\nks=/srv/jrs/home\nksp=/srv/jrs/home\n");
    }
  }

  @Test
  void should_converge_when_stage_keystore_init_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path target = f.packageDir.resolve("buildomatic").resolve("keystore.init.properties");
      UpgradeFixture.write(target, "ks=pristine\n");
      Plan plan = f.ops().planUpgrade(newdb(f));
      Context ctx = start(f, plan, "r-ksi-c");
      Idempotency.runUpTo(plan, ctx, "stage-keystore-init");
      assertThat(UpgradeFixture.read(target)).doesNotContain("pristine");
      Step step = Idempotency.step(plan, "stage-keystore-init");
      Idempotency.compensateOk(step, ctx);
      Map<String, String> once = state(f, "r-ksi-c");
      Idempotency.compensateOk(step, ctx);
      assertThat(state(f, "r-ksi-c")).isEqualTo(once);
      assertThat(target).hasContent("ks=pristine\n");
    }
  }

  @Test
  void should_remove_the_staged_file_when_stage_keystore_init_compensates_and_there_was_none()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path target = f.packageDir.resolve("buildomatic").resolve("keystore.init.properties");
      Plan plan = f.ops().planUpgrade(newdb(f));
      Context ctx = start(f, plan, "r-ksi-rm");
      Idempotency.runUpTo(plan, ctx, "stage-keystore-init");
      assertThat(target).exists();
      Step step = Idempotency.step(plan, "stage-keystore-init");
      Idempotency.compensateOk(step, ctx);
      Idempotency.compensateOk(step, ctx);
      assertThat(target).doesNotExist();
    }
  }

  @Test
  void should_fail_stage_keystore_init_precheck_when_no_keystore_location_is_known()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.fake.adapter.keystore = KeystoreInfo.absent("no .jrsks under the run-as user's home");
      Plan plan = f.ops().planUpgrade(newdb(f));
      CheckResult result =
          Idempotency.step(plan, "stage-keystore-init").precheck(f.ctx("r-ksi-none"));
      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message()).contains("new keystore");
    }
  }

  private static Properties load(Path file) throws IOException {
    Properties p = new Properties();
    try (var in = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
      p.load(in);
    }
    return p;
  }

  /**
   * Upgrade guide 10.1 p.34: clear {@code <tomcat>/work} and {@code <tomcat>/temp} before starting.
   */
  @Test
  void should_empty_work_and_temp_when_clear_tomcat_caches_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path work = f.tomcatDir.resolve("work");
      Path temp = f.tomcatDir.resolve("temp");
      UpgradeFixture.write(
          work.resolve("Catalina")
              .resolve("localhost")
              .resolve("jasperserver-pro")
              .resolve("x.class"),
          "compiled");
      UpgradeFixture.write(temp.resolve("y.tmp"), "temp");
      assertReexecutionConverges(f, f.ops().planUpgrade(newdb(f)), "r-ctc", "clear-tomcat-caches");
      assertThat(work).isDirectory().isEmptyDirectory();
      assertThat(temp).isDirectory().isEmptyDirectory();
    }
  }

  /**
   * Upgrade guide 10.1 p.35: the repository cache is cleared with two statements; both are
   * idempotent, so a re-execution sends them again and changes nothing.
   */
  @Test
  void should_send_the_vendor_cache_sql_when_clear_repository_cache_executes_twice()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.createWithDatabase(tmp)) {
      assertReexecutionConverges(
          f, f.ops().planUpgrade(newdb(f)), "r-crc", "clear-repository-cache");
      assertThat(f.jdbc.executed)
          .containsExactly(
              PostUpgradeSteps.CACHE_UPDATE,
              PostUpgradeSteps.CACHE_DELETE,
              PostUpgradeSteps.CACHE_UPDATE,
              PostUpgradeSteps.CACHE_DELETE);
    }
  }

  @Test
  void should_warn_with_the_sql_when_clear_repository_cache_has_no_database_configured()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertReexecutionConverges(
          f, f.ops().planUpgrade(newdb(f)), "r-crc-nodb", "clear-repository-cache");
      assertThat(f.jdbc.executed).isEmpty();
      assertThat(f.logs()).anyMatch(m -> m.contains("JIRepositoryCache"));
    }
  }

  @Test
  void should_replace_the_copy_when_copy_webapp_to_tomcat_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.createWithManualService(tmp)) {
      Path copied = f.newTomcatDir.resolve("webapps").resolve("jasperserver-pro");
      assertReexecutionConverges(
          f,
          f.ops().planUpgrade(UpgradePlanTest.withNewTomcat(f)),
          "r-cw",
          "copy-webapp-to-tomcat");
      assertThat(copied.resolve("WEB-INF")).isDirectory();
      assertThat(UpgradeFixture.read(copied.resolve("version.txt")))
          .isEqualTo(UpgradeFixture.OLD_VERSION);
      assertThat(f.webappDir.resolve("version.txt")).exists();
    }
  }

  @Test
  void should_remove_the_copy_when_copy_webapp_to_tomcat_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.createWithManualService(tmp)) {
      Path copied = f.newTomcatDir.resolve("webapps").resolve("jasperserver-pro");
      Plan plan = f.ops().planUpgrade(UpgradePlanTest.withNewTomcat(f));
      Context ctx = start(f, plan, "r-cw-c");
      Idempotency.runUpTo(plan, ctx, "copy-webapp-to-tomcat");
      assertThat(copied).isDirectory();
      Step step = Idempotency.step(plan, "copy-webapp-to-tomcat");
      Idempotency.compensateOk(step, ctx);
      Idempotency.compensateOk(step, ctx);
      assertThat(copied).doesNotExist();
      assertThat(f.webappDir.resolve("version.txt")).exists();
    }
  }

  @Test
  void should_run_the_vendor_script_once_when_run_vendor_upgrade_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertReexecutionConverges(f, f.ops().planUpgrade(newdb(f)), "r-vu", "run-vendor-upgrade");
      assertThat(UpgradeFixture.read(f.vendorLog).strip().lines()).hasSize(1);
      assertThat(f.logs()).anyMatch(m -> m.contains("already completed in this run"));
    }
  }

  @Test
  void should_refuse_to_rerun_the_vendor_script_when_the_previous_attempt_never_reported_back()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(newdb(f));
      Context ctx = start(f, plan, "r-vu-crash");
      Idempotency.runUpTo(plan, ctx, "run-vendor-upgrade", f.events::add);
      Step step = Idempotency.step(plan, "run-vendor-upgrade");
      Idempotency.executeOk(step, ctx, f.events::add);
      // A power cut between the vendor script starting and its result being journalled leaves the
      // attempt marker without the done marker; js-upgrade-samedb cannot be run again blindly.
      Path runDir = f.fake.home.runDir("r-vu-crash");
      Files.delete(runDir.resolve(VendorSteps.DONE_MARKER));
      assertThat(runDir.resolve(VendorSteps.ATTEMPT_MARKER)).exists();

      StepResult again = step.execute(ctx, f.events::add);

      assertThat(again).isInstanceOf(StepResult.Failed.class);
      assertThat(again.toString())
          .contains("never reported back")
          .contains(VendorSteps.ATTEMPT_MARKER);
      assertThat(UpgradeFixture.read(f.vendorLog).strip().lines()).hasSize(1);
    }
  }

  @Test
  void should_converge_when_run_vendor_upgrade_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      String oldHash = f.sha(f.webappDir.resolve("scripts").resolve("app.js"));
      Plan plan = f.ops().planUpgrade(newdb(f));
      Context ctx = start(f, plan, "r-vu-c");
      Idempotency.runUpTo(plan, ctx, "run-vendor-upgrade");
      Step step = Idempotency.step(plan, "run-vendor-upgrade");
      Idempotency.compensateOk(step, ctx);
      Map<String, String> once = state(f, "r-vu-c");
      Idempotency.compensateOk(step, ctx);
      assertThat(state(f, "r-vu-c")).isEqualTo(once);
      assertThat(f.sha(f.webappDir.resolve("scripts").resolve("app.js"))).isEqualTo(oldHash);
      assertThat(UpgradeFixture.read(f.webappDir.resolve("version.txt")))
          .isEqualTo(UpgradeFixture.OLD_VERSION);
    }
  }

  @Test
  void should_stop_once_when_stop_service_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertReexecutionConverges(
          f, f.ops().planUpgrade(samedb(f)), "r-stop", "full-export-stop-service");
      assertThat(f.fake.platform.controller.events).containsExactly("stop");
      assertThat(f.fake.home.runDir("r-stop").resolve("full-export-stop-service.stopped")).exists();
    }
  }

  @Test
  void should_start_once_when_stop_service_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(samedb(f));
      Context ctx = start(f, plan, "r-stop-c");
      Idempotency.runUpTo(plan, ctx, "full-export-stop-service");
      Step stop = Idempotency.step(plan, "full-export-stop-service");
      Idempotency.compensateOk(stop, ctx);
      Idempotency.compensateOk(stop, ctx);
      assertThat(f.fake.platform.controller.events).containsExactly("stop", "start");
      assertThat(f.fake.platform.serviceState).isEqualTo(ServiceController.State.RUNNING);
      assertThat(f.fake.home.runDir("r-stop-c").resolve("full-export-stop-service.stopped"))
          .doesNotExist();
    }
  }

  @Test
  void should_start_once_when_start_service_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertReexecutionConverges(
          f, f.ops().planUpgrade(samedb(f)), "r-start", "full-export-start-service");
      assertThat(f.fake.platform.controller.events).containsExactly("stop", "start");
    }
  }

  @Test
  void should_stop_once_when_start_service_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(samedb(f));
      Context ctx = start(f, plan, "r-start-c");
      Idempotency.runUpTo(plan, ctx, "full-export-start-service");
      Step startStep = Idempotency.step(plan, "full-export-start-service");
      Idempotency.compensateOk(startStep, ctx);
      Idempotency.compensateOk(startStep, ctx);
      assertThat(f.fake.platform.controller.events).containsExactly("stop", "start", "stop");
    }
  }

  @Test
  void should_converge_when_plan_customization_reapply_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path file = registerCustomization(f, "custom.properties");
      Plan plan = f.ops().planUpgrade(newdb(f));
      Step step = Idempotency.step(plan, "plan-customization-reapply");
      Context ctx = f.ctx("r-cr");
      Idempotency.executeOk(step, ctx, f.events::add);
      Map<String, String> once = state(f, "r-cr");

      Idempotency.executeOk(step, ctx, f.events::add);

      assertThat(state(f, "r-cr")).isEqualTo(once);
      assertThat(UpgradeFixture.read(file)).isEqualTo(CUSTOMIZED);
      assertThat(f.logs()).anyMatch(m -> m.contains("already in place"));
    }
  }

  /**
   * A crash after the snapshot and the first copy leaves the second file un-customised; the
   * re-execution copies it, and the compensation must still restore both upgraded files.
   */
  @Test
  void
      should_restore_every_file_when_plan_customization_reapply_compensates_after_a_partial_re_execution()
          throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path first = registerCustomization(f, "first.properties");
      Path second = registerCustomization(f, "second.properties");
      Plan plan = f.ops().planUpgrade(newdb(f));
      Step step = Idempotency.step(plan, "plan-customization-reapply");
      Context ctx = f.ctx("r-cr-partial");
      Idempotency.executeOk(step, ctx);
      // the process died after copying the first file: the second is still the upgraded one
      UpgradeFixture.write(second, ORIGINAL);

      Idempotency.executeOk(step, ctx);
      assertThat(UpgradeFixture.read(first)).isEqualTo(CUSTOMIZED);
      assertThat(UpgradeFixture.read(second)).isEqualTo(CUSTOMIZED);

      Idempotency.compensateOk(step, ctx);

      assertThat(UpgradeFixture.read(first)).as("first file restored").isEqualTo(ORIGINAL);
      assertThat(UpgradeFixture.read(second)).as("second file restored").isEqualTo(ORIGINAL);
    }
  }

  @Test
  void should_converge_when_plan_customization_reapply_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path file = registerCustomization(f, "custom.properties");
      Plan plan = f.ops().planUpgrade(newdb(f));
      Step step = Idempotency.step(plan, "plan-customization-reapply");
      Context ctx = f.ctx("r-cr-c");
      Idempotency.executeOk(step, ctx);
      Idempotency.compensateOk(step, ctx);
      Map<String, String> once = state(f, "r-cr-c");

      Idempotency.compensateOk(step, ctx);

      assertThat(state(f, "r-cr-c")).isEqualTo(once);
      assertThat(UpgradeFixture.read(file)).isEqualTo(ORIGINAL);
    }
  }

  /**
   * The second execution finds no INSTALLED hotfix left (the first one superseded them all) and
   * must not lose the recorded list, or the compensation could no longer put the states back.
   */
  @Test
  void should_keep_the_superseded_list_when_record_upgrade_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.store().recordHotfixInstalled(installed("HF-OLD", "r-old"), List.of());
      Plan plan = f.ops().planUpgrade(newdb(f));
      Context ctx = start(f, plan, "r-ru");
      Idempotency.runAll(plan, ctx);
      Map<String, String> once = state(f, "r-ru");
      Step step = Idempotency.step(plan, "record-upgrade");

      Idempotency.executeOk(step, ctx);

      assertThat(state(f, "r-ru")).isEqualTo(once);
      assertThat(f.fake.home.runDir("r-ru").resolve("record-upgrade.superseded"))
          .hasContent("HF-OLD");
      Idempotency.compensateOk(step, ctx);
      assertThat(f.store().hotfix("HF-OLD").orElseThrow().state()).isEqualTo(HotfixState.INSTALLED);
    }
  }

  @Test
  void should_converge_when_record_upgrade_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.store().recordHotfixInstalled(installed("HF-OLD", "r-old"), List.of());
      assertCompensationConverges(f, f.ops().planUpgrade(newdb(f)), "r-ru-c", "record-upgrade");
      assertThat(f.store().hotfix("HF-OLD").orElseThrow().state()).isEqualTo(HotfixState.INSTALLED);
    }
  }

  @Test
  void should_keep_the_pre_upgrade_copy_when_point_config_at_target_executes_twice()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      String original = UpgradeFixture.read(f.fake.home.configFile());
      Plan plan = f.ops().planUpgrade(newdb(f));
      Context ctx = start(f, plan, "r-pc");
      Idempotency.runAll(plan, ctx, f.events::add);
      String once = UpgradeFixture.read(f.fake.home.configFile());
      Step step = Idempotency.step(plan, "point-config-at-target");

      Idempotency.executeOk(step, ctx, f.events::add);

      assertThat(UpgradeFixture.read(f.fake.home.configFile())).isEqualTo(once);
      assertThat(once).isNotEqualTo(original);
      assertThat(SnapshotSet.of(f.fake.home, "r-pc", f.os).dir().resolve("jrs-upgrade-config.yaml"))
          .hasContent(original);
    }
  }

  @Test
  void should_converge_when_point_config_at_target_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      String original = UpgradeFixture.read(f.fake.home.configFile());
      Plan plan = f.ops().planUpgrade(newdb(f));
      Context ctx = start(f, plan, "r-pc-c");
      Idempotency.runAll(plan, ctx, f.events::add);
      Step step = Idempotency.step(plan, "point-config-at-target");

      Idempotency.compensateOk(step, ctx, f.events::add);
      Idempotency.compensateOk(step, ctx, f.events::add);

      assertThat(UpgradeFixture.read(f.fake.home.configFile())).isEqualTo(original);
    }
  }

  // ---------------------------------------------------------------- rollback plan

  @Test
  void should_converge_when_restore_jrsupgrade_config_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      String original = UpgradeFixture.read(f.fake.home.configFile());
      Plan plan = rollbackPlan(f);
      Context ctx = start(f, plan, "r-rjc");
      Idempotency.runUpTo(plan, ctx, "restore-jrs-upgrade-config", f.events::add);

      Idempotency.executeOk(
          Idempotency.step(plan, "restore-jrs-upgrade-config"), ctx, f.events::add);

      assertThat(UpgradeFixture.read(f.fake.home.configFile())).isEqualTo(original);
    }
  }

  @Test
  void should_converge_when_restore_jrsupgrade_config_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = rollbackPlan(f);
      String upgraded = UpgradeFixture.read(f.fake.home.configFile());
      Context ctx = start(f, plan, "r-rjc-c");
      Idempotency.runAll(plan, ctx, f.events::add);
      Step step = Idempotency.step(plan, "restore-jrs-upgrade-config");

      Idempotency.compensateOk(step, ctx, f.events::add);
      Idempotency.compensateOk(step, ctx, f.events::add);

      assertThat(UpgradeFixture.read(f.fake.home.configFile())).isEqualTo(upgraded);
    }
  }

  @Test
  void should_converge_when_restore_webapp_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertReexecutionConverges(f, rollbackPlan(f), "r-rb", "restore-webapp");
      assertThat(UpgradeFixture.read(f.webappDir.resolve("version.txt")))
          .isEqualTo(UpgradeFixture.OLD_VERSION);
      assertThat(f.fake.home.runDir("r-rb").resolve("aside").resolve("jasperserver-pro"))
          .isDirectory();
    }
  }

  @Test
  void should_converge_when_restore_webapp_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertCompensationConverges(f, rollbackPlan(f), "r-rb-c", "restore-webapp");
      assertThat(UpgradeFixture.read(f.webappDir.resolve("version.txt")))
          .as("the upgraded tree is back in place")
          .isEqualTo(UpgradeFixture.NEW_VERSION);
    }
  }

  @Test
  void should_converge_when_restore_buildomatic_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertReexecutionConverges(f, rollbackPlan(f), "r-rbb", "restore-buildomatic");
    }
  }

  /**
   * The pre-restore snapshot must keep the post-upgrade file from the first execution, so that
   * compensation after a re-execution still brings the upgraded file back.
   */
  @Test
  void should_keep_the_pre_restore_snapshot_when_restore_config_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan back = rollbackPlan(f);
      assertReexecutionConverges(f, back, "r-rc", "restore-config");
      assertThat(UpgradeFixture.read(contextXml(f))).doesNotContain("changed");

      Idempotency.compensateOk(Idempotency.step(back, "restore-config"), f.ctx("r-rc"));

      assertThat(UpgradeFixture.read(contextXml(f))).isEqualTo(CHANGED_XML);
    }
  }

  @Test
  void should_converge_when_restore_config_compensates_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertCompensationConverges(f, rollbackPlan(f), "r-rc-c", "restore-config");
      assertThat(UpgradeFixture.read(contextXml(f))).isEqualTo(CHANGED_XML);
    }
  }

  @Test
  void should_keep_the_pre_restore_snapshot_when_restore_keystore_executes_twice()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path keystore = f.keystoreDir.resolve(".jrsks");
      Plan back = rollbackPlan(f);
      assertReexecutionConverges(f, back, "r-rk", "restore-keystore");
      assertThat(UpgradeFixture.read(keystore)).isEqualTo("keystore-bytes");

      Idempotency.compensateOk(Idempotency.step(back, "restore-keystore"), f.ctx("r-rk"));

      assertThat(UpgradeFixture.read(keystore)).isEqualTo("keystore-changed-by-the-upgrade");
    }
  }

  @Test
  void should_converge_when_record_rollback_executes_twice() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertReexecutionConverges(f, rollbackPlan(f), "r-rr", "record-rollback");
      assertThat(
              f.store().auditRows(50).stream()
                  .filter(a -> a.action().equals("upgrade.rolled-back")))
          .as("audit is an append-only log; a re-executed record step appends again")
          .hasSize(2);
    }
  }
}
