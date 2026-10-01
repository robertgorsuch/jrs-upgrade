package com.jaspersoft.jrsupgrade.jrs.api;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.core.secrets.SecretResolver;
import java.util.ServiceLoader;

/**
 * Builds a {@link JrsAdapter} for the configured server. Implementations are discovered with {@link
 * ServiceLoader} so the ops and app modules depend only on this interface. Invariant: {@link
 * #connect} performs no mutating request; it may probe {@code serverInfo} and capabilities.
 */
public interface JrsAdapterFactory {

  /**
   * Connects to {@code config.server()}. Throws {@link JrsUnreachableException} when the server
   * does not answer, and {@link com.jaspersoft.jrsupgrade.core.config.ConfigException} when the
   * server block is missing.
   */
  JrsAdapter connect(Config config, SecretResolver secrets, Redactor redactor, Platform platform);

  /** The single registered implementation, or an error naming the missing service file. */
  static JrsAdapterFactory load() {
    return ServiceLoader.load(JrsAdapterFactory.class)
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "no JrsAdapterFactory registered in META-INF/services; is the jrs module on the"
                        + " classpath?"));
  }
}
