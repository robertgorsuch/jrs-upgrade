package com.jaspersoft.jrsupgrade.jrs.api;

import java.net.URI;
import java.util.Set;

/**
 * What {@code GET /rest_v2/serverInfo} tells us, normalised (spec §7.1). Invariant: {@code version}
 * is a plain {@code major.minor.patch} string suitable for semver ranges; {@code tenancy} is
 * derived from the {@code MT} feature flag.
 */
public record ServerIdentity(
    URI baseUrl,
    String version,
    Edition edition,
    Tenancy tenancy,
    Set<String> features,
    String build,
    String dateFormat) {

  /** {@code UNKNOWN} when serverInfo names neither edition; never assumed (review finding 2.7). */
  public enum Edition {
    CE,
    PRO,
    UNKNOWN
  }

  public enum Tenancy {
    SINGLE,
    MULTI
  }

  public ServerIdentity {
    features = Set.copyOf(features);
  }

  /** Stable string used as a plan fingerprint input. */
  public String fingerprintInput() {
    return baseUrl + "|" + version + "|" + edition + "|" + tenancy + "|" + build;
  }
}
