package com.jaspersoft.jrsupgrade.core.state;

import java.time.Instant;

/**
 * A row of {@code servers}: the identity {@code init}/{@code doctor} detected and when it was last
 * seen. Invariant: {@code id} is stable across runs for the same installation.
 */
public record ServerRecord(
    String id,
    String baseUrl,
    String version,
    String edition,
    String tenancy,
    Instant firstSeen,
    Instant lastSeen) {}
