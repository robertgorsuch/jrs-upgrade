package com.jaspersoft.jrsupgrade.core.state;

import java.nio.file.Path;
import java.util.Optional;

/**
 * A row of {@code hotfix_files}: one path a hotfix added, replaced or deleted with the hashes
 * before and after (spec §5.4). Invariant: an absent before-hash means the file did not exist
 * before; an absent after-hash means it was deleted.
 */
public record HotfixFile(
    String hotfixId,
    Path path,
    String action,
    Optional<String> beforeSha256,
    Optional<String> afterSha256) {}
