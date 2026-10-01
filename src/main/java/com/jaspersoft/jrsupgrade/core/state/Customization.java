package com.jaspersoft.jrsupgrade.core.state;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/** A row of {@code customizations}: a registered path and its hash at registration (spec §10.3). */
public record Customization(
    Path path, String originalSha256, Optional<String> snapshotRef, Instant registeredAt) {}
