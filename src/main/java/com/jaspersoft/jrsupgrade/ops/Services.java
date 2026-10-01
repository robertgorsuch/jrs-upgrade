package com.jaspersoft.jrsupgrade.ops;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.compat.CompatMatrix;
import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.core.secrets.SecretResolver;
import com.jaspersoft.jrsupgrade.core.state.StateStore;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Everything an operation needs, assembled once by the application bootstrap and handed to every
 * operation (spec §4). Invariants: {@code config} is already validated against the schema; {@code
 * stateStore} and {@code adapter} are lazy so read-only operations that never touch the server or
 * the journal never open them, and the adapter supplier throws {@link
 * com.jaspersoft.jrsupgrade.jrs.api.JrsUnreachableException} or {@link
 * com.jaspersoft.jrsupgrade.core.config.ConfigException} rather than returning a half-connected
 * adapter; {@code interactive} is false whenever the operator passed {@code --yes} or stdin is not
 * a console, and no operation then waits for a human.
 */
public record Services(
    JrsUpgradeHome home,
    Config config,
    Platform platform,
    SecretResolver secrets,
    Redactor redactor,
    CompatMatrix matrix,
    Supplier<StateStore> stateStore,
    Supplier<JrsAdapter> adapter,
    Clock clock,
    boolean interactive) {

  public Services {
    Objects.requireNonNull(home, "home");
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(platform, "platform");
    Objects.requireNonNull(secrets, "secrets");
    Objects.requireNonNull(redactor, "redactor");
    Objects.requireNonNull(matrix, "matrix");
    Objects.requireNonNull(stateStore, "stateStore");
    Objects.requireNonNull(adapter, "adapter");
    Objects.requireNonNull(clock, "clock");
  }
}
