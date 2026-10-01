package com.jaspersoft.jrsupgrade.core.engine;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import java.util.Map;
import java.util.Objects;

/**
 * Everything a {@code Step} may touch during a run. Invariant: immutable after construction;
 * services are looked up by type so that ops modules can add their own (state store, snapshot
 * store, adapter, config) without changing this record. A missing service is a programming error
 * and fails fast with a clear message.
 */
public record Context(
    String runId,
    JrsUpgradeHome home,
    Platform platform,
    CancellationToken cancel,
    Map<Class<?>, Object> services) {

  public Context {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(home, "home");
    Objects.requireNonNull(platform, "platform");
    Objects.requireNonNull(cancel, "cancel");
    services = Map.copyOf(services);
  }

  public <T> T service(Class<T> type) {
    Object o = services.get(type);
    if (o == null) {
      throw new IllegalStateException(
          "no " + type.getSimpleName() + " registered in the run context for run " + runId);
    }
    return type.cast(o);
  }

  public boolean has(Class<?> type) {
    return services.containsKey(type);
  }

  /** Returns a copy with one more service registered. */
  public Context with(Class<?> type, Object service) {
    var m = new java.util.HashMap<Class<?>, Object>(services);
    m.put(type, service);
    return new Context(runId, home, platform, cancel, m);
  }
}
