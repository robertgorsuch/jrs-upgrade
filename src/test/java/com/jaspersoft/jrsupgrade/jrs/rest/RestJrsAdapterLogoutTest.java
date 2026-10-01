package com.jaspersoft.jrsupgrade.jrs.rest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.jaspersoft.jrsupgrade.core.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Issue #114: the adapter ends the form session it opened, and only that one. */
class RestJrsAdapterLogoutTest {

  @RegisterExtension
  WireMockExtension wm =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  private void formServer(AdapterFixture f) {
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
    wm.stubFor(
        get(urlPathEqualTo(f.path("/logout.html")))
            .willReturn(aResponse().withStatus(302).withHeader("Location", "login.html")));
  }

  @Test
  void should_send_logout_with_the_session_cookie_when_a_form_login_happened() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.FORM);
    formServer(f);
    f.adapter.schedulerReachable();

    f.adapter.close();

    wm.verify(
        1,
        getRequestedFor(urlPathEqualTo(f.path("/logout.html")))
            .withHeader("Cookie", containing("JSESSIONID=F0RM")));
  }

  @Test
  void should_send_logout_once_when_closed_twice() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.FORM);
    formServer(f);
    f.adapter.schedulerReachable();

    f.adapter.close();
    f.adapter.close();

    wm.verify(1, getRequestedFor(urlPathEqualTo(f.path("/logout.html"))));
  }

  @Test
  void should_send_nothing_when_no_login_happened() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.FORM);
    formServer(f);

    f.adapter.close();

    wm.verify(0, getRequestedFor(urlPathEqualTo(f.path("/logout.html"))));
  }

  @Test
  void should_send_nothing_when_auth_is_basic() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.BASIC);
    formServer(f);
    wm.stubFor(
        get(urlPathEqualTo(f.path("/rest_v2/jobs"))).willReturn(aResponse().withStatus(200)));
    f.adapter.schedulerReachable();

    f.adapter.close();

    wm.verify(0, getRequestedFor(urlPathEqualTo(f.path("/logout.html"))));
  }

  @Test
  void should_not_throw_when_the_server_fails_the_logout() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.FORM);
    formServer(f);
    wm.stubFor(get(urlPathEqualTo(f.path("/logout.html"))).willReturn(aResponse().withStatus(500)));
    f.adapter.schedulerReachable();

    assertThatCode(f.adapter::close).doesNotThrowAnyException();
  }

  @Test
  void should_not_throw_when_the_server_is_gone() {
    AdapterFixture f = new AdapterFixture(wm, Config.AuthMode.FORM);
    formServer(f);
    f.adapter.schedulerReachable();
    wm.shutdownServer();

    assertThatCode(f.adapter::close).doesNotThrowAnyException();
  }
}
