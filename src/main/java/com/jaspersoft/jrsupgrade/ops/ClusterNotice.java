package com.jaspersoft.jrsupgrade.ops;

import com.jaspersoft.jrsupgrade.jrs.api.Capability;
import java.util.Optional;

/**
 * The one sentence every plan that changes {@code WEB-INF} carries on a server whose licence
 * includes clustering (review §3.2, issue #112): the clustering deck and every release note say the
 * nodes share one repository database, identical keystores and the same webapp, and jrs-upgrade
 * runs on one host. Invariants: pure text; the licence flag is read from the adapter's cached
 * capability probe, and a server that cannot be probed at plan time (unreachable, no credential)
 * yields no notice rather than an error, since the plan's own steps will say so.
 */
public final class ClusterNotice {

  public static final String WARNING =
      "The licence includes clustering (licenseFeatures cl=true). If this server is one node of a"
          + " cluster, the change to WEB-INF made here reaches this node only: apply the same"
          + " package on every other node before the load balancer sends it traffic, and keep"
          + " .jrsks and .jrsksp identical across nodes.";

  private ClusterNotice() {}

  /** The notice when the server's licence includes clustering; empty otherwise or when unknown. */
  public static Optional<String> warning(Services services) {
    try {
      return services.adapter().get().capabilities().contains(Capability.CLUSTERING)
          ? Optional.of(WARNING)
          : Optional.empty();
    } catch (RuntimeException unreachableOrUnauthenticated) {
      return Optional.empty();
    }
  }
}
