package com.jaspersoft.jrsupgrade.jrs.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Issue #51: the login form body is built from the password's {@code char[]}, not a String. */
class FormBodiesTest {

  @Test
  void should_encode_exactly_like_url_encoder_when_the_password_has_reserved_and_non_ascii_chars() {
    String password = "p&ss wörd=1~%+*._-";

    byte[] body = FormBodies.login("jasperadmin|org_1", password.toCharArray());

    assertThat(new String(body, StandardCharsets.US_ASCII))
        .isEqualTo(
            "j_username="
                + URLEncoder.encode("jasperadmin|org_1", StandardCharsets.UTF_8)
                + "&j_password="
                + URLEncoder.encode(password, StandardCharsets.UTF_8));
  }

  @Test
  void should_encode_an_empty_password_when_none_is_given() {
    assertThat(new String(FormBodies.login("u", new char[0]), StandardCharsets.US_ASCII))
        .isEqualTo("j_username=u&j_password=");
  }
}
