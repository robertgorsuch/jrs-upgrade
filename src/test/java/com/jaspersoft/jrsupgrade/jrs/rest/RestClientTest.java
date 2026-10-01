package com.jaspersoft.jrsupgrade.jrs.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.core.secrets.Secret;
import com.jaspersoft.jrsupgrade.jrs.api.JrsUnreachableException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class RestClientTest {

  @RegisterExtension
  static WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  private static final String PASSWORD = "s3cret-Pa55word!";

  private URI base() {
    return URI.create("http://localhost:" + wm.getPort() + "/jasperserver-pro");
  }

  private RestClient.Builder builder(Redactor redactor) {
    return RestClient.builder(base()).redactor(redactor).correlationId("run-42");
  }

  @Test
  void should_refuse_other_host_and_audit_when_mode_is_isolated() {
    List<String> audit = new ArrayList<>();
    RestClient client =
        builder(new Redactor())
            .networkMode(Config.NetworkMode.ISOLATED)
            .auditHook(audit::add)
            .build();
    String foreign = "http://127.0.0.1:" + wm.getPort() + "/rest_v2/serverInfo?pp=tok";

    assertThatThrownBy(() -> client.get(foreign))
        .isInstanceOf(IsolatedModeViolation.class)
        .hasMessageContaining("127.0.0.1")
        .hasMessageNotContaining("pp=tok");
    assertThat(audit).hasSize(1);
    assertThat(audit.get(0)).contains("FAIL").contains("127.0.0.1").doesNotContain("tok");
    wm.verify(0, getRequestedFor(urlPathEqualTo("/rest_v2/serverInfo")));
  }

  @Test
  void should_allow_other_host_when_mode_is_public() {
    wm.stubFor(get(urlPathEqualTo("/rest_v2/serverInfo")).willReturn(aResponse().withStatus(200)));
    RestClient client = builder(new Redactor()).networkMode(Config.NetworkMode.PUBLIC).build();

    RestClient.Response r = client.get("http://127.0.0.1:" + wm.getPort() + "/rest_v2/serverInfo");

    assertThat(r.status()).isEqualTo(200);
  }

  @Test
  void should_send_basic_header_and_correlation_id_when_basic_auth_configured() {
    wm.stubFor(
        get(urlPathEqualTo("/jasperserver-pro/rest_v2/serverInfo"))
            .willReturn(aResponse().withStatus(200)));
    RestClient client = builder(new Redactor()).build();
    try (Secret pw = Secret.fromString(PASSWORD)) {
      client.useBasic("jasperadmin", pw);
    }

    client.get("/rest_v2/serverInfo");

    String expected =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    ("jasperadmin:" + PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    wm.verify(
        getRequestedFor(urlPathEqualTo("/jasperserver-pro/rest_v2/serverInfo"))
            .withHeader("Authorization", equalTo(expected))
            .withHeader(RestClient.REMOTE_DOMAIN_HEADER, equalTo("1"))
            .withHeader(RestClient.CORRELATION_HEADER, equalTo("run-42")));
  }

  @Test
  void should_not_leak_password_or_header_when_server_answers_401() {
    wm.stubFor(
        get(urlPathEqualTo("/jasperserver-pro/rest_v2/resources"))
            .willReturn(
                aResponse()
                    .withStatus(401)
                    .withBody("Bad credentials for jasperadmin:" + PASSWORD + " try again")));
    Redactor redactor = new Redactor();
    RestClient client = builder(redactor).build();
    try (Secret pw = Secret.fromString(PASSWORD)) {
      client.useBasic("jasperadmin", pw);
    }
    String header =
        "Basic "
            + Base64.getEncoder()
                .encodeToString(
                    ("jasperadmin:" + PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8));

    RestClient.Response r = client.get("/rest_v2/resources?folderUri=%2F");
    assertThatThrownBy(() -> client.require2xx(r, "GET", "/rest_v2/resources?folderUri=%2F"))
        .isInstanceOf(RestException.class)
        .hasMessageContaining("HTTP 401")
        .hasMessageContaining("/rest_v2/resources")
        .hasMessageNotContaining("folderUri")
        .hasMessageNotContaining(PASSWORD)
        .hasMessageNotContaining(header)
        .hasMessageContaining(Redactor.MASK);
  }

  @Test
  void should_append_pp_parameter_when_token_auth_configured() {
    wm.stubFor(
        get(urlPathEqualTo("/jasperserver-pro/rest_v2/jobs"))
            .willReturn(aResponse().withStatus(200)));
    Redactor redactor = new Redactor();
    RestClient client = builder(redactor).build();
    try (Secret token = Secret.fromString("tok&en=1")) {
      client.useToken(token);
    }

    client.get("/rest_v2/jobs?limit=5");

    wm.verify(
        getRequestedFor(urlPathEqualTo("/jasperserver-pro/rest_v2/jobs"))
            .withQueryParam("limit", equalTo("5"))
            .withQueryParam("pp", equalTo("tok&en=1"))
            .withHeader("Authorization", absent()));
    assertThat(redactor.redact("saw tok&en=1 here")).doesNotContain("tok&en=1");
  }

  /** Issue #45: a server with tokenInRequestParam false or unset reads the pp header. */
  @Test
  void should_send_pp_header_and_no_query_parameter_when_token_location_is_header() {
    wm.stubFor(
        get(urlPathEqualTo("/jasperserver-pro/rest_v2/jobs"))
            .willReturn(aResponse().withStatus(200)));
    Redactor redactor = new Redactor();
    RestClient client = builder(redactor).build();
    try (Secret token = Secret.fromString("tok&en=1")) {
      client.useToken(token, Config.TokenLocation.HEADER);
    }

    client.get("/rest_v2/jobs?limit=5");

    wm.verify(
        getRequestedFor(urlPathEqualTo("/jasperserver-pro/rest_v2/jobs"))
            .withQueryParam("limit", equalTo("5"))
            .withQueryParam("pp", absent())
            .withHeader("pp", equalTo("tok&en=1"))
            .withHeader("Authorization", absent()));
    assertThat(redactor.redact("saw tok&en=1 here")).doesNotContain("tok&en=1");
  }

  @Test
  void should_store_session_cookie_and_resend_it_when_form_login_succeeds() {
    wm.stubFor(
        post(urlEqualTo("/jasperserver-pro/j_spring_security_check"))
            .willReturn(
                aResponse()
                    .withStatus(302)
                    .withHeader("Location", "/jasperserver-pro/")
                    .withHeader("Set-Cookie", "JSESSIONID=ABC123; Path=/jasperserver-pro")));
    wm.stubFor(
        get(urlPathEqualTo("/jasperserver-pro/rest_v2/jobs"))
            .willReturn(aResponse().withStatus(200)));
    Redactor redactor = new Redactor();
    RestClient client = builder(redactor).build();

    RestClient.Response login;
    try (Secret pw = Secret.fromString(PASSWORD)) {
      login = client.formLogin("/j_spring_security_check", "jasperadmin", pw);
    }
    client.get("/rest_v2/jobs");

    assertThat(login.status()).isEqualTo(302);
    assertThat(client.sessionCookie()).contains("ABC123");
    wm.verify(
        postRequestedFor(urlEqualTo("/jasperserver-pro/j_spring_security_check"))
            .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded"))
            .withRequestBody(
                equalTo(
                    "j_username=jasperadmin&j_password="
                        + URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8))));
    wm.verify(
        getRequestedFor(urlPathEqualTo("/jasperserver-pro/rest_v2/jobs"))
            .withHeader("Cookie", containing("JSESSIONID=ABC123")));
    assertThat(redactor.redact("cookie ABC123")).doesNotContain("ABC123");
  }

  /**
   * Review finding 2.2: 408, 429, 502, 503 and 504 are transient answers a step may retry, and a
   * {@code Retry-After} header says how long to wait; every other status stays a hard failure.
   */
  @Test
  void should_mark_gateway_and_throttling_answers_transient_and_read_retry_after() {
    wm.stubFor(
        get(urlPathEqualTo("/jasperserver-pro/rest_v2/busy"))
            .willReturn(aResponse().withStatus(503).withHeader("Retry-After", "7")));
    wm.stubFor(
        get(urlPathEqualTo("/jasperserver-pro/rest_v2/broken"))
            .willReturn(aResponse().withStatus(500)));
    RestClient client = builder(new Redactor()).build();

    RestException busy =
        (RestException)
            org.assertj.core.api.Assertions.catchThrowable(
                () -> client.require2xx(client.get("/rest_v2/busy"), "GET", "/rest_v2/busy"));
    RestException broken =
        (RestException)
            org.assertj.core.api.Assertions.catchThrowable(
                () -> client.require2xx(client.get("/rest_v2/broken"), "GET", "/rest_v2/broken"));

    assertThat(busy.transientFailure()).isTrue();
    assertThat(busy.retryAfter()).contains(java.time.Duration.ofSeconds(7));
    assertThat(broken.transientFailure()).isFalse();
    assertThat(broken.retryAfter()).isEmpty();
    for (int status : List.of(408, 429, 502, 504)) {
      assertThat(new RestException(status, "GET", "/x", "x").transientFailure())
          .as("HTTP " + status)
          .isTrue();
    }
    for (int status : List.of(400, 401, 403, 404, 500)) {
      assertThat(new RestException(status, "GET", "/x", "x").transientFailure())
          .as("HTTP " + status)
          .isFalse();
    }
  }

  /**
   * Review finding 2.4: the request timeout covers time-to-headers only, so a half-open connection
   * during a download blocked forever. Bytes must keep arriving within the idle timeout.
   */
  @Test
  void should_fail_as_unreachable_when_the_download_stalls() throws IOException {
    byte[] body = new byte[64 * 1024];
    wm.stubFor(
        get(urlPathEqualTo("/jasperserver-pro/rest_v2/big"))
            .willReturn(
                aResponse().withStatus(200).withBody(body).withChunkedDribbleDelay(4, 20_000)));
    RestClient client =
        builder(new Redactor()).idleTimeout(java.time.Duration.ofSeconds(1)).build();
    java.nio.file.Path target = java.nio.file.Files.createTempFile("stall", ".bin");

    long started = System.nanoTime();
    assertThatThrownBy(
            () -> client.getToFile("/rest_v2/big", "application/zip", target, () -> false))
        .isInstanceOf(JrsUnreachableException.class)
        .hasMessageContaining("stalled");
    assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
        .isLessThan(java.time.Duration.ofSeconds(10));
  }

  @Test
  void should_stop_the_download_when_cancelled_mid_transfer() throws IOException {
    byte[] body = new byte[64 * 1024];
    wm.stubFor(
        get(urlPathEqualTo("/jasperserver-pro/rest_v2/slow"))
            .willReturn(
                aResponse().withStatus(200).withBody(body).withChunkedDribbleDelay(4, 20_000)));
    RestClient client = builder(new Redactor()).build();
    java.nio.file.Path target = java.nio.file.Files.createTempFile("cancel", ".bin");
    long started = System.nanoTime();
    java.util.function.BooleanSupplier cancelled =
        () -> System.nanoTime() - started > java.time.Duration.ofMillis(500).toNanos();

    assertThatThrownBy(
            () -> client.getToFile("/rest_v2/slow", "application/zip", target, cancelled))
        .isInstanceOf(
            com.jaspersoft.jrsupgrade.core.engine.CancellationToken.CancelledException.class);
    assertThat(java.time.Duration.ofNanos(System.nanoTime() - started))
        .isLessThan(java.time.Duration.ofSeconds(10));
  }

  /**
   * Review finding 2.9: a proxy applies to the server host only; loopback and the listed hosts (a
   * bare name or a {@code .suffix}) go direct.
   */
  @Test
  void should_bypass_the_proxy_for_loopback_and_listed_hosts() {
    java.net.ProxySelector selector =
        RestClient.proxySelector("proxy.corp", 3128, List.of(".corp.example", "intranet"));

    for (String direct :
        List.of(
            "http://localhost:8080/x",
            "http://127.0.0.1/x",
            "http://[::1]/x",
            "https://jrs.corp.example/x",
            "http://intranet/x")) {
      assertThat(selector.select(URI.create(direct)))
          .as(direct)
          .containsExactly(java.net.Proxy.NO_PROXY);
    }
    List<java.net.Proxy> proxied = selector.select(URI.create("https://jrs.example.com/x"));
    assertThat(proxied).hasSize(1);
    assertThat(proxied.get(0).type()).isEqualTo(java.net.Proxy.Type.HTTP);
    assertThat(proxied.get(0).address().toString()).contains("proxy.corp").contains("3128");
  }

  /** A custom trust store adds to the JDK's CA set instead of replacing it. */
  @Test
  void should_trust_a_certificate_that_either_store_accepts() throws Exception {
    javax.net.ssl.X509TrustManager refusing =
        trustManager(
            () -> {
              throw new java.security.cert.CertificateException("unknown CA");
            });
    javax.net.ssl.X509TrustManager accepting = trustManager(() -> {});
    java.security.cert.X509Certificate[] chain = new java.security.cert.X509Certificate[0];

    new RestClient.CompositeTrustManager(List.of(refusing, accepting))
        .checkServerTrusted(chain, "RSA");
    new RestClient.CompositeTrustManager(List.of(accepting, refusing))
        .checkServerTrusted(chain, "RSA");
    assertThatThrownBy(
            () ->
                new RestClient.CompositeTrustManager(List.of(refusing, refusing))
                    .checkServerTrusted(chain, "RSA"))
        .isInstanceOf(java.security.cert.CertificateException.class)
        .hasMessageContaining("unknown CA");
  }

  /**
   * The JDK refuses Basic authentication on CONNECT tunnels unless told otherwise, so every
   * https:// base URL through an authenticated proxy got 407; the client enables it when a proxy
   * user is configured.
   */
  @Test
  void should_enable_basic_auth_over_connect_tunnels_when_a_proxy_user_is_configured() {
    try (Secret pw = Secret.fromString("proxy-pw")) {
      builder(new Redactor())
          .proxy(
              "proxy.corp", 3128, java.util.Optional.of("u"), java.util.Optional.of(pw), List.of())
          .build();
    }

    assertThat(System.getProperty("jdk.http.auth.tunneling.disabledSchemes")).isEqualTo("");
  }

  private static javax.net.ssl.X509TrustManager trustManager(ThrowingCheck check) {
    return new javax.net.ssl.X509TrustManager() {
      @Override
      public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType)
          throws java.security.cert.CertificateException {
        check.run();
      }

      @Override
      public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType)
          throws java.security.cert.CertificateException {
        check.run();
      }

      @Override
      public java.security.cert.X509Certificate[] getAcceptedIssuers() {
        return new java.security.cert.X509Certificate[0];
      }
    };
  }

  interface ThrowingCheck {
    void run() throws java.security.cert.CertificateException;
  }

  @Test
  void should_throw_unreachable_with_remediation_when_port_is_closed() throws IOException {
    int closedPort;
    try (ServerSocket s = new ServerSocket(0)) {
      closedPort = s.getLocalPort();
    }
    RestClient client =
        RestClient.builder(URI.create("http://localhost:" + closedPort + "/jasperserver"))
            .redactor(new Redactor())
            .build();

    assertThatThrownBy(() -> client.get("/rest_v2/serverInfo?pp=secret-token"))
        .isInstanceOf(JrsUnreachableException.class)
        .hasMessageContaining("/rest_v2/serverInfo")
        .hasMessageNotContaining("secret-token")
        .satisfies(
            e -> {
              JrsUnreachableException u = (JrsUnreachableException) e;
              assertThat(u.remediation()).contains("server.baseUrl");
              assertThat(u.url().toString()).doesNotContain("secret-token");
            });
  }

  @Test
  void should_encode_repository_paths_when_segments_contain_spaces() {
    assertThat(RestClient.encodePath("/public/My Reports/All Accounts"))
        .isEqualTo("/public/My%20Reports/All%20Accounts");
    assertThat(RestClient.encodePath("/")).isEqualTo("/");
    assertThat(RestClient.encodeQuery("/a b")).isEqualTo("%2Fa+b");
  }
}
