package com.jaspersoft.jrsupgrade.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.RunOptions;
import com.jaspersoft.jrsupgrade.core.engine.RunOutcome;
import com.jaspersoft.jrsupgrade.core.state.HotfixInstalled;
import com.jaspersoft.jrsupgrade.core.state.HotfixState;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.UpgradeOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #9: a patched WAR deployed in place of the package's own, and hotfix labels that differ
 * from the version the server reports.
 */
class PatchedWarTest {

  @TempDir Path tmp;

  private static UpgradeOptions withWar(UpgradeFixture f, Path war) {
    return UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, f.packageDir).withWar(war);
  }

  @Test
  void should_stage_the_patched_war_and_record_both_checksums_when_war_is_given() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path own = f.usePackagedWar();
      String ownSha = f.sha(own);
      Path war = f.patchedWar("patched.war", Optional.of("9.0.0-hf3"), "9.0.0", "9.0.0");
      Plan plan = f.ops().planUpgrade(withWar(f, war));

      List<String> ids = UpgradeFixture.ids(plan);
      assertThat(ids.indexOf("stage-patched-war"))
          .isGreaterThan(ids.indexOf("stage-keystore-init"))
          .isLessThan(ids.indexOf("stop-service"));
      assertThat(plan.summary().warnings())
          .anySatisfy(w -> assertThat(w).contains("patched WAR").contains(f.sha(war)));

      RunOutcome outcome = f.run(plan, "r-war", RunOptions.DEFAULT);

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.sha(own)).isEqualTo(f.sha(war)).isNotEqualTo(ownSha);
      String manifest =
          Files.readString(f.fake.home.snapshots().resolve("r-war").resolve("upgrade.json"));
      assertThat(manifest)
          .contains("\"packageContentHash\"")
          .contains(f.sha(war))
          .contains("9.0.0-hf3");
    }
  }

  @Test
  void should_put_the_packages_own_war_back_when_the_upgrade_fails_after_staging()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path own = f.usePackagedWar();
      String ownSha = f.sha(own);
      Path war = f.patchedWar("patched.war", Optional.empty(), "9.0.0");
      f.failVendorScriptAfterCopy();

      RunOutcome outcome =
          f.run(f.ops().planUpgrade(withWar(f, war)), "r-war-fail", RunOptions.DEFAULT);

      assertThat(outcome).isNotInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.sha(own)).isEqualTo(ownSha);
    }
  }

  @Test
  void should_refuse_war_with_exit_2_when_the_package_holds_an_exploded_webapp() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path war = f.patchedWar("patched.war", Optional.empty(), "9.0.0");

      assertThatThrownBy(() -> f.ops().planUpgrade(withWar(f, war)))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("exploded webapp")
          .satisfies(e -> assertThat(((UpgradeException) e).exitCode()).isEqualTo(2));
    }
  }

  @Test
  void should_refuse_war_with_exit_2_when_it_states_another_version() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.usePackagedWar();
      Path war = f.patchedWar("patched.war", Optional.empty(), "10.1.0");

      assertThatThrownBy(() -> f.ops().planUpgrade(withWar(f, war)))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("states version 10.1.0")
          .satisfies(e -> assertThat(((UpgradeException) e).exitCode()).isEqualTo(2));
    }
  }

  @Test
  void should_refuse_war_with_exit_2_when_it_is_not_a_webapp_archive() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.usePackagedWar();
      Path notAWar = f.fakeExport("not-a-war.war");

      assertThatThrownBy(() -> f.ops().planUpgrade(withWar(f, notAWar)))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("no WEB-INF/")
          .satisfies(e -> assertThat(((UpgradeException) e).exitCode()).isEqualTo(2));
    }
  }

  /** NGRA: jars labelled 8.2.6 on a server that reports 8.2.0. */
  @Test
  void should_name_the_running_version_when_installed_jars_carry_a_hotfix_label() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      UpgradeFixture.write(
          f.webappDir.resolve("WEB-INF/lib/jasperserver-api-externalAuth-impl-8.2.6.jar"), "hf");

      Plan plan =
          f.ops().planUpgrade(UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, f.packageDir));

      assertThat(plan.summary().warnings())
          .anySatisfy(
              w ->
                  assertThat(w)
                      .contains("labelled 8.2.6")
                      .contains("jasperserver-api-externalAuth-impl-8.2.6.jar")
                      .contains("the running version is 8.2.0")
                      .doesNotContain("jasperserver-8.2.0.jar"));
    }
  }

  @Test
  void should_name_the_running_version_when_a_recorded_bundle_carries_a_hotfix_label()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.store()
          .recordHotfixInstalled(
              new HotfixInstalled(
                  "NGRA-CUMULATIVE-2",
                  "8.2.6",
                  "second cumulative bundle",
                  "r-hf",
                  Optional.empty(),
                  HotfixState.INSTALLED,
                  Instant.EPOCH),
              List.of());

      Plan plan =
          f.ops().planUpgrade(UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, f.packageDir));

      assertThat(plan.summary().warnings())
          .anySatisfy(
              w ->
                  assertThat(w)
                      .contains("NGRA-CUMULATIVE-2")
                      .contains("labelled 8.2.6")
                      .contains("the running version is 8.2.0"));
    }
  }

  @Test
  void should_report_no_label_when_every_vendor_jar_states_the_server_version() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan =
          f.ops().planUpgrade(UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, f.packageDir));

      assertThat(plan.summary().warnings()).noneMatch(w -> w.contains("hotfix label"));
    }
  }
}
