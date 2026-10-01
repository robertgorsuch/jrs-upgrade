package com.jaspersoft.jrsupgrade.jrs.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.config.ConfigException;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.core.secrets.EncryptedSecretStore;
import com.jaspersoft.jrsupgrade.core.secrets.PassphraseSource;
import com.jaspersoft.jrsupgrade.core.secrets.Secret;
import com.jaspersoft.jrsupgrade.core.secrets.SecretResolver;
import com.jaspersoft.jrsupgrade.jrs.FakePlatform;
import com.jaspersoft.jrsupgrade.jrs.TestConfigs;
import com.jaspersoft.jrsupgrade.jrs.api.HealthReport;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapterFactory;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class RestJrsAdapterFactoryTest {

  private static final String PASSWORD = "factory-Secret-9";

  @RegisterExtension
  WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @TempDir Path tmp;

  private final Platform platform = new FakePlatform(Platform.OsFamily.LINUX);

  private SecretResolver resolver() {
    EncryptedSecretStore store =
        new EncryptedSecretStore(
            tmp.resolve("secrets.enc"),
            new PassphraseSource.Fixed(Secret.fromString("pp")),
            "host");
    return new SecretResolver(Map.of(TestConfigs.PASSWORD_ENV, PASSWORD), platform.files(), store);
  }

  private URI base() {
    return URI.create("http://localhost:" + wm.getPort() + "/jasperserver-pro");
  }

  private static final String SERVER_INFO =
      "{\"version\":\"10.0.0\",\"edition\":\"PRO\",\"editionName\":\"Professional\","
          + "\"build\":\"20250601_0800\",\"features\":\"\",\"dateFormatPattern\":\"yyyy-MM-dd\","
          + "\"datetimeFormatPattern\":\"yyyy-MM-dd'T'HH:mm:ss\"}";

  private static final String SERVER_INFO_PATH = "/jasperserver-pro/rest_v2/serverInfo";

  /**
   * Field test 2, D1: doctor needed the admin password before it could even ask whether the server
   * was up, because the factory resolved it at connect time. The password is now resolved by the
   * first request that carries it, and serverInfo is asked for without credentials first.
   */
  @Test
  void should_not_resolve_the_password_until_a_request_needs_it() {
    wm.stubFor(
        get(urlPathEqualTo(SERVER_INFO_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(SERVER_INFO)));
    SecretResolver noPassword =
        new SecretResolver(
            Map.of(),
            platform.files(),
            new EncryptedSecretStore(
                tmp.resolve("secrets.enc"),
                new PassphraseSource.Fixed(Secret.fromString("pp")),
                "host"));

    JrsAdapter adapter =
        new RestJrsAdapterFactory()
            .connect(
                TestConfigs.server(base(), Config.AuthMode.BASIC),
                noPassword,
                new Redactor(),
                platform);

    assertThat(adapter.identity().version()).isEqualTo("10.0.0");
    wm.verify(getRequestedFor(urlPathEqualTo(SERVER_INFO_PATH)).withoutHeader("Authorization"));
    assertThatThrownBy(() -> adapter.listFolder("/"))
        .isInstanceOf(com.jaspersoft.jrsupgrade.core.secrets.SecretException.class)
        .hasMessageContaining(TestConfigs.PASSWORD_ENV);
  }

  @Test
  void should_retry_server_info_with_the_credentials_when_the_server_wants_a_login() {
    wm.stubFor(
        get(urlPathEqualTo(SERVER_INFO_PATH))
            .atPriority(5)
            .willReturn(aResponse().withStatus(401).withBody("<html>Unauthorized</html>")));
    wm.stubFor(
        get(urlPathEqualTo(SERVER_INFO_PATH))
            .withHeader("Authorization", containing("Basic "))
            .atPriority(1)
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(SERVER_INFO)));

    JrsAdapter adapter =
        new RestJrsAdapterFactory()
            .connect(
                TestConfigs.server(base(), Config.AuthMode.BASIC),
                resolver(),
                new Redactor(),
                platform);

    assertThat(adapter.identity().version()).isEqualTo("10.0.0");
    wm.verify(2, getRequestedFor(urlPathEqualTo(SERVER_INFO_PATH)));
    wm.verify(
        1,
        getRequestedFor(urlPathEqualTo(SERVER_INFO_PATH))
            .withHeader("Authorization", containing("Basic ")));
  }

  @Test
  void should_be_discoverable_when_loaded_through_service_loader() {
    assertThat(
            java.util.ServiceLoader.load(JrsAdapterFactory.class).stream()
                .anyMatch(p -> p.type() == RestJrsAdapterFactory.class))
        .as("the REST factory is registered as a service provider")
        .isTrue();
  }

  @Test
  void should_not_touch_the_server_when_connecting_and_register_the_secret_on_first_use() {
    Redactor redactor = new Redactor();
    wm.stubFor(
        get(urlPathEqualTo("/jasperserver-pro/rest_v2/resources"))
            .willReturn(aResponse().withStatus(204)));

    JrsAdapter adapter =
        new RestJrsAdapterFactory()
            .connect(
                TestConfigs.server(base(), Config.AuthMode.BASIC), resolver(), redactor, platform);

    assertThat(adapter).isInstanceOf(RestJrsAdapter.class);
    wm.verify(0, anyRequestedFor(anyUrl()));
    // field test 2, D1: the secret is resolved by the first request that carries it, not before
    assertThat(redactor.redact("saw " + PASSWORD)).contains(PASSWORD);
    adapter.listFolder("/");
    assertThat(redactor.redact("saw " + PASSWORD)).doesNotContain(PASSWORD).contains(Redactor.MASK);
  }

  @Test
  void should_send_basic_auth_on_the_first_authenticated_request_when_mode_is_basic() {
    wm.stubFor(
        get(urlPathEqualTo(SERVER_INFO_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"version\":\"8.2.0\",\"edition\":\"PRO\"}")));
    wm.stubFor(
        get(urlPathEqualTo("/jasperserver-pro/rest_v2/resources"))
            .willReturn(aResponse().withStatus(204)));
    JrsAdapter adapter =
        new RestJrsAdapterFactory()
            .connect(
                TestConfigs.server(base(), Config.AuthMode.BASIC),
                resolver(),
                new Redactor(),
                platform);

    assertThat(adapter.identity().version()).isEqualTo("8.2.0");
    assertThat(adapter.listFolder("/")).isEmpty();
    wm.verify(getRequestedFor(urlPathEqualTo(SERVER_INFO_PATH)).withoutHeader("Authorization"));
    wm.verify(
        getRequestedFor(urlPathEqualTo("/jasperserver-pro/rest_v2/resources"))
            .withHeader("Authorization", containing("Basic ")));
  }

  @Test
  void should_throw_config_exception_when_base_url_missing() {
    assertThatThrownBy(
            () ->
                new RestJrsAdapterFactory()
                    .connect(Config.defaults(), resolver(), new Redactor(), platform))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining("server.baseUrl");
  }

  @Test
  void should_report_unreachable_as_health_item_when_server_is_down() throws IOException {
    int closed;
    try (ServerSocket s = new ServerSocket(0)) {
      closed = s.getLocalPort();
    }
    JrsAdapter adapter =
        new RestJrsAdapterFactory()
            .connect(
                TestConfigs.server(
                    URI.create("http://localhost:" + closed + "/jasperserver"),
                    Config.AuthMode.BASIC),
                resolver(),
                new Redactor(),
                platform);

    HealthReport report = adapter.health();

    assertThat(report.reachable()).isFalse();
    assertThat(report.items()).hasSize(1);
    assertThat(report.items().get(0).status()).isEqualTo(HealthReport.Status.FAIL);
    assertThat(report.items().get(0).remediation()).contains("server.baseUrl");
  }

  @Test
  void should_connect_without_credentials_when_password_ref_absent() {
    JrsAdapter adapter =
        new RestJrsAdapterFactory()
            .connect(TestConfigs.anonymous(base()), resolver(), new Redactor(), platform);
    wm.stubFor(
        get(urlPathEqualTo("/jasperserver-pro/rest_v2/serverInfo"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"version\":\"7.1.0\",\"edition\":\"CE\"}")));

    HealthReport report = adapter.health();

    assertThat(report.reachable()).isTrue();
    assertThat(report.items())
        .extracting(HealthReport.Item::status)
        .containsExactly(HealthReport.Status.PASS, HealthReport.Status.WARN);
  }
}
