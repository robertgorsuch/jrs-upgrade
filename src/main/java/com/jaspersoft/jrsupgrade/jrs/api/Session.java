package com.jaspersoft.jrsupgrade.jrs.api;

import java.time.Instant;
import java.util.Optional;

/**
 * Result of a successful login. With {@code basic} auth there is no server-side session and {@code
 * cookie} is empty; every request re-sends the Authorization header.
 */
public record Session(AuthMode mode, Optional<String> cookie, Instant establishedAt) {

  public enum AuthMode {
    BASIC,
    FORM,
    TOKEN
  }
}
