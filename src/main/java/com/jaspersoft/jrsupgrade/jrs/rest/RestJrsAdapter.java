package com.jaspersoft.jrsupgrade.jrs.rest;

import com.jaspersoft.jrsupgrade.core.compat.CompatMatrix;
import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.json.Json;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.jrs.api.BrokenDependencies;
import com.jaspersoft.jrsupgrade.jrs.api.Capability;
import com.jaspersoft.jrsupgrade.jrs.api.Credentials;
import com.jaspersoft.jrsupgrade.jrs.api.ExportRequest;
import com.jaspersoft.jrsupgrade.jrs.api.Handles;
import com.jaspersoft.jrsupgrade.jrs.api.HealthReport;
import com.jaspersoft.jrsupgrade.jrs.api.ImportRequest;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsupgrade.jrs.api.KeystoreInfo;
import com.jaspersoft.jrsupgrade.jrs.api.ServerIdentity;
import com.jaspersoft.jrsupgrade.jrs.api.Session;
import com.jaspersoft.jrsupgrade.jrs.keystore.KeystoreInspector;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one {@link JrsAdapter} (spec §7.2, ADR-0004), driven by probed {@link Capability}s rather
 * than the version string. Invariants: {@link #identity()} and {@link #capabilities()} hit the
 * server at most once each and are cached, while {@link #refreshIdentity()} always re-reads and
 * replaces the cached identity; every probe is a non-mutating {@code GET}; the only
 * repository-mutating calls are {@link #startImport}, {@link #createFolder}, {@link
 * #uploadJrxmlReport} and {@link #deleteResource}; downloads and uploads stream through the client;
 * in {@code form} auth mode a session is established lazily before the first authenticated call
 * using the configured credentials; no exception message carries a credential.
 */
public final class RestJrsAdapter implements JrsAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(RestJrsAdapter.class);

  static final String SERVER_INFO = "/rest_v2/serverInfo";
  static final String EXPORT = "/rest_v2/export";
  static final String IMPORT = "/rest_v2/import";
  static final String RESOURCES = "/rest_v2/resources";
  static final String REPORTS = "/rest_v2/reports";
  static final String JOBS = "/rest_v2/jobs";
  static final String ORGANIZATIONS = "/rest_v2/organizations";
  static final String REST_LOGIN = "/rest_v2/login";

  /** REST reference 10.1 pp.20-22: licence feature flags, commercial editions only. */
  static final String LICENSE_FEATURES = "/rest_v2/licenseFeatures";

  /** REST reference 10.1 p.128: the custom keys of the keystore, 7.5 and later. */
  static final String KEYS = "/rest_v2/keys/";

  static final String FORM_LOGIN = "/j_spring_security_check";

  /**
   * REST reference 10.1: the session is ended by {@code GET /logout.html}; there is no REST v2 one.
   */
  static final String LOGOUT = "/logout.html";

  static final String PROBE_ID = "jrs-upgrade-probe";
  static final String DEFAULT_EXPORT_FILE = "export.zip";
  static final String FOLDER_TYPE = "application/repository.folder+json";
  static final String REPORT_UNIT_TYPE = "application/repository.reportUnit+json";

  private final RestClient client;
  private final Config config;
  private final CompatMatrix matrix;

  /**
   * The configured credentials, resolved on first use so that connecting and an anonymous
   * serverInfo never need the password (field test 2, D1).
   */
  private final Optional<Supplier<Credentials>> configured;

  private final KeystoreInspector keystoreInspector;
  private final Object lock = new Object();
  private final Map<String, String> exportFileNames = new ConcurrentHashMap<>();
  private ServerIdentity identity;
  private Set<Capability> capabilities;
  private Map<Capability, String> probeResults;
  private volatile Optional<Boolean> restLoginExists = Optional.empty();
  private volatile int restLoginStatus;
  private volatile Optional<String> restLoginFromMatrix = Optional.empty();
  private volatile Optional<Session> session = Optional.empty();

  public RestJrsAdapter(
      RestClient client,
      Config config,
      Platform platform,
      CompatMatrix matrix,
      Optional<Credentials> configured) {
    this(client, config, matrix, configured, new KeystoreInspector(platform, config));
  }

  /**
   * An adapter whose configured credentials are produced on first use: {@code credentials} is
   * called by the first login or re-authentication, and a {@link
   * com.jaspersoft.jrsupgrade.core.secrets.SecretException} it throws surfaces from that request.
   */
  public static RestJrsAdapter withLazyCredentials(
      RestClient client,
      Config config,
      Platform platform,
      CompatMatrix matrix,
      Supplier<Credentials> credentials) {
    return new RestJrsAdapter(
        client,
        config,
        matrix,
        new KeystoreInspector(platform, config),
        Optional.of(Objects.requireNonNull(credentials, "credentials")));
  }

  /** Constructor with an explicit keystore inspector (tests use a fake home layout). */
  public RestJrsAdapter(
      RestClient client,
      Config config,
      CompatMatrix matrix,
      Optional<Credentials> configured,
      KeystoreInspector keystoreInspector) {
    this(
        client,
        config,
        matrix,
        keystoreInspector,
        Objects.requireNonNull(configured, "configured").map(c -> (Supplier<Credentials>) () -> c));
  }

  private RestJrsAdapter(
      RestClient client,
      Config config,
      CompatMatrix matrix,
      KeystoreInspector keystoreInspector,
      Optional<Supplier<Credentials>> configured) {
    this.client = Objects.requireNonNull(client, "client");
    this.config = Objects.requireNonNull(config, "config");
    this.matrix = Objects.requireNonNull(matrix, "matrix");
    this.configured = Objects.requireNonNull(configured, "configured");
    this.keystoreInspector = Objects.requireNonNull(keystoreInspector, "keystoreInspector");
    client.onUnauthorized(this::reauthenticate);
  }

  /**
   * Review finding 2.3: a form session ends with a server restart or a timeout, after which every
   * call answered 401 until the process was restarted. On a 401 in form mode the session is dropped
   * and re-established once with the configured credentials; the client then replays the request.
   * Basic and token modes carry their credential on every request and have nothing to re-establish.
   */
  private boolean reauthenticate() {
    if (config.server().auth().mode() != Config.AuthMode.FORM || configured.isEmpty()) {
      return false;
    }
    session = Optional.empty();
    try {
      login(configured.get().get());
      return true;
    } catch (RestException | JrsUnreachableException e) {
      return false;
    }
  }

  /**
   * Issue #114: a form login leaves a session on the server until it times out, and report output
   * is held in it. Sends {@code GET /logout.html} with the session cookie, once; basic and token
   * modes hold no session. A refused or failed logout is logged at debug and never surfaces.
   */
  @Override
  public void close() {
    session = Optional.empty();
    if (config.server().auth().mode() != Config.AuthMode.FORM || client.sessionCookie().isEmpty()) {
      return;
    }
    try {
      RestClient.Response r = client.get(LOGOUT, "text/html");
      LOG.debug("logout answered HTTP {}", r.status());
    } catch (RuntimeException e) {
      LOG.debug("logout failed: {}", e.getMessage());
    } finally {
      client.clearSession();
    }
  }

  /** The underlying client, e.g. to set the correlation id for a run. */
  public RestClient client() {
    return client;
  }

  // ---------------------------------------------------------------- identity and capabilities

  @Override
  public ServerIdentity identity() {
    synchronized (lock) {
      if (identity == null) {
        identity = fetchIdentity();
      }
      return identity;
    }
  }

  @Override
  public ServerIdentity refreshIdentity() {
    ServerIdentity fresh = fetchIdentity();
    synchronized (lock) {
      identity = fresh;
    }
    return fresh;
  }

  private ServerIdentity fetchIdentity() {
    // serverInfo answers anyone on every supported server, so it is asked without a credential
    // first and reachability never depends on a password (field test 2, D1); a server that wants
    // a login for it gets the configured one, resolved now
    RestClient.Response r = client.getAnonymous(SERVER_INFO);
    if (r.status() == 401 || r.status() == 403) {
      r = client.get(SERVER_INFO);
    }
    if (r.status() >= 500) {
      throw new JrsUnreachableException(
          client.baseUrl(),
          "server answered HTTP " + r.status() + " on GET " + SERVER_INFO,
          "the server may still be starting or its database may be down; check the Tomcat and"
              + " jasperserver logs",
          null);
    }
    client.require2xx(r, "GET", SERVER_INFO);
    return ServerIdentities.parse(client.baseUrl(), r.body());
  }

  @Override
  public Set<Capability> capabilities() {
    synchronized (lock) {
      if (capabilities == null) {
        probe();
      }
      return capabilities;
    }
  }

  /** One line per capability saying how it was decided; for {@code doctor}. */
  public Map<Capability, String> probeResults() {
    synchronized (lock) {
      if (probeResults == null) {
        probe();
      }
      return probeResults;
    }
  }

  /** Capabilities the compat matrix expects for the detected version and edition. */
  public Set<Capability> expectedCapabilities() {
    ServerIdentity id = identity();
    EnumSet<Capability> out = EnumSet.noneOf(Capability.class);
    for (String name : matrix.expectedCapabilities(id.version(), id.edition().name())) {
      try {
        out.add(Capability.valueOf(name.toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException ignored) {
        // a matrix entry naming a capability this build does not know is not an error here
      }
    }
    return Collections.unmodifiableSet(out);
  }

  private void probe() {
    ServerIdentity id = identity();
    ensureSession();
    Set<Capability> expected = expectedCapabilities();
    EnumMap<Capability, String> details = new EnumMap<>(Capability.class);
    EnumSet<Capability> found = EnumSet.noneOf(Capability.class);

    probeEndpoint(Capability.EXPORT_ASYNC, EXPORT + "/" + PROBE_ID + "/state", found, details);
    probeEndpoint(Capability.IMPORT_ASYNC, IMPORT + "/" + PROBE_ID + "/state", found, details);

    String orgs = ORGANIZATIONS + "?limit=1";
    int s = refuseIfUnauthenticated(client.get(orgs).status(), ORGANIZATIONS);
    decide(Capability.ORGS, s == 200 || s == 204, "GET " + orgs, s, found, details);

    boolean restLogin = restLoginExists(id);
    Optional<String> fromMatrix = restLoginFromMatrix;
    if (fromMatrix.isPresent()) {
      if (restLogin) {
        found.add(Capability.REST_LOGIN);
      }
      details.put(Capability.REST_LOGIN, (restLogin ? "present, " : "absent, ") + fromMatrix.get());
    } else {
      decide(
          Capability.REST_LOGIN, restLogin, "POST " + REST_LOGIN, restLoginStatus, found, details);
    }

    // review §3.2 (issue #112): the licence says whether other nodes may exist
    probeLicenseFeatures(found, details);
    boolean known = matrix.find(id.version()).isPresent();
    for (Capability c :
        List.of(Capability.KEYSTORE_ENCRYPTION, Capability.TOKEN_AUTH, Capability.PREAUTH)) {
      boolean present;
      String how;
      if (known) {
        present = expected.contains(c);
        how = "per compat matrix for JRS " + id.version() + " " + id.edition();
      } else if (c == Capability.KEYSTORE_ENCRYPTION) {
        // review §3.1 (issue #112): GET /rest_v2/keys/ answers on any 7.5+ server (REST
        // reference p.128), so a version the matrix does not list is probed rather than assumed
        RestClient.Response keys = client.get(KEYS);
        int keysStatus = refuseIfUnauthenticated(keys.status(), KEYS);
        present = keysStatus == 200 || keysStatus == 204;
        how =
            "JRS "
                + id.version()
                + " is not in the compat matrix; GET "
                + KEYS
                + " answered HTTP "
                + keysStatus
                + " for "
                + id.edition();
      } else {
        // Review finding 2.7: a version the matrix does not list is unknown, not incapable.
        // The two auth modes need server configuration to tell and are assumed absent until
        // probed by hand.
        present = false;
        how =
            "JRS "
                + id.version()
                + " is not in the compat matrix; assumed absent, unknown for "
                + id.edition();
      }
      if (present) {
        found.add(c);
      }
      details.put(
          c,
          (present ? "present" : "absent")
              + " "
              + how
              + " (not probeable without server configuration)");
    }
    capabilities = Collections.unmodifiableSet(found);
    probeResults = Collections.unmodifiableMap(details);
  }

  /**
   * Review §3.2 (issue #112): {@code GET /rest_v2/licenseFeatures} answers 200 with the licence's
   * feature flags on a commercial server ({@code cl} is clustering, {@code mt} multi-tenancy) and
   * 404 on the community edition, which has no licence service. Anything but a parseable 200 is
   * "absent" with the status, never an error: the flag only adds a warning.
   */
  private void probeLicenseFeatures(Set<Capability> found, Map<Capability, String> details) {
    RestClient.Response r = client.get(LICENSE_FEATURES);
    int s = refuseIfUnauthenticated(r.status(), LICENSE_FEATURES);
    if (s != 200) {
      decide(Capability.CLUSTERING, false, "GET " + LICENSE_FEATURES, s, found, details);
      return;
    }
    Wire.LicenseFeatures features;
    try {
      features = Wire.parse(r.body(), Wire.LicenseFeatures.class, "GET", LICENSE_FEATURES);
    } catch (RestException notJson) {
      details.put(
          Capability.CLUSTERING,
          "GET " + LICENSE_FEATURES + " answered HTTP 200 without licence flags (absent)");
      return;
    }
    boolean clustered = Boolean.TRUE.equals(features.cl());
    if (clustered) {
      found.add(Capability.CLUSTERING);
    }
    details.put(
        Capability.CLUSTERING,
        "GET "
            + LICENSE_FEATURES
            + " answered HTTP 200: cl="
            + features.cl()
            + ", mt="
            + features.mt()
            + " ("
            + (clustered ? "present" : "absent")
            + ")");
  }

  /**
   * Review finding 2.7: a present task endpoint answers the probe id with 404 and the JSON error
   * body every JRS returns for a missing task ({@code errorCode}); a server without the endpoint
   * answers 404 too, but with the container's HTML or nothing. Only the former is presence.
   */
  private void probeEndpoint(
      Capability c, String path, Set<Capability> found, Map<Capability, String> details) {
    RestClient.Response r = client.get(path);
    int s = refuseIfUnauthenticated(r.status(), path);
    boolean present = s == 200 || (s == 404 && namesMissingTask(r.body()));
    decide(c, present, "GET " + path, s, found, details);
  }

  static boolean namesMissingTask(String body) {
    if (body == null || !body.strip().startsWith("{")) {
      return false;
    }
    try {
      Wire.ErrorBody error = Wire.parse(body, Wire.ErrorBody.class, "GET", "probe");
      return error.errorCode() != null && !error.errorCode().isBlank();
    } catch (RestException notJson) {
      return false;
    }
  }

  /** True when {@code version} (major.minor[.patch]) is at least {@code major.minor}. */
  static boolean atLeast(String version, int major, int minor) {
    int[] parsed = {0, 0};
    int index = 0;
    int value = -1;
    for (int i = 0; i < version.length() && index < 2; i++) {
      char c = version.charAt(i);
      if (Character.isDigit(c)) {
        value = (value < 0 ? 0 : value * 10) + (c - '0');
      } else {
        if (value < 0) {
          break;
        }
        parsed[index++] = value;
        value = -1;
        if (c != '.') {
          break;
        }
      }
    }
    if (index < 2 && value >= 0) {
      parsed[index++] = value;
    }
    if (index == 0) {
      return false;
    }
    return parsed[0] > major || (parsed[0] == major && parsed[1] >= minor);
  }

  /**
   * A 401 or 403 on a probe says the credentials in {@code server.auth} were refused, not that the
   * capability is absent. Reporting it as absent would let strategy selection fall back to the
   * vendor tools and stop the service over a typo in a password, so it is a hard failure instead.
   */
  private static int refuseIfUnauthenticated(int status, String path) {
    if (status == 401 || status == 403) {
      throw new RestException(
          status,
          "GET",
          path,
          "GET "
              + path
              + " answered HTTP "
              + status
              + ": the server refused the credentials in server.auth; check the user name and"
              + " the password reference before retrying");
    }
    return status;
  }

  private static void decide(
      Capability c,
      boolean present,
      String request,
      int status,
      Set<Capability> found,
      Map<Capability, String> details) {
    if (present) {
      found.add(c);
    }
    details.put(
        c, request + " answered HTTP " + status + " (" + (present ? "present" : "absent") + ")");
  }

  private boolean restLoginExists(ServerIdentity id) {
    Optional<Boolean> known = restLoginExists;
    if (known.isPresent()) {
      return known.get();
    }
    boolean exists;
    if (matrix.find(id.version()).isPresent()) {
      // Issue #46: a listed version is decided by the matrix; no login without credentials.
      exists = expectedCapabilities().contains(Capability.REST_LOGIN);
      restLoginFromMatrix =
          Optional.of("per compat matrix for JRS " + id.version() + " " + id.edition());
    } else {
      // A version the matrix does not list: POST with no credentials. An existing endpoint answers
      // 401/400/403 (or 200 on odd builds), a server without it answers 404. A GET is unreliable
      // because 10.x answers 404 to GET as well.
      int s = client.post(REST_LOGIN, "application/x-www-form-urlencoded", "").status();
      restLoginStatus = s;
      exists = s != 404 && s < 500;
    }
    restLoginExists = Optional.of(exists);
    return exists;
  }

  // ---------------------------------------------------------------- login

  @Override
  public Session login(Credentials credentials) {
    Objects.requireNonNull(credentials, "credentials");
    String principal =
        credentials
            .organization()
            .map(o -> credentials.username() + "|" + o)
            .orElse(credentials.username());
    Session s =
        switch (config.server().auth().mode()) {
          case BASIC -> {
            client.useBasic(principal, credentials.password());
            verifyAuthenticated();
            yield new Session(Session.AuthMode.BASIC, Optional.empty(), Instant.now());
          }
          case TOKEN -> {
            client.useToken(credentials.password(), config.server().auth().tokenLocation());
            verifyAuthenticated();
            yield new Session(Session.AuthMode.TOKEN, Optional.empty(), Instant.now());
          }
          case FORM -> formLogin(principal, credentials);
        };
    session = Optional.of(s);
    return s;
  }

  private Session formLogin(String principal, Credentials credentials) {
    client.clearSession();
    Optional<Boolean> known = restLoginExists;
    boolean rest = known.orElse(true);
    RestClient.Response r =
        client.formLogin(rest ? REST_LOGIN : FORM_LOGIN, principal, credentials.password());
    if (known.isEmpty()) {
      // Issue #46: the credentialed login itself tells whether /rest_v2/login exists, instead of a
      // login attempt without credentials; a server without the endpoint answers it 404.
      if (r.status() == 404) {
        restLoginStatus = 404;
        restLoginExists = Optional.of(false);
        rest = false;
        client.clearSession();
        r = client.formLogin(FORM_LOGIN, principal, credentials.password());
      } else if (r.status() < 500) {
        restLoginStatus = r.status();
        restLoginExists = Optional.of(true);
      }
    }
    String path = rest ? REST_LOGIN : FORM_LOGIN;
    Optional<String> cookie = client.sessionCookie();
    boolean accepted;
    if (rest) {
      accepted = r.ok();
    } else {
      // Spring Security answers 302 to the home page on success and to login.html?error on failure
      String location = r.header("Location").orElse("").toLowerCase(Locale.ROOT);
      accepted = (r.ok() || r.status() == 302) && !location.contains("error");
    }
    if (!accepted || cookie.isEmpty()) {
      throw new RestException(
          r.status(),
          "POST",
          path,
          "login rejected for user "
              + principal
              + " (HTTP "
              + r.status()
              + (cookie.isEmpty() ? ", no session cookie" : "")
              + ")");
    }
    return new Session(Session.AuthMode.FORM, cookie, Instant.now());
  }

  private void verifyAuthenticated() {
    String path = RESOURCES + "?folderUri=%2F&recursive=false&limit=1";
    RestClient.Response r = client.get(path);
    if (r.status() != 204) {
      client.require2xx(r, "GET", path);
    }
  }

  /** In form mode, logs in with the configured credentials before the first authenticated call. */
  private void ensureSession() {
    if (config.server().auth().mode() == Config.AuthMode.FORM
        && session.isEmpty()
        && configured.isPresent()) {
      login(configured.get().get());
    }
  }

  // ---------------------------------------------------------------- export

  @Override
  public Handles.ExportHandle startExport(ExportRequest request) {
    Objects.requireNonNull(request, "request");
    ensureSession();
    boolean everything = request.scope() == ExportRequest.Scope.EVERYTHING || request.fullServer();
    List<String> parameters = new ArrayList<>();
    if (everything) {
      parameters.add("everything");
    }
    parameters.add("repository-permissions");
    if (request.includeUsersRoles()) {
      parameters.add("role-users");
    }
    if (request.includeAccessEvents()) {
      parameters.add("include-access-events");
    }
    if (request.includeAuditEvents()) {
      parameters.add("include-audit-events");
    }
    if (request.includeMonitoring()) {
      parameters.add("include-monitoring-events");
    }
    if (request.includeSettings()) {
      parameters.add("include-server-settings");
    }
    if (request.skipDependentResources()) {
      parameters.add("skip-dependent-resources");
    }
    if (request.skipFavoriteResources()) {
      parameters.add("skip-favorite-resources");
    }
    List<String> uris = new ArrayList<>(new TreeSet<>(request.uris()));
    if (uris.isEmpty() && !everything) {
      uris.add("/");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("uris", uris);
    body.put("roles", List.of());
    body.put("users", List.of());
    body.put("parameters", parameters);
    // REST reference 10.1 p.110: the alias must exist in the importing server's keystore
    request.keyAlias().ifPresent(alias -> body.put("keyAlias", alias));
    // REST reference 10.1 p.110: only that organisation's resources, users and roles
    request.organization().ifPresent(org -> body.put("organization", org));
    RestClient.Response r = client.require2xx(client.postJson(EXPORT, body), "POST", EXPORT);
    Wire.AsyncState state = Wire.parse(r.body(), Wire.AsyncState.class, "POST", EXPORT);
    if (state.id() == null || state.id().isBlank()) {
      throw new RestException(r.status(), "POST", EXPORT, "export started but no task id returned");
    }
    if (state.fileName() != null) {
      exportFileNames.put(state.id(), state.fileName());
    }
    return new Handles.ExportHandle(state.id());
  }

  @Override
  public Handles.ExportStatus pollExport(Handles.ExportHandle handle) {
    String path = EXPORT + "/" + RestClient.encodeQuery(handle.id()) + "/state";
    RestClient.Response r = client.require2xx(client.get(path), "GET", path);
    Wire.AsyncState state = Wire.parse(r.body(), Wire.AsyncState.class, "GET", path);
    if (state.fileName() != null) {
      exportFileNames.put(handle.id(), state.fileName());
    }
    // a failed export carries its cause in errorDescriptor, not in message (field test 2, E3)
    Optional<Wire.ErrorDescriptor> descriptor = Optional.ofNullable(state.errorDescriptor());
    return new Handles.ExportStatus(
        phase(state.phase()),
        Optional.ofNullable(state.message())
            .or(() -> descriptor.map(Wire.ErrorDescriptor::message))
            .filter(m -> !m.isBlank()),
        Optional.ofNullable(state.fileName()),
        Optional.ofNullable(state.errorCode())
            .or(() -> descriptor.map(Wire.ErrorDescriptor::errorCode))
            .filter(c -> !c.isBlank()));
  }

  @Override
  public Path downloadExport(Handles.ExportHandle handle, Path target) {
    return downloadExport(handle, target, () -> false);
  }

  @Override
  public Path downloadExport(Handles.ExportHandle handle, Path target, BooleanSupplier cancelled) {
    Objects.requireNonNull(target, "target");
    String fileName = exportFileNames.getOrDefault(handle.id(), DEFAULT_EXPORT_FILE);
    String path =
        EXPORT + "/" + RestClient.encodeQuery(handle.id()) + "/" + RestClient.encodeQuery(fileName);
    client.require2xx(client.getToFile(path, "application/zip", target, cancelled), "GET", path);
    return target;
  }

  // ---------------------------------------------------------------- import

  @Override
  public Handles.ImportHandle startImport(ImportRequest request, Path archive) {
    return startImport(request, archive, () -> false);
  }

  @Override
  public Handles.ImportHandle startImport(
      ImportRequest request, Path archive, BooleanSupplier cancelled) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(archive, "archive");
    ensureSession();
    String path =
        IMPORT
            + "?update="
            + request.update()
            + "&skipUserUpdate="
            + request.skipUserUpdate()
            + "&includeAccessEvents="
            + request.includeAccessEvents()
            + "&includeAuditEvents="
            + request.includeAuditEvents()
            + "&includeMonitoringEvents="
            + request.includeMonitoring()
            // The REST reference 10.1 p.117 spells this "includeServerSetting"; the server's own
            // ImportJaxrsService (10.0.0, jasperserver-jax-rs-rest) declares only the plural
            // @QueryParam("includeServerSettings") and @FormDataParam("include-server-settings"),
            // so the plural is what the server honours and the reference is the one in error
            // (issue #110, checked 2026-09-19).
            + "&includeServerSettings="
            + request.includeSettings()
            + "&skipThemes="
            + request.skipThemes()
            // the server default; omitted so that older servers see the request they always saw
            + (request.brokenDependencies() == BrokenDependencies.FAIL
                ? ""
                : "&brokenDependencies=" + request.brokenDependencies().wire())
            // REST reference 10.1 p.117: the key the archive was encrypted with
            + request.keyAlias().map(a -> "&keyAlias=" + RestClient.encodeQuery(a)).orElse("")
            // REST reference 10.1 pp.115, 121: the target organisation, merged when the ids differ
            + request
                .organization()
                .map(
                    o ->
                        "&organization="
                            + RestClient.encodeQuery(o)
                            + (request.mergeOrganization() ? "&mergeOrganization=true" : ""))
                .orElse("");
    RestClient.Response r =
        client.require2xx(
            client.postBytesFromFile(path, archive, "application/zip", cancelled), "POST", path);
    Wire.AsyncState state = Wire.parse(r.body(), Wire.AsyncState.class, "POST", IMPORT);
    if (state.id() == null || state.id().isBlank()) {
      throw new RestException(r.status(), "POST", IMPORT, "import started but no task id returned");
    }
    return new Handles.ImportHandle(state.id());
  }

  @Override
  public Handles.ImportStatus pollImport(Handles.ImportHandle handle) {
    String path = IMPORT + "/" + RestClient.encodeQuery(handle.id()) + "/state";
    RestClient.Response r = client.require2xx(client.get(path), "GET", path);
    Wire.AsyncState state = Wire.parse(r.body(), Wire.AsyncState.class, "GET", path);
    // a pending import reports its code nested under "error" (REST 10.1 p.120); a failed one may
    // use either shape, and the nested one wins when both are present
    Optional<Wire.ImportError> error = Optional.ofNullable(state.error());
    return new Handles.ImportStatus(
        phase(state.phase()),
        Optional.ofNullable(state.message()),
        error.map(Wire.ImportError::code).or(() -> Optional.ofNullable(state.errorCode())),
        error.map(Wire.ImportError::parameters).orElse(List.of()));
  }

  @Override
  public void cancelImport(Handles.ImportHandle handle) {
    Objects.requireNonNull(handle, "handle");
    ensureSession();
    String path = IMPORT + "/" + RestClient.encodeQuery(handle.id());
    RestClient.Response r = client.delete(path);
    // 404: the server already dropped the task, which is the state we want
    if (r.status() != 404) {
      client.require2xx(r, "DELETE", path);
    }
  }

  static Handles.Phase phase(String raw) {
    String p =
        raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    return switch (p) {
      case "inprogress", "running", "queued" -> Handles.Phase.INPROGRESS;
      case "ready", "finished", "success", "succeeded", "completed" -> Handles.Phase.READY;
      case "pending" -> Handles.Phase.PENDING;
      default -> Handles.Phase.FAILED;
    };
  }

  // ---------------------------------------------------------------- keystore and health

  @Override
  public KeystoreInfo keystore() {
    boolean encrypted = true;
    Optional<String> version = Optional.empty();
    try {
      encrypted = capabilities().contains(Capability.KEYSTORE_ENCRYPTION);
      version = Optional.of(identity().version());
    } catch (JrsUnreachableException e) {
      // server down: still worth inspecting the files, doctor reports reachability separately
    }
    if (!encrypted) {
      boolean known = version.flatMap(matrix::find).isPresent();
      return KeystoreInfo.absent(
          "JRS "
              + version.orElse("?")
              + " has no KEYSTORE_ENCRYPTION capability ("
              + (known ? "pre 7.5" : "not in the compat matrix and older than 7.5")
              + "); repository passwords are not keystore-encrypted, nothing to carry across");
    }
    return keystoreInspector.inspect();
  }

  @Override
  public HealthReport health() {
    List<HealthReport.Item> items = new ArrayList<>();
    Instant start = Instant.now();
    ServerIdentity id;
    try {
      id = identity();
    } catch (JrsUnreachableException e) {
      items.add(
          new HealthReport.Item(
              "serverInfo", HealthReport.Status.FAIL, e.getMessage(), e.remediation()));
      return new HealthReport(false, Duration.between(start, Instant.now()), items);
    } catch (RestException e) {
      items.add(
          new HealthReport.Item(
              "serverInfo",
              HealthReport.Status.FAIL,
              e.getMessage(),
              "check server.baseUrl points at the JasperReports Server web application"));
      return new HealthReport(false, Duration.between(start, Instant.now()), items);
    }
    Duration latency = Duration.between(start, Instant.now());
    items.add(
        new HealthReport.Item(
            "serverInfo",
            HealthReport.Status.PASS,
            "JRS "
                + id.version()
                + " "
                + id.edition()
                + " "
                + id.tenancy()
                + " answered in "
                + latency.toMillis()
                + " ms",
            ""));

    if (configured.isEmpty()) {
      items.add(
          new HealthReport.Item(
              "login",
              HealthReport.Status.WARN,
              "no credentials configured; authenticated checks skipped",
              "set server.auth.username and server.auth.passwordRef"));
      return new HealthReport(true, latency, items);
    }
    try {
      Credentials credentials = configured.get().get();
      Session s = login(credentials);
      items.add(
          new HealthReport.Item(
              "login",
              HealthReport.Status.PASS,
              "authenticated as " + credentials.username() + " (" + s.mode() + ")",
              ""));
    } catch (RestException | JrsUnreachableException e) {
      items.add(
          new HealthReport.Item(
              "login",
              HealthReport.Status.FAIL,
              e.getMessage(),
              "check server.auth.mode, server.auth.username and the secret behind"
                  + " server.auth.passwordRef"));
      return new HealthReport(true, latency, items);
    }
    try {
      List<String> children = listFolder("/");
      items.add(
          new HealthReport.Item(
              "repository",
              HealthReport.Status.PASS,
              "listed / (" + children.size() + " entries)",
              ""));
    } catch (RestException | JrsUnreachableException e) {
      items.add(
          new HealthReport.Item(
              "repository",
              HealthReport.Status.FAIL,
              e.getMessage(),
              "the account must be able to read the repository root"));
    }
    boolean scheduler = schedulerReachable();
    items.add(
        new HealthReport.Item(
            "scheduler",
            scheduler ? HealthReport.Status.PASS : HealthReport.Status.FAIL,
            scheduler ? "GET " + JOBS + " answered" : "GET " + JOBS + " did not answer 200",
            scheduler ? "" : "check that the scheduler is enabled and the account may list jobs"));
    return new HealthReport(true, latency, items);
  }

  // ---------------------------------------------------------------- read-only helpers

  @Override
  public List<String> listFolder(String folderUri) {
    Objects.requireNonNull(folderUri, "folderUri");
    ensureSession();
    String path =
        RESOURCES
            + "?folderUri="
            + RestClient.encodeQuery(folderUri)
            + "&recursive=false&limit=100";
    RestClient.Response r = client.get(path);
    if (r.status() == 204) {
      return List.of();
    }
    client.require2xx(r, "GET", path);
    Wire.ResourceLookupList list =
        Wire.parse(r.body(), Wire.ResourceLookupList.class, "GET", RESOURCES);
    List<String> uris = new ArrayList<>();
    if (list.resourceLookup() != null) {
      for (Wire.ResourceLookup l : list.resourceLookup()) {
        if (l != null && l.uri() != null) {
          uris.add(l.uri());
        }
      }
    }
    return List.copyOf(uris);
  }

  /** Page size of the recursive listing; the server caps a page and paginates with offset. */
  static final int LIST_PAGE = 500;

  /**
   * Issue #100: {@code GET /rest_v2/resources?folderUri=..&recursive=true} in pages of {@link
   * #LIST_PAGE}, following the {@code Total-Count} header the server sends (REST reference 10.1,
   * resources service); without the header a short page ends the walk. Folders and resources alike;
   * 204 means an empty subtree.
   */
  @Override
  public List<String> listTree(String folderUri) {
    Objects.requireNonNull(folderUri, "folderUri");
    ensureSession();
    List<String> uris = new ArrayList<>();
    int offset = 0;
    while (true) {
      String path =
          RESOURCES
              + "?folderUri="
              + RestClient.encodeQuery(folderUri)
              + "&recursive=true&limit="
              + LIST_PAGE
              + "&offset="
              + offset;
      RestClient.Response r = client.get(path);
      if (r.status() == 204) {
        break;
      }
      client.require2xx(r, "GET", path);
      Wire.ResourceLookupList list =
          Wire.parse(r.body(), Wire.ResourceLookupList.class, "GET", RESOURCES);
      int returned = 0;
      if (list.resourceLookup() != null) {
        for (Wire.ResourceLookup l : list.resourceLookup()) {
          returned++;
          if (l != null && l.uri() != null && !l.uri().equals(folderUri)) {
            uris.add(l.uri());
          }
        }
      }
      offset += returned;
      int reached = offset;
      Optional<Integer> total = r.header("Total-Count").flatMap(RestJrsAdapter::parseInt);
      boolean more = total.map(t -> reached < t).orElse(returned == LIST_PAGE);
      if (returned == 0 || !more) {
        break;
      }
    }
    return List.copyOf(uris);
  }

  private static Optional<Integer> parseInt(String text) {
    try {
      return Optional.of(Integer.parseInt(text.strip()));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  @Override
  public boolean resourceExists(String uri) {
    Objects.requireNonNull(uri, "uri");
    ensureSession();
    String path = RESOURCES + RestClient.encodePath(uri);
    RestClient.Response r = client.get(path);
    if (r.status() == 404) {
      return false;
    }
    if (r.status() != 204) {
      client.require2xx(r, "GET", path);
    }
    return true;
  }

  @Override
  public Path runReportToPdf(String reportUri, Path target) {
    Objects.requireNonNull(reportUri, "reportUri");
    Objects.requireNonNull(target, "target");
    ensureSession();
    String path = REPORTS + RestClient.encodePath(reportUri) + ".pdf";
    client.require2xx(client.getToFile(path, "application/pdf", target), "GET", path);
    return target;
  }

  @Override
  public boolean schedulerReachable() {
    try {
      ensureSession();
      int s = client.get(JOBS).status();
      return s == 200 || s == 204;
    } catch (JrsUnreachableException | RestException e) {
      return false;
    }
  }

  // ---------------------------------------------------------------- mutating helpers (Steps only)

  @Override
  public void createFolder(String folderUri, String label) {
    Objects.requireNonNull(folderUri, "folderUri");
    Objects.requireNonNull(label, "label");
    ensureSession();
    String parent = parentOf(folderUri);
    String path = RESOURCES + RestClient.encodePath(parent) + "?createFolders=true";
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("label", label);
    body.put("description", "created by jrs-upgrade smoke");
    String json = Json.write(body);
    client.require2xx(client.post(path, FOLDER_TYPE, json), "POST", path);
  }

  @Override
  public void uploadJrxmlReport(String folderUri, String label, Path jrxml) {
    Objects.requireNonNull(folderUri, "folderUri");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(jrxml, "jrxml");
    ensureSession();
    String id = slug(label);
    String path =
        RESOURCES
            + RestClient.encodePath(stripTrailingSlash(folderUri) + "/" + id)
            + "?createFolders=true&overwrite=true";
    Path tmp;
    try {
      tmp = Files.createTempFile("jrs-upgrade-upload", ".json");
    } catch (IOException e) {
      throw new RestException(0, "PUT", path, "cannot create upload staging file", e);
    }
    try {
      writeReportUnit(tmp, label, jrxml);
      client.require2xx(client.putFromFile(path, REPORT_UNIT_TYPE, tmp), "PUT", path);
    } catch (IOException e) {
      throw new RestException(0, "PUT", path, "cannot stage " + jrxml + ": " + e.getMessage(), e);
    } finally {
      try {
        Files.deleteIfExists(tmp);
      } catch (IOException ignored) {
        // a leftover staging file in the temp dir is harmless
      }
    }
  }

  @Override
  public void deleteResource(String uri) {
    Objects.requireNonNull(uri, "uri");
    ensureSession();
    String path = RESOURCES + RestClient.encodePath(uri);
    client.require2xx(client.delete(path), "DELETE", path);
  }

  /**
   * Streams the JRXML through a Base64 encoder into a reportUnit descriptor; nothing is buffered.
   */
  private static void writeReportUnit(Path target, String label, Path jrxml) throws IOException {
    Map<String, Object> head = new LinkedHashMap<>();
    head.put("label", label);
    head.put("description", "uploaded by jrs-upgrade smoke --mutating");
    String prefix = Json.write(head);
    // drop the closing brace and open the nested jrxml object
    prefix =
        prefix.substring(0, prefix.length() - 1)
            + ",\"jrxml\":{\"jrxmlFile\":{\"type\":\"jrxml\",\"content\":\"";
    String suffix = "\"}}}";
    try (OutputStream out = Files.newOutputStream(target)) {
      out.write(prefix.getBytes(StandardCharsets.UTF_8));
      OutputStream b64 = Base64.getEncoder().wrap(new NonClosingOutputStream(out));
      Files.copy(jrxml, b64);
      b64.close();
      out.write(suffix.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static final class NonClosingOutputStream extends java.io.FilterOutputStream {
    NonClosingOutputStream(OutputStream out) {
      super(out);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      out.write(b, off, len);
    }

    @Override
    public void close() throws IOException {
      out.flush();
    }
  }

  static String parentOf(String uri) {
    String u = stripTrailingSlash(uri);
    int slash = u.lastIndexOf('/');
    return slash <= 0 ? "/" : u.substring(0, slash);
  }

  static String slug(String label) {
    String s = label.strip().replaceAll("[^A-Za-z0-9_.-]+", "_");
    return s.isEmpty() ? "report" : s;
  }

  private static String stripTrailingSlash(String s) {
    return s.length() > 1 && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}
