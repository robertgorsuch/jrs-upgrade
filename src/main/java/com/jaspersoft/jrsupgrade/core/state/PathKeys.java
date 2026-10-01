package com.jaspersoft.jrsupgrade.core.state;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Canonical key under which the state store compares file paths (review finding 1.15). Invariants:
 * the key is absolute and normalised with forward slashes; letter case is folded exactly when the
 * default file system folds it (Windows), so two spellings of one file share a key and two distinct
 * Linux files never do; nothing here touches the disk, so a deleted or not-yet-created file keys
 * the same as an existing one.
 */
public final class PathKeys {

  static final boolean FOLDS_CASE = Path.of("A").equals(Path.of("a"));

  private PathKeys() {}

  public static String key(Path path) {
    String canonical = path.toAbsolutePath().normalize().toString().replace('\\', '/');
    return FOLDS_CASE ? canonical.toLowerCase(Locale.ROOT) : canonical;
  }
}
