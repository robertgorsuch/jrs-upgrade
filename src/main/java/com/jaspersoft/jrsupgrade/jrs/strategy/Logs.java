package com.jaspersoft.jrsupgrade.jrs.strategy;

import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.event.Event;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.jrs.vendor.VendorTools;
import java.time.Instant;
import java.util.Optional;

/**
 * Emits {@link Event.Log} lines tagged with the step and run, redacted when a redactor is known.
 */
final class Logs {

  private Logs() {}

  static void info(EventSink out, Context ctx, Step step, String message) {
    emit(out, ctx, step, Event.Log.Level.INFO, message);
  }

  static void warn(EventSink out, Context ctx, Step step, String message) {
    emit(out, ctx, step, Event.Log.Level.WARN, message);
  }

  static void emit(EventSink out, Context ctx, Step step, Event.Log.Level level, String message) {
    String text = ctx.has(Redactor.class) ? ctx.service(Redactor.class).redact(message) : message;
    out.emit(
        new Event.Log(
            Instant.now(), ctx.runId(), Optional.of(step.id()), step.phase(), level, text));
  }

  static VendorTools.LogScope scope(Context ctx, Step step) {
    return new VendorTools.LogScope(ctx.runId(), Optional.of(step.id()), step.phase());
  }
}
