package com.jaspersoft.jrsupgrade.ops.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.engine.TerminalState;
import com.jaspersoft.jrsupgrade.core.state.Customization;
import com.jaspersoft.jrsupgrade.core.state.StateStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetentionProtectionTest {

  private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");

  @TempDir Path tmp;

  private StateStore store;

  @BeforeEach
  void setUp() {
    store = StateStore.open(tmp.resolve("state.db"), Clock.fixed(T0, ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  private void run(String runId, String operation, Instant started, Optional<TerminalState> end) {
    store.recordRunStart(runId, operation, Optional.empty(), started);
    end.ifPresent(s -> store.recordRunEnd(runId, started.plusSeconds(60), s, 0));
  }

  @Test
  void should_return_nothing_when_store_is_empty() {
    assertThat(RetentionProtection.compute(store).runIds()).isEmpty();
  }

  /**
   * ADR-0004: a run another tool journaled in a shared home (jrsctl's hotfix runs) is never pruned,
   * whatever its outcome; jrs-upgrade's own finished runs are not protected for being runs.
   */
  @Test
  void should_protect_every_run_another_tool_journaled_and_none_of_its_own() {
    run("r-hf", "hotfix.apply", T0, Optional.of(TerminalState.SUCCEEDED));
    run("r-hf-rb", "hotfix.rollback", T0.plusSeconds(60), Optional.of(TerminalState.FAILED));
    run("r-exp", "export", T0.plusSeconds(120), Optional.of(TerminalState.SUCCEEDED));
    run("r-imp", "import", T0.plusSeconds(180), Optional.of(TerminalState.SUCCEEDED));
    run("r-smoke", "smoke --mutating", T0.plusSeconds(240), Optional.of(TerminalState.SUCCEEDED));
    run("r-test", "upgrade.test", T0.plusSeconds(300), Optional.of(TerminalState.SUCCEEDED));

    RetentionProtection.Protected p = RetentionProtection.compute(store);

    assertThat(p.runIds()).containsExactlyInAnyOrder("r-hf", "r-hf-rb");
    assertThat(p.reasons().get("r-hf")).contains("hotfix.apply").contains("another tool");
  }

  @Test
  void should_protect_snapshot_run_when_customization_is_registered() {
    store.registerCustomization(
        new Customization(
            Path.of("/opt/jrs/WEB-INF/classes/x.properties"),
            "abc",
            Optional.of("cust-0123456789abcdef/file"),
            T0));

    RetentionProtection.Protected p = RetentionProtection.compute(store);

    assertThat(p.runIds()).containsExactly("cust-0123456789abcdef");
    assertThat(p.reasons().get("cust-0123456789abcdef")).contains("registered customization");
  }

  @Test
  void should_protect_only_latest_successful_upgrade_when_several_are_recorded() {
    run("r-up1", "upgrade", T0, Optional.of(TerminalState.SUCCEEDED));
    run("r-up2", "upgrade", T0.plusSeconds(3600), Optional.of(TerminalState.SUCCEEDED));
    run("r-up3", "upgrade", T0.plusSeconds(7200), Optional.of(TerminalState.FAILED));
    run("r-exp", "export", T0.plusSeconds(10_800), Optional.of(TerminalState.SUCCEEDED));

    RetentionProtection.Protected p = RetentionProtection.compute(store);

    assertThat(p.runIds()).containsExactly("r-up2");
    assertThat(p.reasons().get("r-up2")).isEqualTo("most recent successful upgrade");
    assertThat(RetentionProtection.latestSuccessfulUpgrade(store).map(r -> r.runId()))
        .contains("r-up2");
  }

  @Test
  void should_protect_pending_runs_when_recovery_is_outstanding() {
    run("r-pending", "import", T0, Optional.empty());
    run("r-done", "import", T0.plusSeconds(60), Optional.of(TerminalState.SUCCEEDED));

    RetentionProtection.Protected p = RetentionProtection.compute(store);

    assertThat(p.runIds()).containsExactly("r-pending");
    assertThat(p.reasons().get("r-pending")).isEqualTo("run pending recovery");
  }

  @Test
  void should_ignore_reference_without_step_when_snapshot_ref_is_malformed() {
    assertThat(RetentionProtection.runIdOf("r-1/snapshot")).contains("r-1");
    assertThat(RetentionProtection.runIdOf("no-slash")).isEmpty();
    assertThat(RetentionProtection.runIdOf("/leading")).isEmpty();
  }
}
