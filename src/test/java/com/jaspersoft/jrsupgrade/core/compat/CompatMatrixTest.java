package com.jaspersoft.jrsupgrade.core.compat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompatMatrixTest {

  private final CompatMatrix matrix = CompatMatrix.load();

  @Test
  void should_load_unsigned_version_two_matrix_when_bundled() {
    // version 2 (2026-09-17): Java majors are sets, entries carry Tomcat ranges, paths carry modes
    assertThat(matrix.matrixVersion()).isEqualTo(2);
    assertThat(matrix.signed()).isFalse();
    assertThat(matrix.entries()).hasSize(6);
    assertThat(matrix.upgradePaths()).isNotEmpty();
  }

  /** Issue #1: the NGRA route, 8.2.0 to 10.1.0, is two documented hops. */
  @Test
  void should_route_through_10_0_when_8_2_to_10_1_has_no_single_path() {
    assertThat(matrix.route("8.2.0", "10.1.0", "newdb"))
        .contains(
            List.of(
                new CompatMatrix.RouteHop("8.2.0", "10.0.0", "newdb"),
                new CompatMatrix.RouteHop("10.0.0", "10.1.0", "samedb")));
  }

  @Test
  void should_not_route_when_a_single_path_covers_the_pair_or_no_route_exists() {
    assertThat(matrix.route("9.0.0", "10.1.0", "newdb")).isEmpty();
    assertThat(matrix.route("8.2.0", "12.0.0", "newdb")).isEmpty();
    assertThat(matrix.route("garbage", "10.1.0", "newdb")).isEmpty();
    // from 7.x every route crosses into 8.x with newdb, which only a first hop may run
    assertThat(matrix.route("7.5.0", "10.1.0", "samedb")).isEmpty();
    assertThat(matrix.route("7.5.0", "10.1.0", "newdb")).isPresent();
  }

  /** Every hop samedb is a route too: each migrates the database the hop before it left. */
  @Test
  void should_route_samedb_hop_by_hop_when_the_first_hop_is_samedb() {
    assertThat(matrix.route("8.2.0", "10.1.0", "samedb"))
        .contains(
            List.of(
                new CompatMatrix.RouteHop("8.2.0", "9.0.0", "samedb"),
                new CompatMatrix.RouteHop("9.0.0", "10.0.0", "samedb"),
                new CompatMatrix.RouteHop("10.0.0", "10.1.0", "samedb")));
  }

  @Test
  void should_prefer_the_fewest_hops_then_the_latest_stops_when_routing() throws IOException {
    String yaml =
        """
        matrixVersion: 2
        signed: false
        entries: []
        upgradePaths:
          - { from: ">=1.0.0 <2.0.0", to: ">=2.0.0 <4.0.0", modes: [newdb] }
          - { from: ">=2.0.0 <4.0.0", to: ">=3.0.0 <6.0.0", modes: [samedb] }
          - { from: ">=5.0.0 <6.0.0", to: ">=6.0.0 <7.0.0", modes: [samedb] }
        releases: ["2.0.0", "3.0.0", "4.0.0", "5.0.0"]
        """;
    CompatMatrix m =
        CompatMatrix.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

    assertThat(m.route("1.0.0", "6.0.0", "newdb"))
        .contains(
            List.of(
                new CompatMatrix.RouteHop("1.0.0", "3.0.0", "newdb"),
                new CompatMatrix.RouteHop("3.0.0", "5.0.0", "samedb"),
                new CompatMatrix.RouteHop("5.0.0", "6.0.0", "samedb")));
  }

  @Test
  void should_find_entry_when_version_is_inside_a_range() {
    assertThat(matrix.find("7.1.0"))
        .map(CompatMatrix.Entry::javaForBuildomatic)
        .contains(Set.of(8));
    assertThat(matrix.find("7.9.1"))
        .map(CompatMatrix.Entry::javaForBuildomatic)
        .contains(Set.of(8));
    assertThat(matrix.find("8.2.0"))
        .map(CompatMatrix.Entry::label)
        .contains("JasperReports Server 8.x");
    assertThat(matrix.find("9.0.0"))
        .map(CompatMatrix.Entry::javaForBuildomatic)
        .contains(Set.of(8, 11, 17));
    assertThat(matrix.find("10.0.0"))
        .map(CompatMatrix.Entry::javaForBuildomatic)
        .contains(Set.of(17));
    // platform-support 10.1.0 p.20 / release notes 10.1.0 p.14: JDK 21 added; 10.0.0 is 17 only
    assertThat(matrix.find("10.1.0"))
        .map(CompatMatrix.Entry::javaForBuildomatic)
        .contains(Set.of(17, 21));
    assertThat(matrix.find("10.0.5").map(CompatMatrix.Entry::label))
        .isNotEqualTo(matrix.find("10.1.0").map(CompatMatrix.Entry::label));
  }

  @Test
  void should_coerce_two_part_versions_when_looking_up() {
    assertThat(matrix.find("8.2")).isPresent();
    assertThat(matrix.javaRequiredFor("8.2")).containsExactly(8, 11);
  }

  @Test
  void should_return_empty_when_version_is_unsupported_or_garbage() {
    assertThat(matrix.find("6.4.3")).isEmpty();
    assertThat(matrix.find("11.0.0")).isEmpty();
    assertThat(matrix.find("not-a-version")).isEmpty();
    assertThat(matrix.find("")).isEmpty();
  }

  @Test
  void should_support_combination_when_every_dimension_is_listed() {
    assertThat(matrix.supports("9.0.0", "pro", "Tomcat", "PostgreSQL")).isTrue();
    assertThat(matrix.supports("7.5.0", "CE", "tomcat", "db2")).isTrue();
    assertThat(matrix.supports("9.0.0", "PRO", "jboss", "postgresql")).isFalse();
    assertThat(matrix.supports("9.0.0", "PRO", "tomcat", "sqlite")).isFalse();
    assertThat(matrix.supports("9.0.0", "ENTERPRISE", "tomcat", "postgresql")).isFalse();
    assertThat(matrix.supports("6.0.0", "PRO", "tomcat", "postgresql")).isFalse();
  }

  @Test
  void should_report_java_for_buildomatic_when_version_is_known() {
    // platform-support sheets: 8.2 certifies JDK 8 and 11; 9.0 lists 8, 11 and 17 (17 runtime
    // only); 10.0 is 17 only; 10.1 adds 21. The upgrade guides name no Java at all.
    assertThat(matrix.javaRequiredFor("7.2.0")).containsExactly(8);
    assertThat(matrix.javaRequiredFor("8.0.4")).containsExactly(8, 11);
    assertThat(matrix.javaRequiredFor("9.0.0")).containsExactly(8, 11, 17);
    assertThat(matrix.javaRequiredFor("10.0.0")).containsExactly(17);
    assertThat(matrix.javaRequiredFor("10.1.0")).containsExactly(17, 21);
    assertThat(CompatMatrix.describeJava(Set.of(17))).isEqualTo("Java 17");
    assertThat(CompatMatrix.describeJava(Set.of(8, 11, 17))).isEqualTo("Java 8, 11 or 17");
  }

  /**
   * Upgrade guides 10.1 pp.10-11, 10.0 pp.11-12, 9.0 pp.10-12, 8.2 §1.1.1: which source versions
   * each target accepts depends on the mode; samedb is only ever offered from the previous release
   * line.
   */
  @Test
  void should_judge_upgrade_paths_by_mode_when_the_vendor_offers_only_one() {
    assertThat(matrix.upgradePathSupported("9.0.0", "10.1.0", "newdb")).isTrue();
    assertThat(matrix.upgradePathSupported("9.0.0", "10.1.0", "samedb")).isFalse();
    assertThat(matrix.upgradePathSupported("10.0.0", "10.1.0", "samedb")).isTrue();
    assertThat(matrix.upgradePathSupported("8.2.0", "10.0.0", "newdb")).isTrue();
    assertThat(matrix.upgradePathSupported("8.2.0", "10.0.0", "samedb")).isFalse();
    assertThat(matrix.upgradePathSupported("8.2.0", "10.1.0", "newdb")).isFalse();
    assertThat(matrix.upgradePathSupported("8.2.0", "9.0.0", "samedb")).isTrue();
    assertThat(matrix.upgradePathSupported("8.0.0", "9.0.0", "samedb")).isFalse();
    assertThat(matrix.upgradePathSupported("8.0.0", "9.0.0", "newdb")).isTrue();
    assertThat(matrix.upgradePathSupported("7.9.0", "8.2.0", "samedb")).isFalse();
    assertThat(matrix.upgradePathSupported("7.9.0", "8.2.0", "newdb")).isTrue();
    assertThat(matrix.upgradePathSupported("8.0.0", "8.2.0", "SAMEDB")).isTrue();
    assertThat(matrix.upgradePathSupported("9.0.0", "10.1.0", "sideways")).isFalse();
    assertThat(matrix.upgradeModes("9.0.0", "10.1.0")).containsExactly("newdb");
    assertThat(matrix.upgradeModes("10.0.0", "10.1.0")).containsExactly("newdb", "samedb");
    assertThat(matrix.upgradeModes("8.2.0", "10.1.0")).isEmpty();
  }

  /**
   * Platform-support sheets: Tomcat 9 through 9.0; 10.0 moved to Jakarta EE and certifies Tomcat
   * 10.1.24+ and 11.0.11+ (installation guide 10.1 p.58).
   */
  @Test
  void should_judge_tomcat_versions_by_the_release_line() {
    assertThat(matrix.tomcatSupported("9.0.0", "9.0.85")).isTrue();
    assertThat(matrix.tomcatSupported("9.0.0", "10.1.24")).isFalse();
    assertThat(matrix.tomcatSupported("10.0.0", "10.1.24")).isTrue();
    assertThat(matrix.tomcatSupported("10.0.0", "10.1.5")).isFalse();
    assertThat(matrix.tomcatSupported("10.0.0", "9.0.85")).isFalse();
    assertThat(matrix.tomcatSupported("10.1.0", "11.0.11")).isTrue();
    assertThat(matrix.tomcatSupported("10.1.0", "garbage")).isFalse();
    assertThat(matrix.tomcatSupported("6.0.0", "9.0.85")).isFalse();
    assertThat(matrix.find("10.1.0")).map(CompatMatrix.Entry::tomcat).isPresent();
  }

  @Test
  void should_treat_a_path_without_modes_and_an_entry_without_tomcat_as_unrestricted()
      throws IOException {
    String yaml =
        """
        matrixVersion: 1
        signed: false
        entries:
          - range: "1.x"
            editions: [ce]
            appServers: [Tomcat]
            javaForBuildomatic: 21
            databases: [PostgreSQL]
        upgradePaths:
          - { from: "1.x", to: "1.x" }
        """;

    CompatMatrix m =
        CompatMatrix.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

    assertThat(m.javaRequiredFor("1.0.0")).containsExactly(21);
    assertThat(m.upgradePathSupported("1.0.0", "1.2.3", "samedb")).isTrue();
    assertThat(m.upgradePathSupported("1.0.0", "1.2.3", "newdb")).isTrue();
    assertThat(m.tomcatSupported("1.0.0", "9.0.1")).isTrue();
  }

  @Test
  void should_throw_unsupported_when_asking_java_for_unknown_version() {
    assertThatThrownBy(() -> matrix.javaRequiredFor("6.0.0"))
        .isInstanceOf(UnsupportedVersionException.class)
        .hasMessageContaining("6.0.0")
        .hasMessageContaining("--allow-unsupported");
  }

  @Test
  void should_accept_listed_upgrade_paths_when_target_is_newer() {
    assertThat(matrix.upgradePathSupported("7.9.1", "8.2.0")).isTrue();
    assertThat(matrix.upgradePathSupported("8.0.0", "9.0.0")).isTrue();
    assertThat(matrix.upgradePathSupported("8.2.0", "10.0.0")).isTrue();
    assertThat(matrix.upgradePathSupported("9.0.0", "10.0.0")).isTrue();
    assertThat(matrix.upgradePathSupported("8.0.0", "8.2.0")).isTrue();
  }

  @Test
  void should_reject_upgrade_path_when_unlisted_downgrade_or_same_version() {
    assertThat(matrix.upgradePathSupported("7.1.0", "9.0.0")).isFalse();
    assertThat(matrix.upgradePathSupported("7.1.0", "10.0.0")).isFalse();
    assertThat(matrix.upgradePathSupported("8.2.0", "8.0.0")).isFalse();
    assertThat(matrix.upgradePathSupported("8.2.0", "8.2.0")).isFalse();
    assertThat(matrix.upgradePathSupported("garbage", "8.2.0")).isFalse();
  }

  @Test
  void should_expect_capabilities_by_version_and_edition_when_listed() {
    assertThat(matrix.expectedCapabilities("7.1.0", "CE"))
        .containsExactlyInAnyOrder("EXPORT_ASYNC", "IMPORT_ASYNC", "REST_LOGIN");
    assertThat(matrix.expectedCapabilities("7.1.0", "PRO")).contains("ORGS");
    assertThat(matrix.expectedCapabilities("7.5.0", "CE"))
        .contains("KEYSTORE_ENCRYPTION")
        .doesNotContain("TOKEN_AUTH", "ORGS");
    assertThat(matrix.expectedCapabilities("8.0.0", "pro"))
        .contains("TOKEN_AUTH", "PREAUTH", "ORGS", "KEYSTORE_ENCRYPTION");
    assertThat(matrix.expectedCapabilities("10.0.0", "CE"))
        .contains("TOKEN_AUTH")
        .doesNotContain("ORGS");
    assertThat(matrix.expectedCapabilities("6.0.0", "PRO")).isEmpty();
  }

  @Test
  void should_parse_minimal_document_when_loaded_from_stream() throws IOException {
    String yaml =
        """
        matrixVersion: 1
        signed: false
        entries:
          - range: "1.x"
            editions: [ce]
            appServers: [Tomcat]
            javaForBuildomatic: 21
            databases: [PostgreSQL]
        """;

    CompatMatrix m =
        CompatMatrix.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

    assertThat(m.supports("1.2.3", "CE", "tomcat", "postgresql")).isTrue();
    assertThat(m.expectedCapabilities("1.2.3", "CE")).isEmpty();
    assertThat(m.upgradePathSupported("1.0.0", "1.2.3")).isFalse();
    assertThat(m.find("1.0.0")).map(CompatMatrix.Entry::label).contains("1.x");
  }
}
