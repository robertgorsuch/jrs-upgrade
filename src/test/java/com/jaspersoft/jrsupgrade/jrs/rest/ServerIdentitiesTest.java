package com.jaspersoft.jrsupgrade.jrs.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.jrs.api.ServerIdentity;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ServerIdentitiesTest {

  static final URI BASE = URI.create("http://localhost:8080/jasperserver-pro");

  static String fixture(String name) throws IOException {
    try (InputStream in =
        ServerIdentitiesTest.class.getResourceAsStream("/fixtures/serverInfo-" + name + ".json")) {
      if (in == null) {
        throw new IOException("missing fixture " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "7.1.0-CE,   7.1.0,  CE,  SINGLE, 20180406_1140",
    "7.1.0-PRO,  7.1.0,  PRO, MULTI,  20180406_1140",
    "7.5.0-CE,   7.5.0,  CE,  SINGLE, 20200115_1200",
    "7.9.1-PRO,  7.9.1,  PRO, SINGLE, 20210113_1017",
    "8.2.0-CE,   8.2.0,  CE,  SINGLE, 20230210_0923",
    "8.2.0-PRO,  8.2.0,  PRO, MULTI,  20230210_0923",
    "9.0.0-CE,   9.0.0,  CE,  SINGLE, 20240315_1421",
    "9.0.0-PRO,  9.0.0,  PRO, MULTI,  20240315_1421",
    "10.0.0-CE,  10.0.0, CE,  SINGLE, 20250601_0800",
    "10.0.0-PRO, 10.0.0, PRO, MULTI,  20250601_0800"
  })
  void should_parse_identity_when_fixture_is_realistic(
      String fixture, String version, String edition, String tenancy, String build)
      throws IOException {
    ServerIdentity id = ServerIdentities.parse(BASE, fixture(fixture));

    assertThat(id.version()).isEqualTo(version);
    assertThat(id.edition()).isEqualTo(ServerIdentity.Edition.valueOf(edition));
    assertThat(id.tenancy()).isEqualTo(ServerIdentity.Tenancy.valueOf(tenancy));
    assertThat(id.build()).isEqualTo(build);
    assertThat(id.baseUrl()).isEqualTo(BASE);
    assertThat(id.dateFormat()).isEqualTo("yyyy-MM-dd");
    if (tenancy.equals("MULTI")) {
      assertThat(id.features()).contains("MT", "Fusion", "AUD");
    } else {
      assertThat(id.features()).doesNotContain("MT");
    }
  }

  @ParameterizedTest
  @CsvSource({
    "8.2.0_build-1234, 8.2.0",
    "9.0, 9.0.0",
    "10.0.0-SNAPSHOT, 10.0.0",
    "7.9.1, 7.9.1",
    "'  8.1.0 ', 8.1.0"
  })
  void should_normalise_version_when_suffix_or_patch_varies(String raw, String expected) {
    assertThat(ServerIdentities.normaliseVersion(raw)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({"nonsense", "''", "v"})
  void should_reject_version_when_no_digits(String raw) {
    assertThatThrownBy(() -> ServerIdentities.normaliseVersion(raw))
        .isInstanceOf(RestException.class);
  }

  @ParameterizedTest
  @CsvSource({
    "CE, Enterprise, CE",
    "pro, Community, PRO",
    "'', Community Edition, CE",
    "'', Enterprise, PRO",
    "'', Professional, PRO",
    "unknown, '', UNKNOWN",
    "'', '', UNKNOWN"
  })
  void should_fall_back_to_edition_name_when_code_is_missing(
      String code, String name, String expected) {
    assertThat(ServerIdentities.edition(code, name))
        .isEqualTo(ServerIdentity.Edition.valueOf(expected));
  }
}
