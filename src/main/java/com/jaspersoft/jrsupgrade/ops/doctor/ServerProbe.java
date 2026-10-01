package com.jaspersoft.jrsupgrade.ops.doctor;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.config.ConfigException;
import com.jaspersoft.jrsupgrade.core.secrets.SecretException;
import com.jaspersoft.jrsupgrade.core.secrets.SecretRef;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsupgrade.jrs.api.ServerIdentity;
import com.jaspersoft.jrsupgrade.jrs.rest.RestException;
import com.jaspersoft.jrsupgrade.ops.ReportItem;
import com.jaspersoft.jrsupgrade.ops.Services;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The one connection attempt {@code doctor} makes, shared by every server-dependent check (spec
 * §12.1). Invariants: the adapter is asked for its identity exactly once, without a credential when
 * the server allows it; when that fails the {@code server} item is a FAIL carrying the adapter's
 * remediation and every dependent check becomes a SKIP saying "server unreachable" rather than a
 * second failure for the same cause; a server that answers 401 or 403 is reported as refusing the
 * configured user's credentials, never as unreachable, and without the server's HTML error page;
 * the admin password is never prompted for here (field test 2, D1): when it is not at hand without
 * a prompt, the checks that log in are SKIPs saying "no admin password available" and the checks
 * that need only the identity still run.
 */
final class ServerProbe {

  static final String NAME = "server";
  static final String UNREACHABLE = "server unreachable";
  static final String REFUSED = "server refused the credentials";
  static final String NO_CREDENTIALS = "no admin password available";
  static final String FIX_SERVER = "fix the server check first";

  /** A live adapter with the identity it reported. */
  record Connected(JrsAdapter adapter, ServerIdentity identity) {}

  private final Optional<Connected> connected;
  private final ReportItem item;
  private final String skipReason;
  private final String skipRemediation;

  /** Why a login is impossible without a prompt, or empty when the password is at hand. */
  private final Optional<String> loginProblem;

  private ServerProbe(
      Optional<Connected> connected,
      ReportItem item,
      String skipReason,
      String skipRemediation,
      Optional<String> loginProblem) {
    this.connected = connected;
    this.item = item;
    this.skipReason = skipReason;
    this.skipRemediation = skipRemediation;
    this.loginProblem = loginProblem;
  }

  private static ServerProbe failed(ReportItem item, Optional<String> loginProblem) {
    return new ServerProbe(Optional.empty(), item, UNREACHABLE, FIX_SERVER, loginProblem);
  }

  static ServerProbe connect(Services services) {
    Objects.requireNonNull(services, "services");
    Optional<String> loginProblem = loginProblem(services);
    String baseUrl =
        services.config().server().baseUrl().map(Object::toString).orElse("the server");
    try {
      JrsAdapter adapter = services.adapter().get();
      ServerIdentity identity = adapter.identity();
      return new ServerProbe(
          Optional.of(new Connected(adapter, identity)),
          ReportItem.pass(NAME, "reachable at " + identity.baseUrl()),
          UNREACHABLE,
          FIX_SERVER,
          loginProblem);
    } catch (SecretException e) {
      // serverInfo answered but wants a login this run cannot give: reachable, identity unknown
      String remediation = loginProblem.orElse(e.getMessage());
      return new ServerProbe(
          Optional.empty(),
          ReportItem.pass(
              NAME, "reachable at " + baseUrl + " (not logged in: " + NO_CREDENTIALS + ")"),
          NO_CREDENTIALS,
          remediation,
          Optional.of(remediation));
    } catch (JrsUnreachableException e) {
      return failed(ReportItem.fail(NAME, e.getMessage(), e.remediation()), loginProblem);
    } catch (RestException e) {
      if (!e.authenticationFailure()) {
        return failed(
            ReportItem.fail(
                NAME,
                "HTTP " + e.status() + " from " + e.method() + " " + e.path(),
                "check server.baseUrl and the jrs-upgrade log"),
            loginProblem);
      }
      String user = services.config().server().auth().username().orElse("(no user configured)");
      return new ServerProbe(
          Optional.empty(),
          ReportItem.fail(
              NAME,
              services
                      .config()
                      .server()
                      .baseUrl()
                      .map(u -> u + " answered, but it")
                      .orElse("the server")
                  + " refused the login of "
                  + user
                  + " (HTTP "
                  + e.status()
                  + " from "
                  + e.method()
                  + " "
                  + e.path()
                  + ")",
              "check the password behind server.auth.passwordRef for "
                  + user
                  + "; on the commercial edition init proposes superuser, whose password can differ"
                  + " from jasperadmin's (jrs-upgrade config set server.auth.username jasperadmin to use"
                  + " that account)"),
          REFUSED,
          FIX_SERVER,
          loginProblem);
    } catch (ConfigException e) {
      return failed(
          ReportItem.fail(NAME, firstLine(e.getMessage()), e.remediation()), loginProblem);
    } catch (RuntimeException e) {
      return failed(
          ReportItem.fail(
              NAME,
              "unexpected " + e.getClass().getSimpleName() + ": " + e.getMessage(),
              "check server.baseUrl and the jrs-upgrade log"),
          loginProblem);
    }
  }

  /**
   * The remediation for a login the doctor cannot attempt, or empty when the password reference is
   * absent (a configuration problem the auth check reports itself) or resolvable without a prompt.
   */
  private static Optional<String> loginProblem(Services services) {
    Config.Auth auth = services.config().server().auth();
    if (auth.passwordRef().isEmpty() || auth.username().isEmpty()) {
      return Optional.empty();
    }
    SecretRef ref = auth.passwordRef().get();
    if (services.secrets().availableWithoutPrompt(ref)) {
      return Optional.empty();
    }
    String supply =
        switch (ref) {
          case SecretRef.Env e -> "set " + e.name() + " in the environment";
          case SecretRef.File f -> "create the secret file " + f.path();
          case SecretRef.Enc c ->
              "unlock secrets.enc with --passphrase-file or JRS_UPGRADE_PASSPHRASE";
        };
    return Optional.of(
        supply + ", or store the password with jrs-upgrade init; the login checks then run");
  }

  ReportItem item() {
    return item;
  }

  boolean reachable() {
    return connected.isPresent();
  }

  Optional<Connected> connected() {
    return connected;
  }

  /** Runs {@code check} against the connection, or returns a SKIP when there is none. */
  ReportItem dependent(String name, Function<Connected, ReportItem> check) {
    if (connected.isEmpty()) {
      return ReportItem.skip(name, skipReason, skipRemediation);
    }
    return check.apply(connected.get());
  }

  /**
   * As {@link #dependent}, for a check that logs in: a SKIP saying {@link #NO_CREDENTIALS} when the
   * password is not at hand without a prompt.
   */
  ReportItem authenticated(String name, Function<Connected, ReportItem> check) {
    if (loginProblem.isPresent()) {
      return ReportItem.skip(name, NO_CREDENTIALS, loginProblem.get());
    }
    return dependent(name, check);
  }

  private static String firstLine(String text) {
    int nl = text.indexOf('\n');
    return nl < 0 ? text : text.substring(0, nl);
  }
}
