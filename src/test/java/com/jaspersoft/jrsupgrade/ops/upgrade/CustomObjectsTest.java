package com.jaspersoft.jrsupgrade.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.RunOptions;
import com.jaspersoft.jrsupgrade.core.engine.RunOutcome;
import com.jaspersoft.jrsupgrade.ops.db.DbObject;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.Mode;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.UpgradeOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #3: NGRA keeps {@code report_log} and {@code report_log_seq} in the repository database,
 * which {@code js-upgrade-newdb} drops and recreates.
 */
class CustomObjectsTest {

  static final String CUSTOM_TABLE = "CREATE TABLE report_log (id INTEGER NOT NULL)";

  @TempDir Path tmp;

  /** The installed buildomatic's DDL and a schema with one customer table and one sequence. */
  static void ngraSchema(UpgradeFixture f) throws Exception {
    UpgradeFixture.write(
        f.installDir.resolve("buildomatic/install_resources/sql/postgresql/js-pro-create.ddl"),
        "create table JIResource (id int8 not null);\n"
            + "create table \"public\".JIUser (id int8 not null);\n"
            + "create sequence hibernate_sequence;\n");
    f.jdbc.objects.addAll(
        List.of(
            new DbObject(DbObject.Kind.TABLE, "jiresource"),
            new DbObject(DbObject.Kind.TABLE, "jiuser"),
            new DbObject(DbObject.Kind.SEQUENCE, "hibernate_sequence"),
            new DbObject(DbObject.Kind.TABLE, "report_log"),
            new DbObject(DbObject.Kind.SEQUENCE, "report_log_seq")));
  }

  static Path customDdl(UpgradeFixture f) throws Exception {
    Path dir = Files.createDirectories(f.root.resolve("custom-ddl"));
    UpgradeFixture.write(dir.resolve("01-report_log.sql"), CUSTOM_TABLE + ";\n");
    UpgradeFixture.write(dir.resolve("02-seq.sql"), "CREATE SEQUENCE report_log_seq;\n");
    return dir;
  }

  @Test
  void should_tell_vendor_tables_from_customer_ones_by_the_installed_ddl() throws Exception {
    Path dir = Files.createDirectories(tmp.resolve("sql"));
    UpgradeFixture.write(
        dir.resolve("js-pro-create.ddl"),
        "CREATE TABLE IF NOT EXISTS \"JIResource\" (id int);\ncreate sequence hibernate_sequence;");
    UpgradeFixture.write(dir.resolve("upgrade-9.0-10.0.sql"), "create table JINewIn10 (id int);");

    Set<String> vendor = ForeignObjects.vendorNames(dir).orElseThrow();

    assertThat(vendor).containsExactlyInAnyOrder("jiresource", "hibernate_sequence", "jinewin10");
    assertThat(
            ForeignObjects.foreign(
                List.of(
                    new DbObject(DbObject.Kind.TABLE, "JIRESOURCE"),
                    new DbObject(DbObject.Kind.TABLE, "report_log")),
                vendor))
        .containsExactly(new DbObject(DbObject.Kind.TABLE, "report_log"));
    assertThat(ForeignObjects.vendorNames(tmp.resolve("absent"))).isEmpty();
  }

  @Test
  void should_name_the_customer_tables_and_dump_their_structure_when_newdb_drops_them()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.createWithDatabase(tmp)) {
      ngraSchema(f);
      Plan plan =
          f.ops().planUpgrade(UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, f.packageDir));

      assertThat(plan.summary().warnings())
          .anySatisfy(
              w ->
                  assertThat(w)
                      .contains("table report_log, sequence report_log_seq")
                      .contains("--custom-ddl")
                      .doesNotContain("jiresource"));
      assertThat(UpgradeFixture.ids(plan))
          .containsSubsequence("full-export", "dump-foreign-schema", "run-vendor-upgrade");

      RunOutcome outcome = f.run(plan, "r-foreign", RunOptions.DEFAULT);

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
      String dump =
          Files.readString(
              f.fake.home.snapshots().resolve("r-foreign").resolve("foreign-objects.sql"));
      assertThat(dump)
          .contains("CREATE TABLE report_log")
          .contains("CREATE SEQUENCE report_log_seq")
          .doesNotContain("jiresource");
    }
  }

  @Test
  void should_run_the_custom_ddl_after_the_vendor_script_and_before_the_server_starts()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.createWithDatabase(tmp)) {
      ngraSchema(f);
      Plan plan =
          f.ops()
              .planUpgrade(
                  UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, f.packageDir)
                      .withCustomDdl(customDdl(f)));

      assertThat(UpgradeFixture.ids(plan))
          .containsSubsequence("run-vendor-upgrade", "apply-custom-ddl", "start-service");
      assertThat(plan.summary().warnings())
          .anySatisfy(w -> assertThat(w).contains("--custom-ddl re-creates them"));

      RunOutcome outcome = f.run(plan, "r-ddl", RunOptions.DEFAULT);

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.jdbc.executed)
          .containsSubsequence(CUSTOM_TABLE, "CREATE SEQUENCE report_log_seq");
    }
  }

  @Test
  void should_leave_samedb_alone_and_refuse_custom_ddl_with_exit_1_there() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.createWithDatabase(tmp)) {
      ngraSchema(f);
      UpgradeOptions samedb =
          new UpgradeOptions(UpgradeFixture.NEW_VERSION, f.packageDir, Mode.SAMEDB, true);

      Plan plan = f.ops().planUpgrade(samedb);

      assertThat(UpgradeFixture.ids(plan))
          .doesNotContain("dump-foreign-schema", "apply-custom-ddl");
      assertThat(plan.summary().warnings()).noneMatch(w -> w.contains("report_log"));
      Path ddl = customDdl(f);
      assertThatThrownBy(() -> f.ops().planUpgrade(samedb.withCustomDdl(ddl)))
          .isInstanceOf(UpgradeException.class)
          .satisfies(e -> assertThat(((UpgradeException) e).exitCode()).isEqualTo(1));
    }
  }

  @Test
  void should_say_customer_tables_cannot_be_named_when_no_database_is_configured()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan =
          f.ops().planUpgrade(UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, f.packageDir));

      assertThat(plan.summary().warnings())
          .anySatisfy(w -> assertThat(w).contains("customer tables included"));
    }
  }
}
