package com.jaspersoft.jrsupgrade.jrs.service;

import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Sleeper;
import com.jaspersoft.jrsupgrade.core.platform.ServiceController;
import com.jaspersoft.jrsupgrade.jrs.api.ServerIdentity;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * What the shared service steps need from an operation's runtime. Invariants: {@link #controller()}
 * is created from the configuration on every call and never cached, so a stopped-then-started
 * service is always re-queried; {@link #refreshIdentity()} contacts the server rather than
 * returning a memoised identity, which is what makes wait-for-server a real probe.
 */
public interface ServiceRuntime {

  ServiceController controller();

  /**
   * The bundled database service that must be up before Tomcat starts, when this host registers one
   * beside the Tomcat service ({@link CompanionDatabase}, installation guide p.51); empty by
   * default and for every kind without a service manager.
   */
  default Optional<ServiceController> databaseController() {
    return Optional.empty();
  }

  /** The configured stop timeout ({@code service.stopTimeoutSeconds}). */
  Duration serviceTimeout();

  Clock clock();

  Sleeper sleeper();

  /** {@code serverInfo} from the server, never from a cache. */
  ServerIdentity refreshIdentity();

  /**
   * Where a step finds its runtime when it runs. An ops operation holds its runtime already and
   * passes a {@link Fixed} one; a strategy builds its steps before any context exists and resolves
   * the runtime from the context each step runs in ({@link ContextServiceRuntime}).
   */
  @FunctionalInterface
  interface Source {

    ServiceRuntime at(Context ctx);

    static Source fixed(ServiceRuntime runtime) {
      return new Fixed(runtime);
    }
  }

  /** A runtime known when the plan is built, the same for every context. */
  record Fixed(ServiceRuntime runtime) implements Source {

    public Fixed {
      Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public ServiceRuntime at(Context ctx) {
      return runtime;
    }
  }
}
