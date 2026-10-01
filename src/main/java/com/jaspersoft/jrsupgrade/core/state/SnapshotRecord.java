package com.jaspersoft.jrsupgrade.core.state;

import java.nio.file.Path;
import java.util.Optional;

/** A row of {@code snapshots}: where a snapshot lives and what still references it (spec §5.6). */
public record SnapshotRecord(
    String id,
    String runId,
    String stepId,
    Path path,
    String manifestSha256,
    Optional<String> referencedBy) {}
