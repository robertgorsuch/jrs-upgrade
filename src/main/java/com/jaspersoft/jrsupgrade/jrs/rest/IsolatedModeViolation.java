package com.jaspersoft.jrsupgrade.jrs.rest;

import java.net.URI;
import java.util.Objects;

/**
 * Thrown before any bytes leave the process when {@code network.mode} is {@code isolated} and a
 * request targets a host other than {@code server.baseUrl}'s (spec §5.1, §19 assumption 1).
 * Invariant: the offending URI is carried without its query string, so a token in {@code pp=} can
 * never reach a log line through this exception.
 */
public final class IsolatedModeViolation extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final URI offendingUri;
  private final String allowedHost;

  public IsolatedModeViolation(URI offendingUri, String allowedHost) {
    super(
        "isolated mode: refused request to host '"
            + hostOf(offendingUri)
            + "' (only '"
            + allowedHost
            + "' is allowed); uri="
            + RestClient.withoutQuery(offendingUri));
    this.offendingUri = RestClient.withoutQuery(Objects.requireNonNull(offendingUri, "uri"));
    this.allowedHost = Objects.requireNonNull(allowedHost, "allowedHost");
  }

  /** The refused URI with any query string removed. */
  public URI offendingUri() {
    return offendingUri;
  }

  public String allowedHost() {
    return allowedHost;
  }

  private static String hostOf(URI uri) {
    return uri.getHost() == null ? "" : uri.getHost();
  }
}
