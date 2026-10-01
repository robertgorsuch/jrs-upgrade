package com.jaspersoft.jrsupgrade.ops;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.config.ConfigWriter;
import java.util.Objects;

/**
 * Renders the effective configuration for {@code jrs-upgrade config show} (spec §5.1). Invariant:
 * the output is the same YAML {@code init} writes, so every {@code passwordRef} appears as its
 * reference ({@code env:NAME}, {@code file:/path}, {@code enc:NAME}) and never as a resolved value;
 * references are not secrets.
 */
public final class ConfigShow {

  private ConfigShow() {}

  public static String render(Config config) {
    return ConfigWriter.render(Objects.requireNonNull(config, "config"));
  }
}
