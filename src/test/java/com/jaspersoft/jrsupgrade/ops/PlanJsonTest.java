package com.jaspersoft.jrsupgrade.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.PlanFingerprint;
import com.jaspersoft.jrsupgrade.core.engine.PlanSummary;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.core.json.Json;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The plan document is what {@code --json} prints and what the store keeps for a resumed run. A
 * field missing from it is a field the operator never sees again.
 */
class PlanJsonTest {

  @Test
  void should_carry_every_summary_field_and_every_step() throws IOException {
    JsonNode tree = Json.mapper().readTree(PlanJson.toJson(plan()));

    assertThat(tree.get("planId").asText()).isEqualTo("p-7");
    assertThat(tree.get("operation").asText()).isEqualTo("hotfix apply");
    JsonNode summary = tree.get("summary");
    assertThat(summary.get("target").asText()).isEqualTo("server-1");
    assertThat(summary.get("serviceRestart").asBoolean()).isTrue();
    assertThat(summary.get("strategy").asText()).isEqualTo("snapshot");
    assertThat(summary.get("filesTouched")).hasSize(1);
    assertThat(summary.get("backupLocations")).hasSize(1);
    assertThat(summary.get("resourcesTouched")).hasSize(1);
    assertThat(summary.get("warnings")).hasSize(1);
    assertThat(summary.get("rollbackPointsByPhase").get("apply").asText()).isEqualTo("snapshot");
    assertThat(tree.get("fingerprint").get("value").asText()).isNotBlank();
    assertThat(tree.get("fingerprint").get("inputs").get("bundle").asText())
        .isEqualTo("sha256:abc");
    JsonNode steps = tree.get("steps");
    assertThat(steps).hasSize(1);
    assertThat(steps.get(0).get("id").asText()).isEqualTo("swap");
    assertThat(steps.get(0).get("phase").asText()).isEqualTo("apply");
    assertThat(steps.get(0).get("title").asText()).isEqualTo("swap the jars");
    assertThat(steps.get(0).get("detail").asText()).isEqualTo("per-file rename");
    assertThat(steps.get(0).get("irreversible").asBoolean()).isFalse();
  }

  @Test
  void should_produce_the_same_content_as_the_tree_it_is_written_from() throws IOException {
    Map<String, Object> tree = PlanJson.toTree(plan());

    assertThat(Json.mapper().readTree(PlanJson.toJson(plan())))
        .isEqualTo(Json.mapper().readTree(Json.write(tree)));
  }

  private static Plan plan() {
    Step step =
        new Step() {
          @Override
          public String id() {
            return "swap";
          }

          @Override
          public String title() {
            return "swap the jars";
          }

          @Override
          public String phase() {
            return "apply";
          }

          @Override
          public String detail() {
            return "per-file rename";
          }

          @Override
          public boolean mutating() {
            return true;
          }

          @Override
          public CheckResult precheck(Context ctx) {
            return CheckResult.pass();
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
            List.of(Path.of("WEB-INF", "lib", "x.jar")),
            List.of("/public/Samples"),
            true,
            List.of(Path.of("snapshots", "r-1")),
            Map.of("apply", "snapshot"),
            "snapshot",
            List.of("the service will be stopped"));
    return new Plan(
        "p-7", List.of(step), summary, PlanFingerprint.of(Map.of("bundle", "sha256:abc")));
  }
}
