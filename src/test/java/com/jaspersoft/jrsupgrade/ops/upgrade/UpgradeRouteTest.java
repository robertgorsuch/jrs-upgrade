package com.jaspersoft.jrsupgrade.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.RunOptions;
import com.jaspersoft.jrsupgrade.core.engine.RunOutcome;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.Mode;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.RollbackOptions;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.RollbackPoint;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.UpgradeOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #1, ADR-0002: an 8.2.0 server and a 10.1.0 target, which the matrix covers only as 8.2.0 ->
 * 10.0.0 (newdb) -> 10.1.0 (samedb). The fixture's own package stands for 10.1.0 and states no
 * version; the 10.0.0 package states its version in its directory name.
 */
class UpgradeRouteTest {

  private static final String TARGET = "10.1.0";
  private static final String STOP = "10.0.0";
  private static final String ROUTE = "8.2.0 -> 10.0.0 (newdb, transit) -> 10.1.0 (samedb)";

  @TempDir Path tmp;

  private static UpgradeOptions route(UpgradeFixture f, Path transit) {
    return UpgradeOptions.newdb(TARGET, f.packageDir).withTransitPackages(List.of(transit));
  }

  /** 10.0 and later check the licence during the upgrade (upgrade guide 10.1 pp.43-44). */
  private static void licence(UpgradeFixture f) throws Exception {
    UpgradeFixture.write(f.userHome.resolve("jaspersoft.jrs.license"), "licence");
  }

  @Test
  void should_plan_the_documented_route_with_a_transit_hop_when_no_single_path_covers_the_pair()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planUpgrade(route(f, f.transitPackage(STOP)));

      assertThat(plan.summary().target()).isEqualTo(ROUTE);
      List<String> ids = UpgradeFixture.ids(plan);
      assertThat(ids)
          .contains(
              "verify-target-package@10.0.0",
              "verify-target-package",
              "verify-vendor-preconditions@10.0.0",
              "write-master-properties@10.0.0",
              "stage-keystore-init@10.0.0")
          .containsSubsequence(
              "stop-service", "full-export", "run-vendor-upgrade@10.0.0", "run-vendor-upgrade");
      assertThat(ids.stream().filter(id -> id.equals("stop-service"))).hasSize(1);
      assertThat(ids).doesNotContain("confirm-db-backup");
      assertThat(plan.summary().warnings())
          .anySatisfy(w -> assertThat(w).contains(ROUTE).contains("never lands on an"));
    }
  }

  @Test
  void should_refuse_with_exit_2_naming_the_route_and_its_packages_when_a_stop_has_no_package()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertThatThrownBy(() -> f.ops().planUpgrade(UpgradeOptions.newdb(TARGET, f.packageDir)))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining(ROUTE)
          .hasMessageContaining("states 10.0.0")
          .satisfies(
              e -> {
                UpgradeException u = (UpgradeException) e;
                assertThat(u.exitCode()).isEqualTo(2);
                assertThat(u.remediation())
                    .contains("--package <10.0.0 package> --package <10.1.0 package>");
              });
    }
  }

  @Test
  void should_refuse_with_exit_6_when_no_documented_route_exists() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertThatThrownBy(() -> f.ops().planUpgrade(UpgradeOptions.newdb("12.0.0", f.packageDir)))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("not in the compatibility matrix")
          .hasMessageContaining("no documented route")
          .satisfies(e -> assertThat(((UpgradeException) e).exitCode()).isEqualTo(6));
    }
  }

  /** From 7.5 every route crosses into 8.x with newdb, which only the first hop may run. */
  @Test
  void should_refuse_with_exit_6_and_name_the_mode_when_the_route_starts_with_the_other_mode()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      f.fake.adapter.identity = com.jaspersoft.jrsupgrade.ops.FakeJrsAdapter.identity("7.5.0");
      UpgradeOptions samedb = new UpgradeOptions(TARGET, f.packageDir, Mode.SAMEDB, true);

      assertThatThrownBy(() -> f.ops().planUpgrade(samedb))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("starts with a newdb hop")
          .satisfies(
              e -> {
                assertThat(((UpgradeException) e).exitCode()).isEqualTo(6);
                assertThat(((UpgradeException) e).remediation()).contains("--mode newdb");
              });
    }
  }

  @Test
  void should_refuse_with_exit_1_when_a_package_is_no_hop_of_the_route() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      UpgradeOptions options =
          UpgradeOptions.newdb(TARGET, f.packageDir)
              .withTransitPackages(List.of(f.transitPackage(STOP), f.transitPackage("9.0.0")));

      assertThatThrownBy(() -> f.ops().planUpgrade(options))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("states version 9.0.0")
          .satisfies(e -> assertThat(((UpgradeException) e).exitCode()).isEqualTo(1));
    }
  }

  @Test
  void should_refuse_with_exit_1_when_a_single_hop_upgrade_is_given_two_packages()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      UpgradeOptions options =
          UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, f.packageDir)
              .withTransitPackages(List.of(f.transitPackage(STOP)));

      assertThatThrownBy(() -> f.ops().planUpgrade(options))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("one documented upgrade")
          .satisfies(e -> assertThat(((UpgradeException) e).exitCode()).isEqualTo(1));
    }
  }

  /**
   * The acceptance of issue #1: the transit hop runs newdb from the point-B export against the
   * 10.0.0 package, told to skip the app server and pointed at a scratch Tomcat; the last hop runs
   * samedb against the 10.1.0 package and deploys; the live webapp is never at 10.0.0.
   */
  @Test
  void should_run_the_transit_hop_without_deploying_then_the_last_hop_when_routed()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path transit = f.transitPackage(STOP);
      licence(f);

      RunOutcome outcome =
          f.run(f.ops().planUpgrade(route(f, transit)), "r-route", RunOptions.DEFAULT);

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
      String transitLog = Files.readString(transit.resolve("js-ant.log"));
      assertThat(transitLog).contains("upgrade-minimal-pro").contains("-Dstrategy=standard");
      assertThat(f.vendorLogText()).contains("-Dstrategy=inDatabase");
      String staged =
          Files.readString(transit.resolve("buildomatic").resolve("default_master.properties"));
      assertThat(staged)
          .contains("appServerType=skipAppServerCheck")
          .doesNotContain("appServerDir=" + f.tomcatDir);
      assertThat(UpgradeFixture.read(f.webappDir.resolve("scripts").resolve("app.js")))
          .isEqualTo(UpgradeFixture.NEW_SCRIPT);
      Path started = f.fake.home.snapshots().resolve("r-route").resolve("vendor-upgrade.started");
      assertThat(Files.readAllLines(started).get(0)).startsWith("js-upgrade-newdb");
      String manifest =
          Files.readString(f.fake.home.snapshots().resolve("r-route").resolve("upgrade.json"));
      assertThat(manifest).contains("\"mode\" : \"NEWDB\"").contains("10.0.0 newdb transit");
      // the newdb transit hop rebuilt the database from the point-B export, so it can be rebuilt
      // from that export again (ADR-0029)
      assertThatCode(
              () -> f.ops().planRollback("r-route", new RollbackOptions(RollbackPoint.B, true)))
          .doesNotThrowAnyException();
    }
  }

  @Test
  void should_restore_point_b_and_never_stop_at_the_intermediate_version_when_the_last_hop_fails()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path transit = f.transitPackage(STOP);
      licence(f);
      f.failVendorScriptAfterCopy();

      RunOutcome outcome =
          f.run(f.ops().planUpgrade(route(f, transit)), "r-route-fail", RunOptions.DEFAULT);

      assertThat(outcome).isNotInstanceOf(RunOutcome.Succeeded.class);
      assertThat(UpgradeFixture.read(f.webappDir.resolve("scripts").resolve("app.js")))
          .isEqualTo(UpgradeFixture.OLD_SCRIPT);
      assertThat(transit.resolve("buildomatic").resolve("default_master.properties"))
          .doesNotExist();
    }
  }

  @Test
  void should_rehearse_every_hop_of_the_route_when_test_is_given() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan = f.ops().planTest(route(f, f.transitPackage(STOP)));

      assertThat(UpgradeFixture.ids(plan))
          .containsSubsequence(
              "run-vendor-test@10.0.0",
              "unstage-target-package@10.0.0",
              "run-vendor-test",
              "unstage-target-package");
      assertThat(plan.summary().target()).isEqualTo("rehearsal of " + ROUTE);
    }
  }
}
