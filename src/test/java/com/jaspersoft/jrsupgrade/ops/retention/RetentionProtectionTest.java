package com.jaspersoft.jrsupgrade.ops.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.engine.TerminalState;
import com.jaspersoft.jrsupgrade.core.state.Customization;
import com.jaspersoft.jrsupgrade.core.state.HotfixInstalled;
import com.jaspersoft.jrsupgrade.core.state.HotfixState;
import com.jaspersoft.jrsupgrade.core.state.StateStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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

  private void hotfix(String id, String runId, HotfixState state) {
    store.recordHotfixInstalled(
        new HotfixInstalled(
            id, "1", "title " + id, runId, Optional.of(runId + "/snapshot"), state, T0),
        List.of());
  }

  private void run(String runId, String operation, Instant started, Optional<TerminalState> end) {
    store.recordRunStart(runId, operation, Optional.empty(), started);
    end.ifPresent(s -> store.recordRunEnd(runId, started.plusSeconds(60), s, 0));
  }

  @Test
  void should_return_nothing_when_store_is_empty() {
    assertThat(RetentionProtection.compute(store).runIds()).isEmpty();
  }

  @Test
  void should_protect_installing_run_when_hotfix_is_installed_but_not_when_rolled_back() {
    hotfix("HF-1", "r-hf1", HotfixState.INSTALLED);
    hotfix("HF-2", "r-hf2", HotfixState.ROLLED_BACK);
    hotfix("HF-3", "r-hf3", HotfixState.SUPERSEDED);

    RetentionProtection.Protected p = RetentionProtection.compute(store);

    assertThat(p.runIds()).containsExactly("r-hf1");
    assertThat(p.reasons().get("r-hf1")).contains("installed hotfix HF-1");
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
    run("r-hf", "hotfix.apply", T0.plusSeconds(10_800), Optional.of(TerminalState.SUCCEEDED));

    RetentionProtection.Protected p = RetentionProtection.compute(store);

    assertThat(p.runIds()).containsExactly("r-up2");
    assertThat(p.reasons().get("r-up2")).isEqualTo("most recent successful upgrade");
    assertThat(RetentionProtection.latestSuccessfulUpgrade(store).map(r -> r.runId()))
        .contains("r-up2");
  }

  @Test
  void should_protect_pending_runs_when_recovery_is_outstanding() {
    run("r-pending", "hotfix.apply", T0, Optional.empty());
    run("r-done", "hotfix.apply", T0.plusSeconds(60), Optional.of(TerminalState.SUCCEEDED));

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
