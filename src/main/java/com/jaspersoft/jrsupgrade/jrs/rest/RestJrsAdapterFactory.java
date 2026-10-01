package com.jaspersoft.jrsupgrade.jrs.rest;

import com.jaspersoft.jrsupgrade.core.compat.CompatMatrix;
import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.core.secrets.Secret;
import com.jaspersoft.jrsupgrade.core.secrets.SecretRef;
import com.jaspersoft.jrsupgrade.core.secrets.SecretResolver;
import com.jaspersoft.jrsupgrade.jrs.api.Credentials;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapterFactory;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The registered {@link JrsAdapterFactory} ({@code META-INF/services}). Invariants: {@link
 * #connect} sends nothing to the server and resolves no secret; identity and capabilities are
 * fetched lazily on first use so {@code doctor} can report "server unreachable" as a check item
 * instead of failing to start, and the password behind {@code server.auth.passwordRef} is resolved
 * by the first request that carries it (field test 2, D1), once, registered with the {@link
 * Redactor} and kept open for the life of the adapter (basic auth re-sends it, form auth may need
 * to log in again); proxy and trust-store secrets are handled by {@link RestClient#builder(Config,
 * SecretResolver, Redactor)}.
 */
public final class RestJrsAdapterFactory implements JrsAdapterFactory {

  @Override
  public JrsAdapter connect(
      Config config, SecretResolver secrets, Redactor redactor, Platform platform) {
    RestClient client = RestClient.builder(config, secrets, redactor).build();
    Config.Auth auth = config.server().auth();
    CompatMatrix matrix = CompatMatrix.load();
    if (auth.passwordRef().isEmpty()) {
      return new RestJrsAdapter(client, config, platform, matrix, Optional.empty());
    }
    String username = auth.username().orElse(auth.mode() == Config.AuthMode.TOKEN ? "token" : "");
    if (username.isEmpty()) {
      return new RestJrsAdapter(client, config, platform, matrix, Optional.empty());
    }
    Supplier<Secret> password = new Once(secrets, auth.passwordRef().get(), redactor);
    switch (auth.mode()) {
      case BASIC -> client.useBasic(username, password);
      case TOKEN -> client.useToken(password, Config.TokenLocation.QUERY);
      case FORM -> {
        // the adapter logs in lazily before the first authenticated call
      }
    }
    return RestJrsAdapter.withLazyCredentials(
        client,
        config,
        platform,
        matrix,
        () -> new Credentials(username, password.get(), Optional.empty()));
  }

  /** Resolves a reference at most once and keeps the secret open for the adapter's life. */
  private static final class Once implements Supplier<Secret> {
    private final SecretResolver secrets;
    private final SecretRef ref;
    private final Redactor redactor;
    private Secret resolved;

    Once(SecretResolver secrets, SecretRef ref, Redactor redactor) {
      this.secrets = secrets;
      this.ref = ref;
      this.redactor = redactor;
    }

    @Override
    public synchronized Secret get() {
      if (resolved == null) {
        Secret s = secrets.resolve(ref);
        redactor.register(s);
        resolved = s;
      }
      return resolved;
    }
  }
}
