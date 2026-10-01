package com.jaspersoft.jrsupgrade.jrs.rest;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.engine.CancellationToken;
import com.jaspersoft.jrsupgrade.core.json.Json;
import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.core.secrets.Secret;
import com.jaspersoft.jrsupgrade.core.secrets.SecretResolver;
import com.jaspersoft.jrsupgrade.jrs.api.JrsUnreachableException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Thin wrapper over {@link HttpClient} for the JasperReports Server REST v2 API (spec §7.5).
 * Invariants: every request is resolved against {@code server.baseUrl} and, in {@code isolated}
 * network mode, refused with {@link IsolatedModeViolation} (after notifying the audit hook) when
 * its host differs from the base URL's host, before any bytes leave the process; every request
 * carries {@code X-JrsUpgrade-Correlation}; redirects are never followed, so a redirect cannot
 * escape the allowlist; no exception raised here carries an Authorization header, a password, a
 * token or an unredacted body; downloads and uploads stream through {@link
 * HttpResponse.BodyHandlers#ofFile} and {@link HttpRequest.BodyPublishers#ofFile}; the Basic header
 * is computed once and the password chars are zeroed immediately afterwards. The client is safe for
 * use from several threads; the auth mode may be switched at any time and applies to subsequent
 * requests.
 */
public final class RestClient {

  public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);
  public static final Duration DEFAULT_DOWNLOAD_TIMEOUT = Duration.ofHours(2);

  /** Longest pause between two body chunks of a transfer before it counts as stalled. */
  public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(2);

  /** How often a watched transfer looks at the cancel signal and the idle clock. */
  static final Duration WATCH_TICK = Duration.ofMillis(250);

  /** The JDK property that forbids Basic authentication on CONNECT tunnels by default. */
  static final String TUNNELING_PROPERTY = "jdk.http.auth.tunneling.disabledSchemes";

  public static final String CORRELATION_HEADER = "X-JrsUpgrade-Correlation";
  public static final String REMOTE_DOMAIN_HEADER = "X-REMOTE-DOMAIN";

  /** The pre-authentication header, same name as the query parameter (ADR-0018). */
  private static final String PREAUTH_HEADER = "pp";

  public static final String SESSION_COOKIE = "JSESSIONID";
  public static final String JSON = "application/json";

  private static final String REMEDIATION =
      "check that server.baseUrl is correct, that the JasperReports Server service is running and"
          + " accepting connections, and that network.mode / network.proxy match how this host"
          + " reaches the server";

  /** A completed exchange; {@code body} is empty for responses streamed to a file. */
  public record Response(int status, String body, Map<String, List<String>> headers) {

    public Response {
      Objects.requireNonNull(body, "body");
      headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
    }

    public boolean ok() {
      return status >= 200 && status < 300;
    }

    /** First value of a header, case-insensitively. */
    public Optional<String> header(String name) {
      for (Map.Entry<String, List<String>> e : headers.entrySet()) {
        if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
          return Optional.of(e.getValue().get(0));
        }
      }
      return Optional.empty();
    }
  }

  private final HttpClient http;
  private final CookieManager cookies;
  private final String base;
  private final URI baseUrl;
  private final String baseHost;
  private final Config.NetworkMode mode;
  private final Consumer<String> auditHook;
  private final Redactor redactor;
  private final Duration requestTimeout;
  private final Duration downloadTimeout;
  private final Duration idleTimeout;
  private volatile String correlationId;
  private volatile Optional<BooleanSupplier> reauthenticate = Optional.empty();
  private final ThreadLocal<Boolean> reauthenticating = ThreadLocal.withInitial(() -> false);
  private volatile Optional<String> basicHeader = Optional.empty();
  private volatile Optional<String> tokenParam = Optional.empty();
  private volatile Optional<String> tokenHeader = Optional.empty();

  /**
   * A credential whose secret is resolved by the first request that carries it (field test 2, D1):
   * connecting never needs the secret, and a resolution that fails surfaces from that request as
   * its own exception, with the credential still pending for the next one.
   */
  private volatile Optional<Runnable> pendingAuth = Optional.empty();

  private RestClient(Builder b) {
    this.baseUrl = b.baseUrl;
    this.base = stripTrailingSlash(b.baseUrl.toString());
    this.baseHost = hostOf(b.baseUrl);
    this.mode = b.mode;
    this.auditHook = b.auditHook;
    this.redactor = b.redactor;
    this.requestTimeout = b.requestTimeout;
    this.downloadTimeout = b.downloadTimeout;
    this.idleTimeout = b.idleTimeout;
    this.correlationId = b.correlationId;
    this.cookies = new CookieManager();
    HttpClient.Builder hb =
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(cookies);
    if (b.proxyHost.isPresent()) {
      hb.proxy(proxySelector(b.proxyHost.get(), b.proxyPort, b.noProxy));
      if (b.proxyUser.isPresent()) {
        // The JDK disables Basic on CONNECT tunnels unless told otherwise, so every https://
        // base URL through an authenticated proxy answered 407 (review finding 2.9). The property
        // is read once, on the first request of the process; it is set here, before any.
        System.setProperty(TUNNELING_PROPERTY, "");
        hb.authenticator(proxyAuthenticator(b.proxyUser.get(), b.proxyPassword));
      }
    }
    b.trustStore.ifPresent(path -> hb.sslContext(sslContext(path, b.trustStorePassword)));
    this.http = hb.build();
  }

  // ---------------------------------------------------------------- construction

  public static Builder builder(URI baseUrl) {
    return new Builder(baseUrl);
  }

  /**
   * A builder pre-populated from {@code server.baseUrl} and the {@code network:} block; proxy and
   * trust-store passwords are resolved through {@code secrets} and registered with {@code
   * redactor}. Auth is not configured here (see {@link #useBasic}, {@link #useToken}, {@link
   * #formLogin}).
   *
   * @throws com.jaspersoft.jrsupgrade.core.config.ConfigException when {@code server.baseUrl} is
   *     unset
   */
  public static Builder builder(Config config, SecretResolver secrets, Redactor redactor) {
    URI baseUrl =
        config
            .server()
            .baseUrl()
            .orElseThrow(
                () ->
                    new com.jaspersoft.jrsupgrade.core.config.ConfigException(
                        "server.baseUrl is not set",
                        "run jrs-upgrade init or set server.baseUrl in config.yaml"));
    Builder b = new Builder(baseUrl).redactor(redactor).networkMode(config.network().mode());
    Config.Proxy proxy = config.network().proxy();
    if (proxy.host().isPresent()) {
      Optional<Secret> pw = proxy.passwordRef().map(secrets::resolve);
      pw.ifPresent(redactor::register);
      b.proxy(proxy.host().get(), proxy.port().orElse(8080), proxy.username(), pw, proxy.noProxy());
    }
    Config.TrustStore ts = config.network().trustStore();
    if (ts.path().isPresent()) {
      Optional<Secret> pw = ts.passwordRef().map(secrets::resolve);
      pw.ifPresent(redactor::register);
      b.trustStore(ts.path().get(), pw);
    }
    return b;
  }

  /** Fluent configuration; every setter has a safe default. */
  public static final class Builder {
    private final URI baseUrl;
    private Config.NetworkMode mode = Config.NetworkMode.DEFAULT;
    private Consumer<String> auditHook = s -> {};
    private Redactor redactor = Redactor.global();
    private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
    private Duration downloadTimeout = DEFAULT_DOWNLOAD_TIMEOUT;
    private Duration idleTimeout = DEFAULT_IDLE_TIMEOUT;
    private String correlationId = "";
    private Optional<String> proxyHost = Optional.empty();
    private int proxyPort;
    private List<String> noProxy = List.of();
    private Optional<String> proxyUser = Optional.empty();
    private Optional<Secret> proxyPassword = Optional.empty();
    private Optional<Path> trustStore = Optional.empty();
    private Optional<Secret> trustStorePassword = Optional.empty();

    private Builder(URI baseUrl) {
      this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
      if (baseUrl.getHost() == null) {
        throw new IllegalArgumentException("server.baseUrl has no host: " + baseUrl);
      }
    }

    public Builder networkMode(Config.NetworkMode mode) {
      this.mode = Objects.requireNonNull(mode, "mode");
      return this;
    }

    /** Receives one line per refused request in isolated mode. */
    public Builder auditHook(Consumer<String> hook) {
      this.auditHook = Objects.requireNonNull(hook, "hook");
      return this;
    }

    public Builder redactor(Redactor redactor) {
      this.redactor = Objects.requireNonNull(redactor, "redactor");
      return this;
    }

    public Builder requestTimeout(Duration timeout) {
      this.requestTimeout = Objects.requireNonNull(timeout, "timeout");
      return this;
    }

    public Builder downloadTimeout(Duration timeout) {
      this.downloadTimeout = Objects.requireNonNull(timeout, "timeout");
      return this;
    }

    public Builder correlationId(String id) {
      this.correlationId = Objects.requireNonNull(id, "id");
      return this;
    }

    /** The secret, when present, must stay open for the life of the client. */
    public Builder proxy(
        String host, int port, Optional<String> username, Optional<Secret> password) {
      return proxy(host, port, username, password, List.of());
    }

    /** As above, with the hosts (bare name or {@code .suffix}) that bypass the proxy. */
    public Builder proxy(
        String host,
        int port,
        Optional<String> username,
        Optional<Secret> password,
        List<String> noProxy) {
      this.proxyHost = Optional.of(Objects.requireNonNull(host, "host"));
      this.proxyPort = port;
      this.proxyUser = Objects.requireNonNull(username, "username");
      this.proxyPassword = Objects.requireNonNull(password, "password");
      this.noProxy = List.copyOf(Objects.requireNonNull(noProxy, "noProxy"));
      return this;
    }

    /** Longest silence a transfer tolerates before it counts as stalled. */
    public Builder idleTimeout(Duration timeout) {
      this.idleTimeout = Objects.requireNonNull(timeout, "timeout");
      return this;
    }

    /** A JKS or PKCS#12 file holding the CA certificates to trust; read once at build time. */
    public Builder trustStore(Path path, Optional<Secret> password) {
      this.trustStore = Optional.of(Objects.requireNonNull(path, "path"));
      this.trustStorePassword = Objects.requireNonNull(password, "password");
      return this;
    }

    public RestClient build() {
      return new RestClient(this);
    }
  }

  // ---------------------------------------------------------------- auth

  /** Sends {@code Authorization: Basic} on every subsequent request. */
  public void useBasic(String username, Secret password) {
    Objects.requireNonNull(username, "username");
    char[] pw = password.chars();
    byte[] raw;
    try {
      char[] joined = new char[username.length() + 1 + pw.length];
      username.getChars(0, username.length(), joined, 0);
      joined[username.length()] = ':';
      System.arraycopy(pw, 0, joined, username.length() + 1, pw.length);
      ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(joined));
      raw = new byte[encoded.remaining()];
      encoded.get(raw);
      encoded.rewind();
      while (encoded.hasRemaining()) {
        encoded.put((byte) 0);
      }
      Arrays.fill(joined, '\0');
    } finally {
      Arrays.fill(pw, '\0');
    }
    String header = "Basic " + Base64.getEncoder().encodeToString(raw);
    Arrays.fill(raw, (byte) 0);
    redactor.register(header);
    redactor.register(password);
    this.tokenParam = Optional.empty();
    this.tokenHeader = Optional.empty();
    this.basicHeader = Optional.of(header);
  }

  /**
   * As {@link #useBasic(String, Secret)}, with the password supplied by the first request that
   * carries it. {@code password} is called at most once per successful resolution.
   */
  public void useBasic(String username, Supplier<Secret> password) {
    Objects.requireNonNull(username, "username");
    Objects.requireNonNull(password, "password");
    clearAuth();
    this.pendingAuth = Optional.of(() -> useBasic(username, password.get()));
  }

  /** As {@link #useToken(Secret, Config.TokenLocation)}, resolved by the first request. */
  public void useToken(Supplier<Secret> token, Config.TokenLocation location) {
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(location, "location");
    clearAuth();
    this.pendingAuth = Optional.of(() -> useToken(token.get(), location));
  }

  /** Resolves a pending credential before a request that will carry it. */
  private synchronized void applyPendingAuth() {
    Optional<Runnable> pending = pendingAuth;
    if (pending.isPresent()) {
      pending.get().run();
      pendingAuth = Optional.empty();
    }
  }

  /**
   * {@code GET} with no credential at all: no pending credential is resolved, no Basic header,
   * token or cookie-independent auth is sent. For endpoints the server answers to anyone, such as
   * {@code serverInfo}, so that reachability can be judged without a password.
   */
  public Response getAnonymous(String path) {
    URI uri = resolve(path, true);
    enforceAllowlist(uri);
    HttpRequest request =
        request(
            uri,
            "GET",
            JSON,
            Optional.empty(),
            HttpRequest.BodyPublishers.noBody(),
            requestTimeout,
            true);
    return exchange(
        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), "GET", uri);
  }

  /** Appends {@code pp=<token>} to every subsequent request (token / pre-authentication). */
  public void useToken(Secret token) {
    useToken(token, Config.TokenLocation.QUERY);
  }

  /**
   * Sends {@code pp=<token>} on every subsequent request, as a query parameter or as the {@code pp}
   * request header (issue #45, ADR-0018); the token and its encoded form are registered with the
   * redactor.
   */
  public void useToken(Secret token, Config.TokenLocation location) {
    Objects.requireNonNull(location, "location");
    char[] chars = token.chars();
    String raw;
    String encoded;
    try {
      raw = new String(chars);
      encoded = URLEncoder.encode(raw, StandardCharsets.UTF_8);
    } finally {
      Arrays.fill(chars, '\0');
    }
    redactor.register(token);
    redactor.register(encoded);
    this.basicHeader = Optional.empty();
    switch (location) {
      case QUERY -> {
        this.tokenParam = Optional.of(encoded);
        this.tokenHeader = Optional.empty();
      }
      case HEADER -> {
        this.tokenParam = Optional.empty();
        this.tokenHeader = Optional.of(raw);
      }
    }
  }

  /** Drops the Basic header and token, pending or applied; cookies are kept. */
  public void clearAuth() {
    this.pendingAuth = Optional.empty();
    this.basicHeader = Optional.empty();
    this.tokenParam = Optional.empty();
    this.tokenHeader = Optional.empty();
  }

  /**
   * Posts {@code j_username}/{@code j_password} form fields to {@code path} ({@code
   * /j_spring_security_check} or {@code /rest_v2/login}); a successful reply sets the session
   * cookie, which the cookie manager then sends on every request.
   */
  public Response formLogin(String path, String username, Secret password) {
    char[] pw = password.chars();
    byte[] body;
    try {
      // issue #51: encoded straight from the char[], so no String copy of the password exists
      body = FormBodies.login(username, pw);
    } finally {
      Arrays.fill(pw, '\0');
    }
    redactor.register(password);
    try {
      Response r =
          send(
              "POST",
              path,
              JSON,
              Optional.of("application/x-www-form-urlencoded"),
              HttpRequest.BodyPublishers.ofByteArray(body),
              requestTimeout,
              HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      sessionCookie().ifPresent(redactor::register);
      return r;
    } finally {
      Arrays.fill(body, (byte) 0);
    }
  }

  /** The current {@code JSESSIONID} value held by the cookie manager, if any. */
  public Optional<String> sessionCookie() {
    for (HttpCookie c : cookies.getCookieStore().getCookies()) {
      if (SESSION_COOKIE.equalsIgnoreCase(c.getName())) {
        return Optional.of(c.getValue());
      }
    }
    return Optional.empty();
  }

  public void clearSession() {
    cookies.getCookieStore().removeAll();
  }

  /**
   * Called once when a request answers 401 (review finding 2.3): the hook re-establishes the
   * session (a form login, say) and answers true, after which the request is replayed once. Not
   * consulted for requests the hook itself makes.
   */
  public void onUnauthorized(BooleanSupplier hook) {
    this.reauthenticate = Optional.of(Objects.requireNonNull(hook, "hook"));
  }

  public void correlationId(String runId) {
    this.correlationId = Objects.requireNonNull(runId, "runId");
  }

  public String correlationId() {
    return correlationId;
  }

  public URI baseUrl() {
    return baseUrl;
  }

  public Config.NetworkMode networkMode() {
    return mode;
  }

  // ---------------------------------------------------------------- requests

  public Response get(String path) {
    return get(path, JSON);
  }

  public Response get(String path, String accept) {
    return send(
        "GET",
        path,
        accept,
        Optional.empty(),
        HttpRequest.BodyPublishers.noBody(),
        requestTimeout,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /**
   * Streams a 2xx body into {@code target} (replacing it); a non-2xx body is returned as text and
   * nothing is written.
   */
  public Response getToFile(String path, String accept, Path target) {
    return getToFile(path, accept, target, () -> false);
  }

  /**
   * As above, watched (review finding 2.4): the transfer is abandoned with {@link
   * JrsUnreachableException} when no byte arrives within the idle timeout, and with {@link
   * CancellationToken.CancelledException} as soon as {@code cancelled} answers true.
   */
  public Response getToFile(String path, String accept, Path target, BooleanSupplier cancelled) {
    Objects.requireNonNull(target, "target");
    Progress progress = new Progress();
    return sendWatched(
        "GET",
        path,
        accept,
        Optional.empty(),
        HttpRequest.BodyPublishers.noBody(),
        fileOrText(target, progress),
        progress,
        cancelled);
  }

  /** Serialises {@code body} with the shared Jackson mapper. */
  public Response postJson(String path, Object body) {
    return send(
        "POST",
        path,
        JSON,
        Optional.of(JSON),
        HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8),
        requestTimeout,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /** Streams {@code file} as the request body. */
  public Response postBytesFromFile(String path, Path file, String contentType) {
    return postBytesFromFile(path, file, contentType, () -> false);
  }

  /**
   * As above, watched for stalls and cancellation like {@link #getToFile(String, String, Path,
   * BooleanSupplier)}.
   */
  public Response postBytesFromFile(
      String path, Path file, String contentType, BooleanSupplier cancelled) {
    Objects.requireNonNull(file, "file");
    Progress progress = new Progress();
    HttpRequest.BodyPublisher publisher;
    try {
      publisher = progress.watch(HttpRequest.BodyPublishers.ofFile(file));
    } catch (IOException e) {
      throw new RestException(
          0, "POST", withoutQuery(path), "cannot read upload " + file + ": " + e.getMessage(), e);
    }
    return sendWatched(
        "POST",
        path,
        JSON,
        Optional.of(contentType),
        publisher,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        progress,
        cancelled);
  }

  /** Posts a pre-serialised body with an explicit media type (JRS resource descriptors). */
  public Response post(String path, String contentType, String body) {
    return send(
        "POST",
        path,
        JSON,
        Optional.of(contentType),
        HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8),
        requestTimeout,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  public Response put(String path, String contentType, String body) {
    return send(
        "PUT",
        path,
        JSON,
        Optional.of(contentType),
        HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8),
        requestTimeout,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /** Streams {@code file} as the body of a PUT (used for large resource descriptors). */
  public Response putFromFile(String path, String contentType, Path file) {
    HttpRequest.BodyPublisher publisher;
    try {
      publisher = HttpRequest.BodyPublishers.ofFile(file);
    } catch (IOException e) {
      throw new RestException(
          0, "PUT", withoutQuery(path), "cannot read upload " + file + ": " + e.getMessage(), e);
    }
    return send(
        "PUT",
        path,
        JSON,
        Optional.of(contentType),
        publisher,
        downloadTimeout,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  public Response delete(String path) {
    return send(
        "DELETE",
        path,
        JSON,
        Optional.empty(),
        HttpRequest.BodyPublishers.noBody(),
        requestTimeout,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /**
   * Raises {@link RestException} for a non-2xx response; the message carries a redacted, capped
   * body excerpt.
   */
  public Response require2xx(Response r, String method, String path) {
    if (r.ok()) {
      return r;
    }
    throw new RestException(
        r.status(),
        method,
        withoutQuery(path),
        "HTTP " + r.status() + " from " + method + " " + withoutQuery(path) + excerpt(r.body()),
        r.header("Retry-After").flatMap(RestClient::retryAfter));
  }

  // ---------------------------------------------------------------- helpers

  /** Percent-encodes each segment of a repository path such as {@code /public/My Report}. */
  public static String encodePath(String path) {
    StringBuilder sb = new StringBuilder();
    for (String seg : path.split("/", -1)) {
      if (sb.length() > 0 || path.startsWith("/")) {
        sb.append('/');
      }
      sb.append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
    }
    String out = sb.toString();
    return path.startsWith("/") && out.startsWith("//") ? out.substring(1) : out;
  }

  public static String encodeQuery(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  static URI withoutQuery(URI uri) {
    try {
      return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null);
    } catch (URISyntaxException e) {
      return URI.create(uri.getScheme() + "://" + uri.getAuthority());
    }
  }

  static String withoutQuery(String path) {
    int q = path.indexOf('?');
    return q < 0 ? path : path.substring(0, q);
  }

  private Response send(
      String method,
      String path,
      String accept,
      Optional<String> contentType,
      HttpRequest.BodyPublisher body,
      Duration timeout,
      HttpResponse.BodyHandler<String> handler) {
    URI uri = resolve(path);
    enforceAllowlist(uri);
    HttpRequest request = request(uri, method, accept, contentType, body, timeout);
    boolean hadSession = sessionCookie().isPresent();
    Response first = exchange(request, handler, method, uri);
    if (first.status() == 401 && hadSession && reauthenticated()) {
      return exchange(request, handler, method, uri);
    }
    return first;
  }

  private Response exchange(
      HttpRequest request, HttpResponse.BodyHandler<String> handler, String method, URI uri) {
    HttpResponse<String> response;
    try {
      response = http.send(request, handler);
    } catch (IOException e) {
      throw unreachable(method, uri, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new JrsUnreachableException(
          withoutQuery(uri), "interrupted while waiting for " + method + " " + safe(uri), "", e);
    }
    return new Response(response.statusCode(), response.body(), response.headers().map());
  }

  /**
   * A transfer that must keep moving: sent asynchronously and watched every {@link #WATCH_TICK} for
   * the cancel signal and for silence longer than the idle timeout, since the request timeout
   * covers only the wait for headers (review finding 2.4).
   */
  private Response sendWatched(
      String method,
      String path,
      String accept,
      Optional<String> contentType,
      HttpRequest.BodyPublisher body,
      HttpResponse.BodyHandler<String> handler,
      Progress progress,
      BooleanSupplier cancelled) {
    URI uri = resolve(path);
    enforceAllowlist(uri);
    HttpRequest request = request(uri, method, accept, contentType, body, downloadTimeout);
    boolean hadSession = sessionCookie().isPresent();
    Response first = exchangeWatched(request, handler, method, uri, progress, cancelled);
    if (first.status() == 401 && hadSession && reauthenticated()) {
      progress.touch();
      return exchangeWatched(request, handler, method, uri, progress, cancelled);
    }
    return first;
  }

  private Response exchangeWatched(
      HttpRequest request,
      HttpResponse.BodyHandler<String> handler,
      String method,
      URI uri,
      Progress progress,
      BooleanSupplier cancelled) {
    progress.touch();
    CompletableFuture<HttpResponse<String>> future = http.sendAsync(request, handler);
    while (true) {
      try {
        HttpResponse<String> response = future.get(WATCH_TICK.toMillis(), TimeUnit.MILLISECONDS);
        return new Response(response.statusCode(), response.body(), response.headers().map());
      } catch (TimeoutException tick) {
        if (cancelled.getAsBoolean()) {
          future.cancel(true);
          throw new CancellationToken.CancelledException(
              "cancelled during " + method + " " + safe(uri));
        }
        Duration silence = progress.silence();
        if (silence.compareTo(idleTimeout) > 0) {
          future.cancel(true);
          throw new JrsUnreachableException(
              withoutQuery(uri),
              method
                  + " "
                  + safe(uri)
                  + " stalled: no bytes for "
                  + silence.toSeconds()
                  + "s (idle timeout "
                  + idleTimeout.toSeconds()
                  + "s)",
              REMEDIATION,
              null);
        }
      } catch (ExecutionException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        if (cause instanceof IOException io) {
          throw unreachable(method, uri, io);
        }
        if (cause instanceof RuntimeException re) {
          throw re;
        }
        throw new JrsUnreachableException(
            withoutQuery(uri), "cannot complete " + method + " " + safe(uri), REMEDIATION, cause);
      } catch (InterruptedException e) {
        future.cancel(true);
        Thread.currentThread().interrupt();
        throw new JrsUnreachableException(
            withoutQuery(uri), "interrupted while waiting for " + method + " " + safe(uri), "", e);
      }
    }
  }

  private HttpRequest request(
      URI uri,
      String method,
      String accept,
      Optional<String> contentType,
      HttpRequest.BodyPublisher body,
      Duration timeout) {
    return request(uri, method, accept, contentType, body, timeout, false);
  }

  private HttpRequest request(
      URI uri,
      String method,
      String accept,
      Optional<String> contentType,
      HttpRequest.BodyPublisher body,
      Duration timeout,
      boolean anonymous) {
    HttpRequest.Builder rb =
        HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Accept", accept)
            .header(REMOTE_DOMAIN_HEADER, "1")
            .header(CORRELATION_HEADER, correlationId.isEmpty() ? "none" : correlationId)
            .method(method, body);
    contentType.ifPresent(ct -> rb.header("Content-Type", ct));
    if (!anonymous) {
      basicHeader.ifPresent(h -> rb.header("Authorization", h));
      tokenHeader.ifPresent(t -> rb.header(PREAUTH_HEADER, t));
    }
    return rb.build();
  }

  /**
   * Runs the re-authentication hook once for a 401 that arrived on a request that carried a session
   * cookie (a session that ended); a 401 without a session is a probe or a login attempt and is not
   * re-tried. False when there is no hook or it failed.
   */
  private boolean reauthenticated() {
    Optional<BooleanSupplier> hook = reauthenticate;
    if (hook.isEmpty() || reauthenticating.get()) {
      return false;
    }
    reauthenticating.set(true);
    try {
      return hook.get().getAsBoolean();
    } catch (RuntimeException e) {
      return false;
    } finally {
      reauthenticating.set(false);
    }
  }

  /** Seconds or an HTTP date, per RFC 9110; never negative. */
  static Optional<Duration> retryAfter(String header) {
    String h = header.strip();
    if (h.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Duration.ofSeconds(Math.max(0, Long.parseLong(h))));
    } catch (NumberFormatException notSeconds) {
      try {
        Instant at = ZonedDateTime.parse(h, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        Duration until = Duration.between(Instant.now(), at);
        return Optional.of(until.isNegative() ? Duration.ZERO : until);
      } catch (DateTimeParseException notADate) {
        return Optional.empty();
      }
    }
  }

  /**
   * Which requests bypass the proxy: loopback always, plus the {@code noProxy} entries (a bare host
   * name exactly, a {@code .suffix} for every host under it).
   */
  static ProxySelector proxySelector(String host, int port, List<String> noProxy) {
    java.net.Proxy viaProxy =
        new java.net.Proxy(
            java.net.Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port));
    List<String> bypass = noProxy.stream().map(s -> s.strip().toLowerCase(Locale.ROOT)).toList();
    return new ProxySelector() {
      @Override
      public List<java.net.Proxy> select(URI uri) {
        String h = hostOf(uri).toLowerCase(Locale.ROOT);
        if (h.isEmpty() || isLoopback(h)) {
          return List.of(java.net.Proxy.NO_PROXY);
        }
        for (String entry : bypass) {
          if (entry.isEmpty()) {
            continue;
          }
          boolean match =
              entry.startsWith(".")
                  ? h.endsWith(entry) || h.equals(entry.substring(1))
                  : h.equals(entry);
          if (match) {
            return List.of(java.net.Proxy.NO_PROXY);
          }
        }
        return List.of(viaProxy);
      }

      @Override
      public void connectFailed(URI uri, java.net.SocketAddress sa, IOException ioe) {
        // reported by the request that fails; nothing to record here
      }
    };
  }

  private static boolean isLoopback(String host) {
    String h =
        host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    if (h.equals("localhost") || h.equals("::1") || h.startsWith("127.")) {
      return true;
    }
    try {
      return InetAddress.getByName(h).isLoopbackAddress();
    } catch (UnknownHostException e) {
      return false;
    }
  }

  /** Bytes moved so far and when the last one moved, shared with the transfer's subscriber. */
  static final class Progress {
    private final AtomicLong lastNanos = new AtomicLong(System.nanoTime());

    void touch() {
      lastNanos.set(System.nanoTime());
    }

    Duration silence() {
      return Duration.ofNanos(System.nanoTime() - lastNanos.get());
    }

    HttpRequest.BodyPublisher watch(HttpRequest.BodyPublisher delegate) {
      long length = delegate.contentLength();
      Flow.Publisher<ByteBuffer> watched =
          subscriber ->
              delegate.subscribe(
                  new Flow.Subscriber<ByteBuffer>() {
                    @Override
                    public void onSubscribe(Flow.Subscription subscription) {
                      subscriber.onSubscribe(subscription);
                    }

                    @Override
                    public void onNext(ByteBuffer item) {
                      touch();
                      subscriber.onNext(item);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                      subscriber.onError(throwable);
                    }

                    @Override
                    public void onComplete() {
                      subscriber.onComplete();
                    }
                  });
      return length > 0
          ? HttpRequest.BodyPublishers.fromPublisher(watched, length)
          : HttpRequest.BodyPublishers.fromPublisher(watched);
    }

    <T> HttpResponse.BodySubscriber<T> watch(HttpResponse.BodySubscriber<T> delegate) {
      return new HttpResponse.BodySubscriber<T>() {
        @Override
        public CompletionStage<T> getBody() {
          return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
          touch();
          delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
          touch();
          delegate.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
          delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
          delegate.onComplete();
        }
      };
    }
  }

  /**
   * Trusts what any of its managers trusts (review finding 2.9): a configured trust store adds
   * corporate CAs to the JDK's set instead of replacing it, so a server behind a public certificate
   * still verifies. The first manager's refusal is reported when all refuse.
   */
  static final class CompositeTrustManager implements X509TrustManager {
    private final List<X509TrustManager> managers;

    CompositeTrustManager(List<X509TrustManager> managers) {
      if (managers.isEmpty()) {
        throw new IllegalArgumentException("at least one trust manager");
      }
      this.managers = List.copyOf(managers);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
      CertificateException first = null;
      for (X509TrustManager m : managers) {
        try {
          m.checkClientTrusted(chain, authType);
          return;
        } catch (CertificateException e) {
          first = first == null ? e : first;
        }
      }
      throw first;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
        throws CertificateException {
      CertificateException first = null;
      for (X509TrustManager m : managers) {
        try {
          m.checkServerTrusted(chain, authType);
          return;
        } catch (CertificateException e) {
          first = first == null ? e : first;
        }
      }
      throw first;
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      List<X509Certificate> all = new ArrayList<>();
      for (X509TrustManager m : managers) {
        all.addAll(Arrays.asList(m.getAcceptedIssuers()));
      }
      return all.toArray(new X509Certificate[0]);
    }
  }

  private URI resolve(String path) {
    return resolve(path, false);
  }

  private URI resolve(String path, boolean anonymous) {
    Objects.requireNonNull(path, "path");
    if (!anonymous) {
      applyPendingAuth();
    }
    String target = isAbsolute(path) ? path : base + (path.startsWith("/") ? path : "/" + path);
    Optional<String> token = anonymous ? Optional.empty() : tokenParam;
    if (token.isPresent()) {
      target = target + (target.contains("?") ? "&" : "?") + "pp=" + token.get();
    }
    try {
      return new URI(target);
    } catch (URISyntaxException e) {
      throw new RestException(
          0, "?", withoutQuery(path), "malformed request path " + withoutQuery(path), e);
    }
  }

  private void enforceAllowlist(URI uri) {
    if (mode == Config.NetworkMode.ISOLATED && !baseHost.equalsIgnoreCase(hostOf(uri))) {
      IsolatedModeViolation violation = new IsolatedModeViolation(uri, baseHost);
      auditHook.accept("FAIL isolated-mode refused request to " + safe(uri));
      throw violation;
    }
  }

  private JrsUnreachableException unreachable(String method, URI uri, IOException e) {
    String detail = e.getClass().getSimpleName();
    if (e.getMessage() != null && !e.getMessage().isBlank()) {
      detail += ": " + redactor.redact(e.getMessage());
    }
    return new JrsUnreachableException(
        withoutQuery(uri),
        "cannot reach " + method + " " + safe(uri) + " (" + detail + ")",
        REMEDIATION,
        e);
  }

  private String excerpt(String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    String one = body.strip().replaceAll("\\s+", " ");
    if (one.length() > RestException.EXCERPT_LIMIT) {
      one = one.substring(0, RestException.EXCERPT_LIMIT) + "...";
    }
    return ": " + redactor.redact(one);
  }

  private static String safe(URI uri) {
    return withoutQuery(uri).toString();
  }

  private static boolean isAbsolute(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    return lower.startsWith("http://") || lower.startsWith("https://");
  }

  private static String hostOf(URI uri) {
    return uri.getHost() == null ? "" : uri.getHost();
  }

  private static String stripTrailingSlash(String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  private static HttpResponse.BodyHandler<String> fileOrText(Path target, Progress progress) {
    return info ->
        info.statusCode() >= 200 && info.statusCode() < 300
            ? HttpResponse.BodySubscribers.mapping(
                progress.watch(HttpResponse.BodySubscribers.ofFile(target)), p -> "")
            : HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
  }

  private static Authenticator proxyAuthenticator(String user, Optional<Secret> password) {
    return new Authenticator() {
      @Override
      protected PasswordAuthentication getPasswordAuthentication() {
        if (getRequestorType() != RequestorType.PROXY) {
          return null;
        }
        char[] chars = password.map(Secret::chars).orElseGet(() -> new char[0]);
        try {
          return new PasswordAuthentication(user, chars);
        } finally {
          Arrays.fill(chars, '\0');
        }
      }
    };
  }

  /** The X.509 managers of {@code ks}; {@code null} means the JDK's default CA set. */
  private static List<X509TrustManager> x509(KeyStore ks) throws GeneralSecurityException {
    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(ks);
    List<X509TrustManager> out = new ArrayList<>();
    for (TrustManager tm : tmf.getTrustManagers()) {
      if (tm instanceof X509TrustManager x) {
        out.add(x);
      }
    }
    return out;
  }

  private static SSLContext sslContext(Path trustStore, Optional<Secret> password) {
    char[] pw = password.map(Secret::chars).orElse(null);
    try (InputStream in = Files.newInputStream(trustStore)) {
      KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
      ks.load(in, pw);
      List<X509TrustManager> managers = new ArrayList<>();
      managers.addAll(x509(ks));
      managers.addAll(x509(null));
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(null, new TrustManager[] {new CompositeTrustManager(managers)}, null);
      return ctx;
    } catch (IOException | GeneralSecurityException e) {
      throw new IllegalStateException(
          "cannot load network.trustStore " + trustStore + ": " + e.getClass().getSimpleName(), e);
    } finally {
      if (pw != null) {
        Arrays.fill(pw, '\0');
      }
    }
  }
}
