package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.config.ConfigLoader;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.core.secrets.SecretResolver;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapterFactory;
import com.jaspersoft.jrsupgrade.jrs.api.JrsUnreachableException;
import java.net.URI;

/**
 * Registered through {@code META-INF/services} on the test classpath so app tests never open a
 * socket: hands out {@link AppFakeAdapter} unless {@link #unreachable} is set, and refuses a
 * configuration without a server the way the real factory does.
 */
public final class TestAdapterFactory implements JrsAdapterFactory {

  public static volatile boolean unreachable;
  public static volatile AppFakeAdapter adapter = new AppFakeAdapter();

  @Override
  public JrsAdapter connect(
      Config config, SecretResolver secrets, Redactor redactor, Platform platform) {
    URI base = ConfigLoader.requireServer(config);
    if (unreachable) {
      throw new JrsUnreachableException(
          base, "connection refused: " + base, "start the server or fix server.baseUrl", null);
    }
    adapter.base = base;
    return adapter;
  }
}
