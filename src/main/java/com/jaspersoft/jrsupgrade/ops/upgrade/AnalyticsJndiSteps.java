package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Release notes 9.0 p.15 (issue #108): a 9.0 webapp's {@code META-INF/context.xml} must declare the
 * JNDI resources {@code jdbc/jasperserverSystemAnalytics} and {@code
 * jdbc/jasperserverAuditAnalytics} "even if the feature is disabled". Invariants: added to the
 * verify phase for a 9.0.x target only; read-only, nothing to compensate; a missing resource is a
 * logged WARN naming the file and the resource, never a failure, because the server that just
 * answered the smoke probes is the judge of whether it starts.
 */
final class AnalyticsJndiSteps {

  static final String CHECK_ANALYTICS_JNDI = "check-analytics-jndi";

  private AnalyticsJndiSteps() {}

  static final class Check implements Step {

    private final UpgradeRuntime rt;
    private final UpgradeInput in;

    Check(UpgradeRuntime rt, UpgradeInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    @Override
    public String id() {
      return CHECK_ANALYTICS_JNDI;
    }

    @Override
    public String title() {
      return "check the analytics JNDI resources of the 9.0 webapp";
    }

    @Override
    public String phase() {
      return Phases.VERIFY;
    }

    @Override
    public String detail() {
      return contextXml()
          + " must declare "
          + String.join(" and ", VendorPreconditions.ANALYTICS_JNDI)
          + " even with the feature off (release notes 9.0); a WARN, not a failure";
    }

    @Override
    public boolean mutating() {
      return false;
    }

    Path contextXml() {
      return in.hostTomcatDir()
          .resolve("webapps")
          .resolve(in.webappName())
          .resolve("META-INF")
          .resolve("context.xml");
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      List<String> missing = VendorPreconditions.missingAnalyticsJndi(contextXml());
      if (missing.isEmpty()) {
        Logs.info(rt, ctx, out, this, "both analytics JNDI resources declared in " + contextXml());
      } else {
        Logs.warn(
            rt,
            ctx,
            out,
            this,
            contextXml()
                + " does not declare "
                + String.join(" or ", missing)
                + "; JasperReports Server 9.0 wants both even when the analytics feature is off"
                + " (release notes 9.0): add the Resource elements, then restart the server");
      }
      return StepResult.ok();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return StepResult.ok();
    }
  }
}
