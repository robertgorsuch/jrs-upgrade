package com.jaspersoft.jrsupgrade.jrs;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.secrets.SecretRef;
import java.net.URI;
import java.util.Optional;

/** Builds {@link Config} trees for jrs tests without touching YAML. */
public final class TestConfigs {

  public static final String PASSWORD_ENV = "JRS_PASSWORD";

  private TestConfigs() {}

  public static Config server(URI baseUrl, Config.AuthMode mode) {
    return server(baseUrl, mode, Config.NetworkMode.ISOLATED, Optional.empty());
  }

  public static Config server(
      URI baseUrl, Config.AuthMode mode, Config.NetworkMode network, Optional<String> runAsUser) {
    Config d = Config.defaults();
    Config.Server server =
        new Config.Server(
            Optional.of(baseUrl),
            Optional.of(Config.WebappName.JASPERSERVER_PRO),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            runAsUser,
            new Config.Auth(
                mode, Optional.of("jasperadmin"), Optional.of(new SecretRef.Env(PASSWORD_ENV))));
    Config.Network net =
        new Config.Network(network, Config.Proxy.empty(), Config.TrustStore.empty());
    return new Config(server, d.service(), d.database(), d.vendor(), net, d.backups(), d.smoke());
  }

  /** A server block with no credentials at all. */
  public static Config anonymous(URI baseUrl) {
    Config base = server(baseUrl, Config.AuthMode.BASIC);
    Config.Server s = base.server();
    Config.Server server =
        new Config.Server(
            s.baseUrl(),
            s.webappName(),
            s.installDir(),
            s.tomcatDir(),
            s.buildomaticDir(),
            s.runAsUser(),
            Config.Auth.defaults());
    return new Config(
        server,
        base.service(),
        base.database(),
        base.vendor(),
        base.network(),
        base.backups(),
        base.smoke());
  }
}
