package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.engine.Sleeper;
import com.jaspersoft.jrsupgrade.core.platform.FileOps;
import com.jaspersoft.jrsupgrade.core.platform.ServiceController;
import com.jaspersoft.jrsupgrade.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsupgrade.core.state.AuditActor;
import com.jaspersoft.jrsupgrade.core.state.StateStore;
import com.jaspersoft.jrsupgrade.jrs.api.ServerIdentity;
import com.jaspersoft.jrsupgrade.jrs.service.CompanionDatabase;
import com.jaspersoft.jrsupgrade.jrs.service.ServiceRuntime;
import com.jaspersoft.jrsupgrade.jrs.vendor.BuildomaticLocator;
import com.jaspersoft.jrsupgrade.jrs.vendor.VendorTools;
import com.jaspersoft.jrsupgrade.ops.Services;
import com.jaspersoft.jrsupgrade.ops.db.JdbcConnector;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Collaborators every upgrade step needs, captured once at planning time (steps are rebuilt from
 * the stored arguments on recovery, so holding them here is safe). Invariant: all fields are
 * non-null; {@code vendorTools} is a function so tests can substitute a fake process runner while
 * the default resolves the platform's runner lazily.
 */
record UpgradeRuntime(
    Services services,
    SnapshotStore snapshots,
    Function<Services, VendorTools> vendorTools,
    JdbcConnector jdbc,
    Sleeper sleeper)
    implements ServiceRuntime {

  UpgradeRuntime {
    Objects.requireNonNull(services, "services");
    Objects.requireNonNull(snapshots, "snapshots");
    Objects.requireNonNull(vendorTools, "vendorTools");
    Objects.requireNonNull(jdbc, "jdbc");
    Objects.requireNonNull(sleeper, "sleeper");
  }

  StateStore store() {
    return services.stateStore().get();
  }

  FileOps files() {
    return services.platform().files();
  }

  Config config() {
    return services.config();
  }

  JrsUpgradeHome home() {
    return services.home();
  }

  @Override
  public Clock clock() {
    return services.clock();
  }

  VendorTools tools() {
    return vendorTools.apply(services);
  }

  BuildomaticLocator locator() {
    return new BuildomaticLocator(services.platform());
  }

  @Override
  public ServiceController controller() {
    return services.platform().services(config().toServiceConfig());
  }

  @Override
  public Optional<ServiceController> databaseController() {
    return CompanionDatabase.controller(services.platform(), config().toServiceConfig());
  }

  @Override
  public Duration serviceTimeout() {
    return Duration.ofSeconds(config().service().stopTimeoutSeconds());
  }

  ServerIdentity identity() {
    return services.adapter().get().identity();
  }

  @Override
  public ServerIdentity refreshIdentity() {
    return services.adapter().get().refreshIdentity();
  }

  String actor() {
    return AuditActor.current();
  }
}
