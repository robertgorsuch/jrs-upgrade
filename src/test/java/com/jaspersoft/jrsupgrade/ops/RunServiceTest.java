package com.jaspersoft.jrsupgrade.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.PlanFingerprint;
import com.jaspersoft.jrsupgrade.core.engine.PlanSummary;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.core.state.StoredPlan;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The glue both front ends share: storing a plan with its time to live, claiming it once, and
 * building the context and runner a run executes in. It lives in ops rather than in the CLI so
 * {@code runs recover} cannot drift from the command line (roadmap item 17).
 */
class RunServiceTest {

  @Test
  void should_store_a_plan_with_its_arguments_and_a_time_to_live(@TempDir Path tmp)
      throws Exception {
    try (FakeServices fake = FakeServices.in(tmp)) {
      RunService runs = new RunService(fake.build());
      Plan plan = plan("p-1");

      StoredPlan stored = runs.storePlan(plan, PlanRegistry.EXPORT, "{\"a\":1}");

      assertThat(stored.planId()).isEqualTo("p-1");
      assertThat(stored.operation()).isEqualTo(PlanRegistry.EXPORT);
      assertThat(stored.argsJson()).isEqualTo("{\"a\":1}");
      assertThat(stored.planJson()).contains("\"planId\"").contains("p-1");
      assertThat(stored.expiresAt()).isEqualTo(stored.createdAt().plus(RunService.PLAN_TTL));
      assertThat(runs.store().loadPlan("p-1")).isPresent();
    }
  }

  @Test
  void should_let_a_plan_be_claimed_once(@TempDir Path tmp) throws Exception {
    try (FakeServices fake = FakeServices.in(tmp)) {
      RunService runs = new RunService(fake.build());
      runs.storePlan(plan("p-2"), PlanRegistry.EXPORT, "{}");

      Optional<String> first = runs.claim("p-2");
      Optional<String> second = runs.claim("p-2");

      assertThat(first).isPresent();
      assertThat(second).as("a plan may be run once, whichever front end claims it").isEmpty();
    }
  }

  @Test
  void should_report_no_pending_runs_and_a_free_lock_on_a_fresh_home(@TempDir Path tmp)
      throws Exception {
    try (FakeServices fake = FakeServices.in(tmp)) {
      RunService runs = new RunService(fake.build());

      assertThat(runs.pendingRuns()).isEmpty();
      assertThat(runs.lockHolder()).isEmpty();
    }
  }

  @Test
  void should_build_a_context_and_a_runner_for_a_run(@TempDir Path tmp) throws Exception {
    try (FakeServices fake = FakeServices.in(tmp)) {
      RunService runs = new RunService(fake.build());

      Context ctx = runs.context("r-1");

      assertThat(ctx.runId()).isEqualTo("r-1");
      assertThat(ctx.home()).isEqualTo(fake.home);
      assertThat(ctx.cancel().isCancelled()).isFalse();
      assertThat(runs.runner(event -> {})).isNotNull();
    }
  }

  private static Plan plan(String planId) {
    Step step =
        new Step() {
          @Override
          public String id() {
            return "noop";
          }

          @Override
          public String title() {
            return "does nothing";
          }

          @Override
          public String phase() {
            return "apply";
          }

          @Override
          public boolean mutating() {
            return false;
          }

          @Override
          public com.jaspersoft.jrsupgrade.core.engine.CheckResult precheck(Context ctx) {
            return com.jaspersoft.jrsupgrade.core.engine.CheckResult.pass();
          }

          @Override
          public StepResult execute(Context ctx, EventSink out) {
            return StepResult.ok();
          }

          @Override
          public StepResult compensate(Context ctx, EventSink out) {
            return StepResult.ok();
          }
        };
    PlanSummary summary =
        new PlanSummary(
            "hotfix apply",
            "server-1",
            List.of(),
            List.of(),
            false,
            List.of(),
            Map.of(),
            "snapshot",
            List.of());
    return new Plan(
        planId, List.of(step), summary, PlanFingerprint.of(Map.of("bundle", "sha256:abc")));
  }
}
