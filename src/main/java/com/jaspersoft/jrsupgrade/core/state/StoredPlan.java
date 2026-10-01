package com.jaspersoft.jrsupgrade.core.state;

import java.time.Instant;
import java.util.Optional;

/**
 * A row of {@code plans}: the serialised plan, its fingerprint and TTL (spec §5.4, §6.2).
 * Invariant: a plan is executed at most once; {@code consumedByRunId} is set when a run claims it.
 */
public record StoredPlan(
    String planId,
    String operation,
    String argsJson,
    String planJson,
    String fingerprint,
    Instant createdAt,
    Instant expiresAt,
    Optional<String> consumedByRunId) {}
