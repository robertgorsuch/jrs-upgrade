package com.jaspersoft.jrsupgrade.jrs.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.jrs.api.HealthReport;
import com.jaspersoft.jrsupgrade.jrs.api.Session;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class RestJrsAdapterRepositoryTest {

  @RegisterExtension
  WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @TempDir Path tmp;

  /** Field test 2, E3: export planning asks before starting, so a mistyped uri is refused. */
  @Test
  void should_say_whether_a_resource_exists() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/resources/public/reports")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"uri\":\"/public/reports\",\"label\":\"Reports\"}")));
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/resources/typo")))
            .willReturn(aResponse().withStatus(404).withBody("{\"message\":\"not found\"}")));
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/resources/broken")))
            .willReturn(aResponse().withStatus(500)));

    assertThat(f.adapter.resourceExists("/public/reports")).isTrue();
    assertThat(f.adapter.resourceExists("/typo")).isFalse();
    assertThatThrownBy(() -> f.adapter.resourceExists("/broken")).isInstanceOf(RestException.class);
  }

  @Test
  void should_list_child_uris_when_folder_has_resources() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/resources")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody(
                        "{\"resourceLookup\":[{\"uri\":\"/public\",\"label\":\"Public\","
                            + "\"resourceType\":\"folder\"},{\"uri\":\"/temp\",\"label\":\"Temp\","
                            + "\"resourceType\":\"folder\"}]}")));

    assertThat(f.adapter.listFolder("/")).containsExactly("/public", "/temp");
    wm.verify(
        getRequestedFor(urlPathEqualTo(f.path("/rest_v2/resources")))
            .withQueryParam("folderUri", equalTo("/"))
            .withQueryParam("recursive", equalTo("false"))
            .withQueryParam("limit", equalTo("100"))
            .withHeader("Accept", equalTo("application/json")));
  }

  /** Issue #100: the recursive listing follows Total-Count through the pages the server sends. */
  @Test
  void should_list_the_whole_subtree_across_pages_when_listing_recursively() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/resources")))
            .withQueryParam("recursive", equalTo("true"))
            .withQueryParam("offset", equalTo("0"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Total-Count", "3")
                    .withBody(
                        "{\"resourceLookup\":[{\"uri\":\"/public/a\",\"resourceType\":\"folder\"},"
                            + "{\"uri\":\"/public/a/r\",\"resourceType\":\"reportUnit\"}]}")));
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/resources")))
            .withQueryParam("recursive", equalTo("true"))
            .withQueryParam("offset", equalTo("2"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Total-Count", "3")
                    .withBody(
                        "{\"resourceLookup\":[{\"uri\":\"/public/b\",\"resourceType\":\"folder\"}]}")));

    assertThat(f.adapter.listTree("/public"))
        .containsExactly("/public/a", "/public/a/r", "/public/b");
    wm.verify(
        2,
        getRequestedFor(urlPathEqualTo(f.path("/rest_v2/resources")))
            .withQueryParam("folderUri", equalTo("/public"))
            .withQueryParam("recursive", equalTo("true")));
  }

  @Test
  void should_return_empty_tree_when_the_subtree_answers_204() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/resources"))).willReturn(aResponse().withStatus(204)));

    assertThat(f.adapter.listTree("/empty")).isEmpty();
  }

  @Test
  void should_return_empty_list_when_folder_answers_204() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/resources"))).willReturn(aResponse().withStatus(204)));

    assertThat(f.adapter.listFolder("/empty")).isEmpty();
  }

  @Test
  void should_report_scheduler_when_jobs_endpoint_answers_200() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/jobs"))).willReturn(aResponse().withStatus(200)));

    assertThat(f.adapter.schedulerReachable()).isTrue();

    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/jobs"))).willReturn(aResponse().withStatus(500)));
    assertThat(f.adapter.schedulerReachable()).isFalse();
  }

  @Test
  void should_stream_pdf_to_target_when_report_runs() throws IOException {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    byte[] pdf = "%PDF-1.4 fake body".getBytes(StandardCharsets.US_ASCII);
    wm.stubFor(
        get(urlEqualTo(f.path("/rest_v2/reports/public/Samples/Reports/All%20Accounts.pdf")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/pdf")
                    .withBody(pdf)));

    Path out =
        f.adapter.runReportToPdf("/public/Samples/Reports/All Accounts", tmp.resolve("r.pdf"));

    assertThat(Files.readAllBytes(out)).isEqualTo(pdf);
    wm.verify(
        getRequestedFor(
                urlPathEqualTo(
                    f.path("/rest_v2/reports/public/Samples/Reports/All%20Accounts.pdf")))
            .withHeader("Accept", equalTo("application/pdf")));
  }

  @Test
  void should_not_write_target_when_report_answers_404() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/reports/public/missing.pdf")))
            .willReturn(aResponse().withStatus(404).withBody("{\"message\":\"not found\"}")));
    Path out = tmp.resolve("missing.pdf");

    assertThatThrownBy(() -> f.adapter.runReportToPdf("/public/missing", out))
        .isInstanceOf(RestException.class)
        .hasMessageContaining("HTTP 404");
    assertThat(out).doesNotExist();
  }

  @Test
  void should_post_folder_descriptor_to_parent_when_creating_folder() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        post(urlPathEqualTo(f.path("/rest_v2/resources/temp")))
            .willReturn(aResponse().withStatus(201)));

    f.adapter.createFolder("/temp/jrs-upgrade", "jrs-upgrade");

    wm.verify(
        postRequestedFor(urlPathEqualTo(f.path("/rest_v2/resources/temp")))
            .withHeader("Content-Type", equalTo("application/repository.folder+json"))
            .withRequestBody(matchingJsonPath("$.label", equalTo("jrs-upgrade")))
            .withRequestBody(matchingJsonPath("$.description")));
  }

  @Test
  void should_put_report_unit_with_base64_jrxml_when_uploading() throws IOException {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    String jrxml = "<jasperReport name=\"smoke\"><title/></jasperReport>";
    Path file = tmp.resolve("smoke.jrxml");
    Files.writeString(file, jrxml, StandardCharsets.UTF_8);
    wm.stubFor(
        put(urlPathEqualTo(f.path("/rest_v2/resources/temp/jrs-upgrade/Smoke_Report")))
            .willReturn(aResponse().withStatus(201)));

    f.adapter.uploadJrxmlReport("/temp/jrs-upgrade", "Smoke Report", file);

    String b64 = Base64.getEncoder().encodeToString(jrxml.getBytes(StandardCharsets.UTF_8));
    wm.verify(
        putRequestedFor(urlPathEqualTo(f.path("/rest_v2/resources/temp/jrs-upgrade/Smoke_Report")))
            .withHeader("Content-Type", equalTo("application/repository.reportUnit+json"))
            .withRequestBody(matchingJsonPath("$.label", equalTo("Smoke Report")))
            .withRequestBody(matchingJsonPath("$.jrxml.jrxmlFile.type", equalTo("jrxml")))
            .withRequestBody(matchingJsonPath("$.jrxml.jrxmlFile.content", equalTo(b64))));
  }

  @Test
  void should_delete_resource_when_asked() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        delete(urlPathEqualTo(f.path("/rest_v2/resources/temp/jrs-upgrade")))
            .willReturn(aResponse().withStatus(204)));

    f.adapter.deleteResource("/temp/jrs-upgrade");

    wm.verify(deleteRequestedFor(urlPathEqualTo(f.path("/rest_v2/resources/temp/jrs-upgrade"))));
  }

  @Test
  void should_pass_every_health_item_when_server_is_healthy() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    f.serverInfo("8.2.0-PRO");
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/resources")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withBody("{\"resourceLookup\":[{\"uri\":\"/public\"}]}")));
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/jobs"))).willReturn(aResponse().withStatus(200)));

    HealthReport report = f.adapter.health();

    assertThat(report.reachable()).isTrue();
    assertThat(report.ok()).isTrue();
    assertThat(report.items())
        .extracting(HealthReport.Item::name)
        .containsExactly("serverInfo", "login", "repository", "scheduler");
    assertThat(report.items()).allMatch(i -> i.status() == HealthReport.Status.PASS);
    assertThat(report.latency().isNegative()).isFalse();
  }

  @Test
  void should_fail_login_item_without_leaking_password_when_credentials_rejected() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    f.serverInfo("8.2.0-PRO");
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/resources")))
            .willReturn(
                aResponse()
                    .withStatus(401)
                    .withBody("bad credentials " + AdapterFixture.PASSWORD)));

    HealthReport report = f.adapter.health();

    assertThat(report.reachable()).isTrue();
    assertThat(report.ok()).isFalse();
    HealthReport.Item login = report.items().get(1);
    assertThat(login.name()).isEqualTo("login");
    assertThat(login.status()).isEqualTo(HealthReport.Status.FAIL);
    assertThat(login.detail()).contains("401").doesNotContain(AdapterFixture.PASSWORD);
    assertThat(login.remediation()).contains("passwordRef");
  }

  @Test
  void should_report_unreachable_when_server_is_down() throws IOException {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/serverInfo"))).willReturn(aResponse().withStatus(502)));

    HealthReport report = f.adapter.health();

    assertThat(report.reachable()).isFalse();
    assertThat(report.ok()).isFalse();
    assertThat(report.items()).hasSize(1);
    assertThat(report.items().get(0).status()).isEqualTo(HealthReport.Status.FAIL);
  }

  @Test
  void should_login_through_rest_endpoint_and_reuse_cookie_when_form_mode_has_rest_login() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.FORM);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/login"))).willReturn(aResponse().withStatus(405)));
    wm.stubFor(
        post(urlPathEqualTo(f.path("/rest_v2/login")))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Set-Cookie", "JSESSIONID=F0RM; Path=/jasperserver-pro")));
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/jobs"))).willReturn(aResponse().withStatus(200)));

    boolean scheduler = f.adapter.schedulerReachable();

    assertThat(scheduler).isTrue();
    wm.verify(
        1,
        postRequestedFor(urlPathEqualTo(f.path("/rest_v2/login")))
            .withRequestBody(containing("j_username=jasperadmin"))
            .withRequestBody(containing("j_password=" + AdapterFixture.PASSWORD)));
    wm.verify(
        getRequestedFor(urlPathEqualTo(f.path("/rest_v2/jobs")))
            .withHeader("Cookie", containing("JSESSIONID=F0RM")));
    // second call reuses the session, no second login (issue #46: form login sends no
    // credential-less probe, and only real logins carry j_username)
    f.adapter.schedulerReachable();
    wm.verify(
        1,
        postRequestedFor(urlPathEqualTo(f.path("/rest_v2/login")))
            .withRequestBody(containing("j_username=")));
  }

  /**
   * Review finding 2.3: a form session was established once and never again, so after a service
   * restart or a session timeout every call answered 401 until the process was restarted. A 401 in
   * form mode now clears the session, logs in once and replays the request.
   */
  @Test
  void should_log_in_again_and_replay_the_request_when_the_form_session_expires() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.FORM);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/login"))).willReturn(aResponse().withStatus(405)));
    // a POST without credentials (a probe of an unlisted version) must not consume a login state
    wm.stubFor(
        post(urlPathEqualTo(f.path("/rest_v2/login")))
            .atPriority(10)
            .willReturn(aResponse().withStatus(401)));
    wm.stubFor(
        post(urlPathEqualTo(f.path("/rest_v2/login")))
            .withRequestBody(containing("j_username="))
            .atPriority(1)
            .inScenario("login")
            .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Set-Cookie", "JSESSIONID=F1RST; Path=/jasperserver-pro"))
            .willSetStateTo("second"));
    wm.stubFor(
        post(urlPathEqualTo(f.path("/rest_v2/login")))
            .withRequestBody(containing("j_username="))
            .atPriority(1)
            .inScenario("login")
            .whenScenarioStateIs("second")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Set-Cookie", "JSESSIONID=SEC0ND; Path=/jasperserver-pro")));
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/jobs")))
            .withHeader("Cookie", containing("JSESSIONID=F1RST"))
            .inScenario("session")
            .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
            .willReturn(aResponse().withStatus(200))
            .willSetStateTo("expired"));
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/jobs")))
            .withHeader("Cookie", containing("JSESSIONID=F1RST"))
            .inScenario("session")
            .whenScenarioStateIs("expired")
            .willReturn(aResponse().withStatus(401)));
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/jobs")))
            .withHeader("Cookie", containing("JSESSIONID=SEC0ND"))
            .willReturn(aResponse().withStatus(200)));

    assertThat(f.adapter.schedulerReachable()).as("first call logs in").isTrue();
    assertThat(f.adapter.schedulerReachable()).as("session expired under the second call").isTrue();

    wm.verify(
        2,
        postRequestedFor(urlPathEqualTo(f.path("/rest_v2/login")))
            .withRequestBody(containing("j_username=jasperadmin")));
    wm.verify(
        1,
        getRequestedFor(urlPathEqualTo(f.path("/rest_v2/jobs")))
            .withHeader("Cookie", containing("JSESSIONID=SEC0ND")));
  }

  @Test
  void should_login_through_spring_form_when_rest_login_is_absent() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.FORM);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/login"))).willReturn(aResponse().withStatus(404)));
    wm.stubFor(
        post(urlPathEqualTo(f.path("/j_spring_security_check")))
            .willReturn(
                aResponse()
                    .withStatus(302)
                    .withHeader("Location", f.path("/"))
                    .withHeader("Set-Cookie", "JSESSIONID=SPR1NG; Path=/jasperserver-pro")));

    Session s =
        f.adapter.login(
            new com.jaspersoft.jrsupgrade.jrs.api.Credentials(
                "jasperadmin", f.password, java.util.Optional.of("org_1")));

    assertThat(s.mode()).isEqualTo(Session.AuthMode.FORM);
    assertThat(s.cookie()).contains("SPR1NG");
    wm.verify(
        postRequestedFor(urlPathEqualTo(f.path("/j_spring_security_check")))
            .withRequestBody(containing("j_username=jasperadmin%7Corg_1")));
  }

  @Test
  void should_reject_form_login_when_spring_redirects_to_error_page() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.FORM);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/login"))).willReturn(aResponse().withStatus(404)));
    wm.stubFor(
        post(urlPathEqualTo(f.path("/j_spring_security_check")))
            .willReturn(
                aResponse()
                    .withStatus(302)
                    .withHeader("Location", f.path("/login.html?error=1"))
                    .withHeader("Set-Cookie", "JSESSIONID=ANON; Path=/jasperserver-pro")));

    assertThatThrownBy(
            () ->
                f.adapter.login(
                    new com.jaspersoft.jrsupgrade.jrs.api.Credentials(
                        "jasperadmin", f.password, java.util.Optional.empty())))
        .isInstanceOf(RestException.class)
        .hasMessageContaining("login rejected")
        .hasMessageNotContaining(AdapterFixture.PASSWORD);
  }
}
