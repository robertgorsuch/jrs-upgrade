package com.jaspersoft.jrsupgrade.jrs.service;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Sleeper;
import com.jaspersoft.jrsupgrade.core.platform.ServiceController;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.api.ServerIdentity;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link ServiceRuntime} read from the run's {@link Context}, for callers that build their steps
 * before any context exists, such as the vendor export/import strategy (issue #43). Invariants: it
 * holds nothing the context does not already hold apart from the clock and sleeper it is given, so
 * a plan rebuilt during recovery resolves the same controller; the controller is created from
 * {@code Config.toServiceConfig()} through {@code Platform.services} on every call and never
 * cached, and a missing or invalid {@code service:} block surfaces as the {@code ConfigException}
 * the shared precheck reports; the identity is always re-read through {@link
 * JrsAdapter#refreshIdentity()}.
 */
public final class ContextServiceRuntime implements ServiceRuntime {

  private final Context ctx;
  private final Clock clock;
  private final Sleeper sleeper;

  private ContextServiceRuntime(Context ctx, Clock clock, Sleeper sleeper) {
    this.ctx = Objects.requireNonNull(ctx, "ctx");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
  }

  /** A source that builds the runtime from whichever context the step runs in. */
  public static ServiceRuntime.Source source(Clock clock, Sleeper sleeper) {
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(sleeper, "sleeper");
    return ctx -> new ContextServiceRuntime(ctx, clock, sleeper);
  }

  @Override
  public ServiceController controller() {
    return ctx.platform().services(ctx.service(Config.class).toServiceConfig());
  }

  @Override
  public Optional<ServiceController> databaseController() {
    return CompanionDatabase.controller(
        ctx.platform(), ctx.service(Config.class).toServiceConfig());
  }

  @Override
  public Duration serviceTimeout() {
    return ctx.service(Config.class).toServiceConfig().stopTimeout();
  }

  @Override
  public Clock clock() {
    return clock;
  }

  @Override
  public Sleeper sleeper() {
    return sleeper;
  }

  @Override
  public ServerIdentity refreshIdentity() {
    return ctx.service(JrsAdapter.class).refreshIdentity();
  }
}
