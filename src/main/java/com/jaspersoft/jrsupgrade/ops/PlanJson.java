package com.jaspersoft.jrsupgrade.ops;

import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.PlanSummary;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.json.Json;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON form of a plan: what {@code --json} prints and what the {@code plans} table stores.
 * Invariant: one document for both, so a plan shown on the terminal and a plan replayed from the
 * store cannot disagree about what the run was going to do. It lives in ops rather than in the CLI
 * because the store and {@code runs recover} need it as much as the terminal does (roadmap item
 * 17).
 */
public final class PlanJson {

  private PlanJson() {}

  /** The JSON document for {@code --json} and the stored-plan table. */
  public static String toJson(Plan plan) {
    return Json.writePretty(toTree(plan));
  }

  public static Map<String, Object> toTree(Plan plan) {
    PlanSummary s = plan.summary();
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("operation", s.operation());
    summary.put("target", s.target());
    summary.put("filesTouched", s.filesTouched().stream().map(Path::toString).toList());
    summary.put("resourcesTouched", s.resourcesTouched());
    summary.put("serviceRestart", s.serviceRestart());
    summary.put("backupLocations", s.backupLocations().stream().map(Path::toString).toList());
    summary.put("rollbackPointsByPhase", s.rollbackPointsByPhase());
    summary.put("strategy", s.strategy());
    summary.put("warnings", s.warnings());
    Map<String, Object> fingerprint = new LinkedHashMap<>();
    fingerprint.put("value", plan.fingerprint().value());
    fingerprint.put("inputs", plan.fingerprint().inputs());
    List<Map<String, Object>> steps = new ArrayList<>();
    for (Step step : plan.steps()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", step.id());
      m.put("phase", step.phase());
      m.put("title", step.title());
      m.put("detail", step.detail());
      m.put("irreversible", step.irreversible());
      steps.add(m);
    }
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("planId", plan.planId());
    root.put("operation", s.operation());
    root.put("summary", summary);
    root.put("fingerprint", fingerprint);
    root.put("steps", steps);
    return root;
  }
}
