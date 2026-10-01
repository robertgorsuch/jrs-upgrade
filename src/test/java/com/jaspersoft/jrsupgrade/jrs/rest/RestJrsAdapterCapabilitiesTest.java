package com.jaspersoft.jrsupgrade.jrs.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.jrs.api.Capability;
import com.jaspersoft.jrsupgrade.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsupgrade.jrs.api.ServerIdentity;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RestJrsAdapterCapabilitiesTest {

  @RegisterExtension
  WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @Test
  void should_refuse_the_probe_instead_of_reporting_absent_when_the_server_answers_401() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    f.serverInfo("8.2.0-PRO");
    f.allProbesPresent();
    f.probe("/rest_v2/export/jrs-upgrade-probe/state", 401);

    assertThatThrownBy(() -> f.adapter.capabilities())
        .isInstanceOf(RestException.class)
        .satisfies(e -> assertThat(((RestException) e).authenticationFailure()).isTrue())
        .hasMessageContaining("401")
        .hasMessageNotContaining(AdapterFixture.PASSWORD);
  }

  @Test
  void should_find_every_capability_when_pro_server_answers_all_probes() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    f.serverInfo("8.2.0-PRO");
    f.allProbesPresent();

    assertThat(f.adapter.capabilities())
        .containsExactlyInAnyOrder(
            Capability.EXPORT_ASYNC,
            Capability.IMPORT_ASYNC,
            Capability.ORGS,
            Capability.REST_LOGIN,
            Capability.KEYSTORE_ENCRYPTION,
            Capability.TOKEN_AUTH,
            Capability.PREAUTH);
    assertThat(f.adapter.probeResults())
        .containsKeys(Capability.values())
        .hasEntrySatisfying(
            Capability.EXPORT_ASYNC, d -> assertThat(d).contains("HTTP 404").contains("present"))
        .hasEntrySatisfying(
            Capability.TOKEN_AUTH, d -> assertThat(d).contains("compat matrix").contains("8.2.0"));
    assertThat(f.adapter.expectedCapabilities()).isEqualTo(f.adapter.capabilities());
    // probed once, cached afterwards
    f.adapter.capabilities();
    wm.verify(1, getRequestedFor(urlPathEqualTo(f.path("/rest_v2/organizations"))));
    wm.verify(1, getRequestedFor(urlPathEqualTo(f.path("/rest_v2/serverInfo"))));
  }

  @Test
  void should_report_absent_capabilities_when_ce_7_1_server_lacks_them() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    f.serverInfo("7.1.0-CE");
    f.probe("/rest_v2/export/jrs-upgrade-probe/state", 405);
    f.probe("/rest_v2/import/jrs-upgrade-probe/state", 501);
    f.probe("/rest_v2/organizations", 404);

    // 7.1 is listed in the compat matrix, which expects REST_LOGIN (issue #46: not probed)
    assertThat(f.adapter.capabilities()).containsExactly(Capability.REST_LOGIN);
    assertThat(f.adapter.probeResults())
        .hasEntrySatisfying(
            Capability.ORGS, d -> assertThat(d).contains("HTTP 404").contains("absent"))
        .hasEntrySatisfying(
            Capability.KEYSTORE_ENCRYPTION, d -> assertThat(d).startsWith("absent"));
    assertThat(f.adapter.expectedCapabilities())
        .containsExactlyInAnyOrder(
            Capability.EXPORT_ASYNC, Capability.IMPORT_ASYNC, Capability.REST_LOGIN);
    wm.verify(0, getRequestedFor(urlPathEqualTo(f.path("/rest_v2/export"))));
  }

  /** Issue #46: a version the matrix lists needs no credential-less login attempt. */
  @Test
  void should_not_post_to_rest_login_when_the_matrix_lists_the_version() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    f.serverInfo("8.2.0-PRO");
    f.allProbesPresent();

    assertThat(f.adapter.capabilities()).contains(Capability.REST_LOGIN);
    assertThat(f.adapter.probeResults())
        .hasEntrySatisfying(
            Capability.REST_LOGIN, d -> assertThat(d).contains("compat matrix").contains("8.2.0"));
    wm.verify(0, postRequestedFor(urlPathEqualTo(f.path("/rest_v2/login"))));
  }

  @Test
  void should_probe_rest_login_when_the_matrix_does_not_list_the_version() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    f.serverInfo("11.0.0-PRO");
    f.allProbesPresent();

    f.adapter.capabilities();

    wm.verify(1, postRequestedFor(urlPathEqualTo(f.path("/rest_v2/login"))));
  }

  /**
   * Review finding 2.7: a 404 counted as "endpoint present", but a server without {@code
   * /rest_v2/export} answers 404 too. A present endpoint answers the probe id with the JSON error
   * body every recorded server returns; a bare 404 is absence.
   */
  @Test
  void should_report_export_absent_when_the_probe_404_carries_no_task_error() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    f.serverInfo("8.2.0-PRO");
    f.allProbesPresent();
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/export/jrs-upgrade-probe/state")))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "text/html")
                    .withBody("<html><body>Not Found</body></html>")));

    assertThat(f.adapter.capabilities())
        .contains(Capability.IMPORT_ASYNC)
        .doesNotContain(Capability.EXPORT_ASYNC);
    assertThat(f.adapter.probeResults().get(Capability.EXPORT_ASYNC)).contains("absent");
  }

  /**
   * A version the compat matrix does not list is unknown, not incapable: the probeable capabilities
   * are still probed, keystore encryption is assumed for 7.5 and later, and the detail says the
   * assumption was made.
   */
  @Test
  void should_treat_a_version_unknown_to_the_matrix_as_unknown_not_absent() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    f.serverInfo("11.0.0-PRO");
    f.allProbesPresent();

    assertThat(f.adapter.expectedCapabilities()).isEmpty();
    assertThat(f.adapter.capabilities())
        .contains(Capability.EXPORT_ASYNC, Capability.IMPORT_ASYNC, Capability.KEYSTORE_ENCRYPTION);
    // issue #112: an unlisted version is probed through GET /rest_v2/keys/, not assumed
    assertThat(f.adapter.probeResults().get(Capability.KEYSTORE_ENCRYPTION))
        .contains("not in the compat matrix")
        .contains("/rest_v2/keys/")
        .contains("HTTP 204");
    assertThat(f.adapter.probeResults().get(Capability.TOKEN_AUTH)).contains("assumed absent");
    assertThat(f.adapter.keystore().reason().orElse("")).doesNotContain("pre 7.5");
  }

  /** Issue #112: without the keys service an unlisted version has no keystore encryption. */
  @Test
  void should_report_keystore_absent_when_an_unlisted_server_has_no_keys_service() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    f.serverInfo("11.0.0-PRO");
    f.allProbesPresent();
    f.probe("/rest_v2/keys/", 404);

    assertThat(f.adapter.capabilities()).doesNotContain(Capability.KEYSTORE_ENCRYPTION);
    assertThat(f.adapter.probeResults().get(Capability.KEYSTORE_ENCRYPTION)).contains("HTTP 404");
  }

  /** Review §3.2 (issue #112): the licence's clustering flag, a capability of the deployment. */
  @Test
  void should_find_clustering_when_the_licence_says_cl_true() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    f.serverInfo("8.2.0-PRO");
    f.allProbesPresent();
    f.licenseFeatures(true);

    assertThat(f.adapter.capabilities()).contains(Capability.CLUSTERING);
    assertThat(f.adapter.probeResults().get(Capability.CLUSTERING))
        .contains("licenseFeatures")
        .contains("cl=true")
        .contains("mt=true")
        .contains("present");
    // the matrix never expects it: a licence flag, not a release-line capability
    assertThat(f.adapter.expectedCapabilities()).doesNotContain(Capability.CLUSTERING);
  }

  @Test
  void should_report_clustering_absent_when_there_is_no_licence_service() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    f.serverInfo("8.2.0-PRO");
    f.allProbesPresent();
    f.probe("/rest_v2/licenseFeatures", 404);

    assertThat(f.adapter.capabilities()).doesNotContain(Capability.CLUSTERING);
    assertThat(f.adapter.probeResults().get(Capability.CLUSTERING))
        .contains("HTTP 404")
        .contains("absent");
  }

  @Test
  void should_derive_identity_when_server_info_is_multi_tenant() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    f.serverInfo("9.0.0-PRO");

    ServerIdentity id = f.adapter.identity();

    assertThat(id.version()).isEqualTo("9.0.0");
    assertThat(id.tenancy()).isEqualTo(ServerIdentity.Tenancy.MULTI);
    assertThat(id.fingerprintInput()).contains("9.0.0|PRO|MULTI");
  }

  @Test
  void should_throw_unreachable_when_server_info_answers_503() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/serverInfo")))
            .willReturn(aResponse().withStatus(503).withBody("starting")));

    assertThatThrownBy(f.adapter::identity)
        .isInstanceOf(JrsUnreachableException.class)
        .hasMessageContaining("503")
        .satisfies(
            e -> assertThat(((JrsUnreachableException) e).remediation()).contains("starting"));
  }

  /**
   * The compat matrix answers for every supported server without a single probe being sent, which
   * is what {@code doctor} relies on when the server is unreachable. The identity is asserted
   * because it is what selects the matrix row; {@link ServerIdentitiesTest} owns parsing the same
   * fixtures in detail.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "7.1.0-CE,   7.1.0,  CE,  SINGLE",
    "7.1.0-PRO,  7.1.0,  PRO, MULTI",
    "7.5.0-CE,   7.5.0,  CE,  SINGLE",
    "7.9.1-PRO,  7.9.1,  PRO, SINGLE",
    "8.2.0-CE,   8.2.0,  CE,  SINGLE",
    "8.2.0-PRO,  8.2.0,  PRO, MULTI",
    "9.0.0-CE,   9.0.0,  CE,  SINGLE",
    "9.0.0-PRO,  9.0.0,  PRO, MULTI",
    "10.0.0-CE,  10.0.0, CE,  SINGLE",
    "10.0.0-PRO, 10.0.0, PRO, MULTI"
  })
  void should_expect_the_matrix_capabilities_when_the_server_reports_a_supported_version(
      String fixture, String version, String edition, ServerIdentity.Tenancy tenancy) {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    f.serverInfo(fixture);

    ServerIdentity id = f.adapter.identity();
    Set<Capability> expected = f.adapter.expectedCapabilities();

    assertThat(id.version()).isEqualTo(version);
    assertThat(id.edition().name()).isEqualTo(edition);
    assertThat(id.tenancy()).isEqualTo(tenancy);
    assertThat(expected)
        .contains(Capability.EXPORT_ASYNC, Capability.IMPORT_ASYNC, Capability.REST_LOGIN);
    assertThat(expected.contains(Capability.ORGS))
        .as("organizations are a PRO capability")
        .isEqualTo(edition.equals("PRO"));
    wm.verify(0, getRequestedFor(urlPathEqualTo(f.path("/rest_v2/organizations"))));
  }
}
