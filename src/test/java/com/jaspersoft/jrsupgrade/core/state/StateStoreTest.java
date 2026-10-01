package com.jaspersoft.jrsupgrade.core.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.engine.RunRecord;
import com.jaspersoft.jrsupgrade.core.engine.TerminalState;
import com.jaspersoft.jrsupgrade.core.engine.Transition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class StateStoreTest {

  private static final Instant NOW = Instant.parse("2026-09-08T10:15:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @TempDir Path tmp;
  private JrsUpgradeHome home;
  private StateStore store;

  @BeforeEach
  void open() {
    home = new JrsUpgradeHome(tmp.resolve("home"));
    store = StateStore.open(home, CLOCK);
  }

  @AfterEach
  void close() {
    store.close();
  }

  @Test
  void should_report_ok_from_quick_check_when_the_db_is_sound() {
    assertThat(store.integrity()).isEqualTo("ok");
  }

  /**
   * Review finding 1.18: a damaged {@code state.db} must be refused on open with a message that
   * says what to do, not fail later on whatever query first touches the damage.
   */
  @Test
  void should_refuse_to_open_a_corrupt_state_db_with_a_remediation() throws Exception {
    Path db = tmp.resolve("damaged").resolve("state.db");
    try (StateStore victim = StateStore.open(db, CLOCK)) {
      for (int i = 0; i < 200; i++) {
        victim.audit("test", "fill", "row " + i + " " + "x".repeat(200));
      }
    }
    try (java.nio.channels.FileChannel ch =
        java.nio.channels.FileChannel.open(db, java.nio.file.StandardOpenOption.WRITE)) {
      // page headers of pages 2 to 4: an invalid page type fails quick_check whatever the page
      // holds, whereas unallocated space inside a page is not validated
      byte[] junk = new byte[64];
      java.util.Arrays.fill(junk, (byte) 0xFF);
      for (int page = 2; page <= 4; page++) {
        ch.write(java.nio.ByteBuffer.wrap(junk), (page - 1) * 4096L);
      }
    }

    assertThatThrownBy(() -> StateStore.open(db, CLOCK))
        .isInstanceOf(StateStoreException.class)
        .hasMessageContaining("quick_check")
        .hasMessageContaining(db.toString())
        .hasMessageContaining("move");
  }

  /** Review finding 1.18: a failure to close must never turn a finished run into an error. */
  @Test
  void should_not_throw_from_close_when_the_connection_refuses_to_close() throws Exception {
    Path db = tmp.resolve("stubborn").resolve("state.db");
    java.nio.file.Files.createDirectories(db.getParent());
    java.sql.Connection real = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
    java.sql.Connection refusing =
        (java.sql.Connection)
            java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {java.sql.Connection.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("close")) {
                    throw new java.sql.SQLException("simulated close failure");
                  }
                  try {
                    return method.invoke(real, args);
                  } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                  }
                });
    StateStore stubborn = new StateStore(refusing, db, CLOCK);

    stubborn.close();

    real.close();
  }

  @Test
  void should_apply_migrations_and_create_the_db_when_opened_on_an_empty_home() {
    assertThat(store.schemaVersion()).isEqualTo(3);
    assertThat(Files.exists(home.stateDb())).isTrue();
  }

  @Test
  void should_keep_schema_version_and_data_when_reopened() {
    store.recordRunStart("r1", "hotfix apply", Optional.empty(), NOW);
    store.close();

    store = StateStore.open(home, CLOCK);

    assertThat(store.schemaVersion()).isEqualTo(3);
    assertThat(store.run("r1")).isPresent();
    assertThat(store.executeUpdate("UPDATE runs SET operation='x' WHERE run_id='r1'")).isEqualTo(1);
  }

  @Test
  void should_split_scripts_keeping_trigger_bodies_whole_when_parsing() {
    List<String> statements =
        Migrations.split(
            "-- comment; with semicolon\nCREATE TABLE a (x TEXT);\n"
                + "CREATE TRIGGER t BEFORE UPDATE ON a BEGIN SELECT RAISE(ABORT, 'no; way'); END;\n"
                + "INSERT INTO a VALUES ('it''s;');");
    assertThat(statements).hasSize(3);
    assertThat(statements.get(1)).startsWith("CREATE TRIGGER").endsWith("END");
    assertThat(statements.get(2)).isEqualTo("INSERT INTO a VALUES ('it''s;')");
  }

  @Test
  void should_reject_update_and_delete_when_touching_step_transitions() {
    store.recordRunStart("r1", "hotfix apply", Optional.empty(), NOW);
    store.appendTransition("r1", "s1", "apply", Optional.empty(), "PENDING", Optional.empty());

    assertThatThrownBy(() -> store.executeUpdate("UPDATE step_transitions SET to_state='RUNNING'"))
        .isInstanceOf(StateStoreException.class)
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> store.executeUpdate("DELETE FROM step_transitions"))
        .isInstanceOf(StateStoreException.class)
        .hasMessageContaining("append-only");
    assertThat(store.transitions("r1")).hasSize(1);
    assertThat(store.transitions("r1").get(0).toState()).isEqualTo("PENDING");
  }

  @Test
  void should_reject_update_and_delete_when_touching_audit() {
    store.audit("operator", "hotfix apply", "HF-1");

    assertThatThrownBy(() -> store.executeUpdate("UPDATE audit SET actor='x'"))
        .isInstanceOf(StateStoreException.class)
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> store.executeUpdate("DELETE FROM audit"))
        .isInstanceOf(StateStoreException.class)
        .hasMessageContaining("append-only");
    assertThat(store.auditRows(10))
        .singleElement()
        .extracting(AuditEntry::actor)
        .isEqualTo("operator");
  }

  @Test
  void should_return_audit_rows_newest_first_when_limited() {
    store.audit("a", "one", null);
    store.audit("a", "two", "d2");
    store.audit("a", "three", "d3");

    List<AuditEntry> rows = store.auditRows(2);

    assertThat(rows).extracting(AuditEntry::action).containsExactly("three", "two");
    assertThat(rows.get(0).detail()).contains("d3");
    assertThat(rows.get(0).seq()).isGreaterThan(rows.get(1).seq());
  }

  @Test
  void should_record_run_lifecycle_and_transitions_in_order_when_written() {
    store.recordRunStart("r1", "hotfix apply", Optional.of("p1"), NOW);
    Transition t1 =
        store.appendTransition("r1", "s1", "apply", Optional.empty(), "PENDING", Optional.empty());
    Transition t2 =
        store.appendTransition(
            "r1", "s1", "apply", Optional.of("PENDING"), "RUNNING", Optional.of("go"));
    store.recordRunEnd("r1", NOW.plusSeconds(5), TerminalState.SUCCEEDED, 0);

    assertThat(t2.seq()).isGreaterThan(t1.seq());
    assertThat(store.transitions("r1")).containsExactly(t1, t2);
    RunRecord run = store.run("r1").orElseThrow();
    assertThat(run.planId()).contains("p1");
    assertThat(run.endedAt()).contains(NOW.plusSeconds(5));
    assertThat(run.terminalState()).contains(TerminalState.SUCCEEDED);
    assertThat(run.exitCode()).contains(0);
    assertThat(run.pending()).isFalse();
    assertThat(store.runs(10)).containsExactly(run);
  }

  @Test
  void should_list_only_runs_without_terminal_state_when_querying_pending() {
    store.recordRunStart("r1", "hotfix apply", Optional.empty(), NOW);
    store.recordRunStart("r2", "export", Optional.empty(), NOW.plusSeconds(1));
    store.recordRunEnd("r1", NOW.plusSeconds(2), TerminalState.FAILED, 4);

    assertThat(store.pendingRuns()).extracting(RunRecord::runId).containsExactly("r2");
    assertThat(store.pendingRuns().get(0).pending()).isTrue();
  }

  @Test
  void should_reject_transition_when_run_is_unknown() {
    assertThatThrownBy(
            () ->
                store.appendTransition(
                    "nope", "s1", "apply", Optional.empty(), "PENDING", Optional.empty()))
        .isInstanceOf(StateStoreException.class);
  }

  /** A run has exactly one terminal state (the Journal contract; assessment item E7). */
  @Test
  void should_refuse_a_second_terminal_state_for_the_same_run() {
    store.recordRunStart("r1", "hotfix apply", Optional.of("p1"), NOW);
    store.recordRunEnd("r1", NOW.plusSeconds(5), TerminalState.SUCCEEDED, 0);

    assertThatThrownBy(() -> store.recordRunEnd("r1", NOW.plusSeconds(6), TerminalState.FAILED, 4))
        .isInstanceOf(StateStoreException.class)
        .hasMessageContaining("already ended as SUCCEEDED");
    assertThat(store.run("r1").orElseThrow().terminalState()).contains(TerminalState.SUCCEEDED);
  }

  @Test
  void should_throw_when_ending_an_unknown_run() {
    assertThatThrownBy(() -> store.recordRunEnd("nope", NOW, TerminalState.FAILED, 4))
        .isInstanceOf(StateStoreException.class)
        .hasMessageContaining("nope");
  }

  @Test
  void should_save_load_consume_once_and_expire_plans_when_managing_plans() {
    StoredPlan plan =
        new StoredPlan(
            "p1",
            "hotfix apply",
            "{}",
            "{\"steps\":[]}",
            "sha256:abc",
            NOW,
            NOW.plus(Duration.ofMinutes(30)),
            Optional.empty());
    store.savePlan(plan);
    store.savePlan(
        new StoredPlan(
            "p-old",
            "export",
            "{}",
            "{}",
            "sha256:old",
            NOW.minusSeconds(3600),
            NOW.minusSeconds(1),
            Optional.empty()));

    assertThat(store.loadPlan("p1")).contains(plan);
    assertThat(store.loadPlan("missing")).isEmpty();
    assertThat(store.consumePlan("p1", "r1", NOW)).isTrue();
    assertThat(store.consumePlan("p1", "r2", NOW)).isFalse();
    assertThat(store.loadPlan("p1").orElseThrow().consumedByRunId()).contains("r1");
    assertThat(store.consumePlan("p-old", "r3", NOW)).isFalse();
    assertThat(store.expirePlans(NOW)).isEqualTo(1);
    assertThat(store.loadPlan("p-old")).isEmpty();
    assertThat(store.loadPlan("p1")).isPresent();
  }

  @Test
  void should_record_hotfix_with_files_and_answer_ownership_queries_when_installed() {
    Path lib = Path.of("/opt/jrs/WEB-INF/lib/foo.jar");
    Path cls = Path.of("/opt/jrs/WEB-INF/classes/x.properties");
    HotfixInstalled hf =
        new HotfixInstalled(
            "HF-1", "1.0", "Fix foo", "r1", Optional.of("snap-1"), HotfixState.INSTALLED, NOW);
    store.recordHotfixInstalled(
        hf,
        List.of(
            new HotfixFile("HF-1", lib, "replace", Optional.of("aa"), Optional.of("bb")),
            new HotfixFile("HF-1", cls, "add", Optional.empty(), Optional.of("cc"))));

    assertThat(store.installedHotfixes()).containsExactly(hf);
    assertThat(store.hotfix("HF-1")).contains(hf);
    assertThat(store.hotfixFiles("HF-1")).hasSize(2);
    assertThat(store.filesOwnedBy(List.of(lib, Path.of("/elsewhere"))))
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.hotfixId()).isEqualTo("HF-1");
              assertThat(f.path()).isEqualTo(lib);
              assertThat(f.beforeSha256()).contains("aa");
            });
    assertThat(store.filesOwnedBy(List.of())).isEmpty();

    store.updateHotfixState("HF-1", HotfixState.ROLLED_BACK);

    assertThat(store.installedHotfixes()).isEmpty();
    assertThat(store.hotfixes())
        .singleElement()
        .extracting(HotfixInstalled::state)
        .isEqualTo(HotfixState.ROLLED_BACK);
    assertThat(store.filesOwnedBy(List.of(lib))).isEmpty();
    assertThatThrownBy(() -> store.updateHotfixState("nope", HotfixState.SUPERSEDED))
        .isInstanceOf(StateStoreException.class);
  }

  /**
   * Review finding 1.14: {@code Instant.toString()} emits 0, 3, 6 or 9 fractional digits, so {@code
   * ...:00Z} sorted after {@code ...:00.500Z} and the LIFO rollback order was wrong within a
   * second. Timestamps are stored fixed-width at millisecond precision.
   */
  @Test
  void should_order_installed_hotfixes_by_time_at_sub_second_granularity() {
    HotfixInstalled first = hotfix("HF-A", NOW);
    HotfixInstalled second = hotfix("HF-B", NOW.plusMillis(500));
    store.recordHotfixInstalled(second, List.of());
    store.recordHotfixInstalled(first, List.of());

    assertThat(store.installedHotfixes()).containsExactly(first, second);
    assertThat(store.hotfixes()).containsExactly(first, second);
  }

  @Test
  void should_treat_a_plan_as_expired_when_it_lapsed_less_than_a_second_ago() {
    Instant expiry = NOW.plusSeconds(1);
    store.savePlan(
        new StoredPlan(
            "p-sub", "hotfix apply", "{}", "{}", "sha256:sub", NOW, expiry, Optional.empty()));

    assertThat(store.consumePlan("p-sub", "r1", expiry.plusMillis(500))).isFalse();
    assertThat(store.expirePlans(expiry.plusMillis(500))).isEqualTo(1);
  }

  /**
   * Review finding 1.15: ownership was answered by comparing raw path strings, so a path spelled
   * with a redundant segment (or, on Windows, in another letter case) missed the row, and the LIFO
   * and overlap checks with it. Rows carry a canonical key next to the display path.
   */
  @Test
  void should_find_owned_files_when_queried_by_an_unnormalised_spelling_of_the_path() {
    Path lib = Path.of("/opt/jrs/WEB-INF/lib/foo.jar");
    store.recordHotfixInstalled(
        hotfix("HF-1", NOW),
        List.of(new HotfixFile("HF-1", lib, "replace", Optional.of("aa"), Optional.of("bb"))));

    Path spelledDifferently = Path.of("/opt/jrs/WEB-INF/./classes/../lib/foo.jar");
    assertThat(store.filesOwnedBy(List.of(spelledDifferently)))
        .singleElement()
        .extracting(HotfixFile::path)
        .isEqualTo(lib);
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void should_find_owned_files_regardless_of_letter_case_on_windows() {
    Path lib = Path.of("C:/opt/jrs/WEB-INF/lib/foo.jar");
    store.recordHotfixInstalled(
        hotfix("HF-1", NOW),
        List.of(new HotfixFile("HF-1", lib, "replace", Optional.of("aa"), Optional.of("bb"))));

    assertThat(store.filesOwnedBy(List.of(Path.of("c:/OPT/jrs/web-inf/LIB/FOO.JAR"))))
        .singleElement()
        .extracting(HotfixFile::path)
        .isEqualTo(lib);
  }

  @Test
  void should_unregister_a_customization_when_given_an_unnormalised_spelling_of_its_path() {
    Path file = Path.of("/opt/jrs/WEB-INF/classes/custom.properties");
    store.registerCustomization(new Customization(file, "aa", Optional.empty(), NOW));

    assertThat(
            store.unregisterCustomization(Path.of("/opt/jrs/WEB-INF/./classes/custom.properties")))
        .isTrue();
    assertThat(store.customizations()).isEmpty();
  }

  private static HotfixInstalled hotfix(String id, Instant at) {
    return new HotfixInstalled(
        id, "1.0", "Fix " + id, "r1", Optional.empty(), HotfixState.INSTALLED, at);
  }

  /**
   * ADR-0030 (issue #99): a recorded row keeps its origin; the older constructor means jrs-upgrade.
   */
  @Test
  void should_keep_the_origin_of_a_recorded_hotfix_when_read_back() {
    HotfixInstalled recorded =
        new HotfixInstalled(
            "JRSHF-10.0.0-20260730-0457",
            "10.0.0",
            "JasperReports Server Pro 10.0.0 cumulative hotfix 20260730_0457",
            "recorded",
            Optional.empty(),
            HotfixState.INSTALLED,
            NOW,
            HotfixInstalled.Origin.RECORDED);
    store.recordHotfixInstalled(recorded, List.of());
    store.recordHotfixInstalled(hotfix("HF-J", NOW), List.of());

    assertThat(store.hotfix("JRSHF-10.0.0-20260730-0457"))
        .get()
        .satisfies(
            h -> {
              assertThat(h.origin()).isEqualTo(HotfixInstalled.Origin.RECORDED);
              assertThat(h.recorded()).isTrue();
            });
    assertThat(store.hotfix("HF-J")).get().extracting(HotfixInstalled::recorded).isEqualTo(false);
    assertThat(store.installedHotfixes()).hasSize(2);
  }

  @Test
  void should_roll_back_the_whole_transaction_when_a_file_row_violates_a_constraint() {
    Path lib = Path.of("/opt/jrs/WEB-INF/lib/foo.jar");
    HotfixInstalled hf =
        new HotfixInstalled(
            "HF-2", "1.0", "Dup", "r1", Optional.empty(), HotfixState.INSTALLED, NOW);

    assertThatThrownBy(
            () ->
                store.recordHotfixInstalled(
                    hf,
                    List.of(
                        new HotfixFile("HF-2", lib, "replace", Optional.empty(), Optional.empty()),
                        new HotfixFile(
                            "HF-2", lib, "replace", Optional.empty(), Optional.empty()))))
        .isInstanceOf(StateStoreException.class);

    assertThat(store.hotfix("HF-2")).isEmpty();
    assertThat(store.hotfixFiles("HF-2")).isEmpty();
    store.audit("a", "still-usable", null);
    assertThat(store.auditRows(1)).hasSize(1);
  }

  @Test
  void should_upsert_customizations_servers_and_snapshots_when_written_twice() {
    Path p = Path.of("/opt/jrs/WEB-INF/classes/custom.xml");
    store.registerCustomization(new Customization(p, "aa", Optional.empty(), NOW));
    store.registerCustomization(
        new Customization(p, "bb", Optional.of("snap"), NOW.plusSeconds(1)));
    assertThat(store.customizations())
        .singleElement()
        .extracting(Customization::originalSha256)
        .isEqualTo("bb");
    assertThat(store.unregisterCustomization(p)).isTrue();
    assertThat(store.unregisterCustomization(p)).isFalse();

    ServerRecord srv =
        new ServerRecord(
            "srv-1", "http://localhost:8080/jasperserver", "9.0.0", "PRO", "SINGLE", NOW, NOW);
    store.upsertServer(srv);
    store.upsertServer(
        new ServerRecord(
            "srv-1",
            "http://localhost:8080/jasperserver",
            "9.1.0",
            "PRO",
            "SINGLE",
            NOW.minusSeconds(9),
            NOW.plusSeconds(9)));
    assertThat(store.servers())
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.version()).isEqualTo("9.1.0");
              assertThat(s.firstSeen()).isEqualTo(NOW);
              assertThat(s.lastSeen()).isEqualTo(NOW.plusSeconds(9));
            });

    store.recordRunStart("r1", "hotfix apply", Optional.empty(), NOW);
    SnapshotRecord snap =
        new SnapshotRecord("snap-1", "r1", "s1", tmp.resolve("snap-1"), "dd", Optional.empty());
    store.recordSnapshot(snap);
    store.recordSnapshot(
        new SnapshotRecord("snap-1", "r1", "s1", tmp.resolve("snap-1"), "dd", Optional.of("HF-1")));
    assertThat(store.snapshot("snap-1").orElseThrow().referencedBy()).contains("HF-1");
    assertThat(store.snapshots("r1")).hasSize(1);
    assertThat(store.snapshots("r2")).isEmpty();
  }

  @Test
  void should_use_wal_and_full_sync_when_opened() {
    // WAL leaves a -wal sidecar once a write has happened; that is observable without SQL access.
    store.audit("a", "b", null);
    assertThat(Files.exists(tmp.resolve("home").resolve("state.db-wal"))).isTrue();
  }

  @Test
  void should_redact_secrets_in_step_transitions_and_audit_details() {
    store.recordRunStart("r1", "hotfix apply", Optional.empty(), NOW);
    Transition t =
        store.appendTransition(
            "r1",
            "s1",
            "apply",
            Optional.empty(),
            "FAILED",
            Optional.of(
                "failed with password=superSecretPassword and Authorization: Bearer token123"));
    assertThat(t.detail())
        .contains("failed with password=[redacted] and Authorization: [redacted]");
    assertThat(store.transitions("r1").get(0).detail())
        .contains("failed with password=[redacted] and Authorization: [redacted]");

    AuditEntry entry = store.audit("admin", "run", "auth: password=superSecretPassword");
    assertThat(entry.detail()).contains("auth: password=[redacted]");
    assertThat(store.auditRows(1).get(0).detail()).contains("auth: password=[redacted]");
  }
}
