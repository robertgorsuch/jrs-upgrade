package com.jaspersoft.jrsupgrade.core.state;

import java.time.Instant;
import java.util.Optional;

/** A row of the append-only {@code audit} table (spec §11.3). */
public record AuditEntry(
    long seq, Instant ts, String actor, String action, Optional<String> detail) {}
