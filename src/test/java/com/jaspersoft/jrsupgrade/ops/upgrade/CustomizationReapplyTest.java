package com.jaspersoft.jrsupgrade.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.RunOptions;
import com.jaspersoft.jrsupgrade.core.engine.RunOutcome;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.ops.customizations.DefaultCustomizationOperations;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.UpgradeOptions;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CustomizationReapplyTest {

  private static final String ORIGINAL = "a=1\nb=2\nc=3\n";
  private static final String CUSTOMIZED = "a=1\nb=two\nc=3\n";
  private static final String UPGRADED = "a=1\nb=2\nc=3\nd=4\n";

  @TempDir Path tmp;

  private Step reapplyStep(UpgradeFixture f) {
    Plan plan = f.ops().planUpgrade(UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, f.packageDir));
    return UpgradeFixture.step(plan, "plan-customization-reapply");
  }

  private Path registerCustomized(UpgradeFixture f) throws Exception {
    return registerCustomized(
        f, f.webappDir.resolve("WEB-INF").resolve("classes").resolve("custom.properties"));
  }

  private Path registerCustomized(UpgradeFixture f, Path file) throws Exception {
    Path pristine = tmp.resolve("pristine.properties");
    UpgradeFixture.write(pristine, ORIGINAL);
    UpgradeFixture.write(file, CUSTOMIZED);
    DefaultCustomizationOperations ops =
        new DefaultCustomizationOperations(f.services, f.snapshots());
    ops.register(file, java.util.Optional.of(pristine));
    assertThat(f.store().customizations().get(0).originalSha256()).isEqualTo(f.sha(pristine));
    return file;
  }

  @Test
  void should_stop_and_restart_the_service_around_reapply_when_a_target_is_under_web_inf()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      registerCustomized(f);
      Plan plan =
          f.ops().planUpgrade(UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, f.packageDir));

      assertThat(UpgradeFixture.ids(plan))
          .containsSubsequence(
              "wait-for-server",
              "plan-customization-reapply-stop-service",
              "plan-customization-reapply",
              "plan-customization-reapply-start-service",
              "plan-customization-reapply-wait-for-server",
              "smoke");
      assertThat(plan.byPhase().get("reconcile").stream().map(Step::id))
          .contains(
              "plan-customization-reapply-stop-service",
              "plan-customization-reapply-start-service",
              "plan-customization-reapply-wait-for-server");

      RunOutcome outcome = f.run(plan, "r-cust-svc", RunOptions.DEFAULT);

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.fake.platform.controller.events)
          .as(
              "the vendor upgrade (newdb takes the full export after its stop, review §1.6) and"
                  + " the WEB-INF customisation each stop and start")
          .containsExactly("stop", "start", "stop", "start");
    }
  }

  @Test
  void should_not_add_service_steps_when_no_customization_is_under_web_inf() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      registerCustomized(f, f.webappDir.resolve("scripts").resolve("custom.js"));
      Plan plan =
          f.ops().planUpgrade(UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, f.packageDir));

      assertThat(UpgradeFixture.ids(plan))
          .contains("plan-customization-reapply")
          .doesNotContain(
              "plan-customization-reapply-stop-service",
              "plan-customization-reapply-start-service",
              "plan-customization-reapply-wait-for-server");
    }
  }

  @Test
  void should_reapply_customization_when_upgraded_file_equals_the_original() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path file = registerCustomized(f);
      UpgradeFixture.write(file, ORIGINAL);
      Step step = reapplyStep(f);
      Context ctx = f.ctx("r-cust-1");

      StepResult result = step.execute(ctx, f.events::add);

      assertThat(result).as(String.join("\n", f.logs())).isInstanceOf(StepResult.Ok.class);
      assertThat(UpgradeFixture.read(file)).isEqualTo(CUSTOMIZED);
      assertThat(f.snapshots().find("r-cust-1", "reapply-customizations")).isPresent();
      assertThat(f.logs()).anyMatch(m -> m.contains("re-applied"));
      assertThat(f.store().auditRows(10))
          .anyMatch(a -> a.action().equals("customizations.reapplied"));

      assertThat(step.compensate(ctx, f.events::add)).isInstanceOf(StepResult.Ok.class);
      assertThat(UpgradeFixture.read(file))
          .as("compensation restores the upgraded file")
          .isEqualTo(ORIGINAL);
    }
  }

  @Test
  void should_report_conflict_with_a_diff_and_leave_the_file_when_upgrade_changed_it()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path file = registerCustomized(f);
      UpgradeFixture.write(file, UPGRADED);
      Step step = reapplyStep(f);

      StepResult result = step.execute(f.ctx("r-cust-2"), f.events::add);

      assertThat(result).isInstanceOf(StepResult.Ok.class);
      assertThat(UpgradeFixture.read(file)).as("never blind-copied").isEqualTo(UPGRADED);
      List<String> logs = f.logs();
      assertThat(logs).anyMatch(m -> m.startsWith("CONFLICT ") && m.contains(file.toString()));
      assertThat(logs).anyMatch(m -> m.contains("-b=two"));
      assertThat(logs).anyMatch(m -> m.contains("+b=2"));
      assertThat(logs).anyMatch(m -> m.contains("+d=4"));
      assertThat(logs).anyMatch(m -> m.contains("1 conflict(s)"));
      assertThat(f.snapshots().find("r-cust-2", "reapply-customizations")).isEmpty();
    }
  }

  /**
   * Issue #6: a customized 8.2 ehcache.xml against a 10.x target says where the setting went, not
   * only that the file is gone.
   */
  @Test
  void should_say_where_the_setting_went_when_a_relocated_file_conflicts() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path file = registerCustomized(f, f.webappDir.resolve("WEB-INF").resolve("ehcache.xml"));
      java.nio.file.Files.delete(file);
      Plan plan = f.ops().planUpgrade(UpgradeOptions.newdb("10.0.0", f.packageDir));

      assertThat(plan.summary().warnings())
          .anySatisfy(w -> assertThat(w).contains("WEB-INF/ehcache.xml").contains("appCacheType"));

      StepResult result =
          UpgradeFixture.step(plan, "plan-customization-reapply")
              .execute(f.ctx("r-reloc"), f.events::add);

      assertThat(result).isInstanceOf(StepResult.Ok.class);
      assertThat(f.logs())
          .anyMatch(m -> m.startsWith("CONFLICT ") && m.contains("absent after the upgrade"))
          .anySatisfy(
              m ->
                  assertThat(m)
                      .contains("WEB-INF/ehcache.xml removed in the target")
                      .contains("appCacheType in default_master.properties")
                      .contains("*-ehcache.xml")
                      .contains("NGRA upgrade guide"));
    }
  }

  @Test
  void should_report_already_in_place_when_upgraded_file_equals_the_customized_copy()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      registerCustomized(f);
      Step step = reapplyStep(f);

      StepResult result = step.execute(f.ctx("r-cust-3"), f.events::add);

      assertThat(result).isInstanceOf(StepResult.Ok.class);
      assertThat(f.logs()).anyMatch(m -> m.contains("already in place"));
      assertThat(f.logs()).anyMatch(m -> m.contains("0 re-applied, 0 conflict(s)"));
    }
  }

  @Test
  void should_report_conflict_when_the_file_vanished_after_the_upgrade() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path file = registerCustomized(f);
      java.nio.file.Files.delete(file);
      Step step = reapplyStep(f);

      StepResult result = step.execute(f.ctx("r-cust-4"), f.events::add);

      assertThat(result).isInstanceOf(StepResult.Ok.class);
      assertThat(f.logs())
          .anyMatch(m -> m.startsWith("CONFLICT ") && m.contains("absent after the upgrade"));
    }
  }
}
