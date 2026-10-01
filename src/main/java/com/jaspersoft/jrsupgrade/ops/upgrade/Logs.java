package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.event.Event;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.jrs.vendor.VendorTools;
import java.util.Optional;

/** Log-event helpers; every message passes the services' redactor before it is emitted. */
final class Logs {

  private Logs() {}

  static void info(UpgradeRuntime rt, Context ctx, EventSink out, Step step, String message) {
    emit(rt, ctx, out, step, Event.Log.Level.INFO, message);
  }

  static void warn(UpgradeRuntime rt, Context ctx, EventSink out, Step step, String message) {
    emit(rt, ctx, out, step, Event.Log.Level.WARN, message);
  }

  static void emit(
      UpgradeRuntime rt,
      Context ctx,
      EventSink out,
      Step step,
      Event.Log.Level level,
      String message) {
    out.emit(
        new Event.Log(
            rt.clock().instant(),
            ctx.runId(),
            Optional.of(step.id()),
            step.phase(),
            level,
            rt.services().redactor().redact(message)));
  }

  static VendorTools.LogScope scope(Context ctx, Step step) {
    return new VendorTools.LogScope(ctx.runId(), Optional.of(step.id()), step.phase());
  }
}
