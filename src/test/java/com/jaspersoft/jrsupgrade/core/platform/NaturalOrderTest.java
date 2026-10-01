package com.jaspersoft.jrsupgrade.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NaturalOrderTest {

  @Test
  void should_put_10_after_9_when_names_differ_only_in_a_version() {
    assertThat(NaturalOrder.compare("jasperreports-server-9.0.0", "jasperreports-server-10.0.0"))
        .isNegative();
    assertThat(NaturalOrder.compare("apache-tomcat-10.1.18", "apache-tomcat-9.0.85")).isPositive();
    assertThat(NaturalOrder.compare("9.0.0", "9.0.0")).isZero();
  }

  @Test
  void should_order_a_shorter_prefix_first_and_stay_total_when_names_differ_in_case_or_zeros() {
    assertThat(NaturalOrder.compare("tomcat", "tomcat9")).isNegative();
    assertThat(NaturalOrder.compare("a07", "a7")).isNotZero();
    assertThat(NaturalOrder.compare("a07", "a7")).isEqualTo(-NaturalOrder.compare("a7", "a07"));
    assertThat(NaturalOrder.compare("Tomcat", "tomcat")).isNotZero();
    // a digit run longer than a long still compares by value
    assertThat(NaturalOrder.compare("v99999999999999999999", "v100000000000000000000"))
        .isNegative();
  }

  @Test
  void should_sort_paths_by_version_when_used_as_a_comparator() {
    List<Path> paths =
        new ArrayList<>(
            List.of(
                Path.of("jasperreports-server-10.0.0"),
                Path.of("jasperreports-server-8.2.0"),
                Path.of("jasperreports-server-9.0.0")));

    paths.sort(NaturalOrder.PATHS);

    assertThat(paths)
        .containsExactly(
            Path.of("jasperreports-server-8.2.0"),
            Path.of("jasperreports-server-9.0.0"),
            Path.of("jasperreports-server-10.0.0"));
  }

  @Test
  void should_compare_only_the_numbers_when_comparing_versions() {
    assertThat(NaturalOrder.compareVersions("10.0.0", "9.0.0")).isPositive();
    assertThat(NaturalOrder.compareVersions("9.0", "9.0.0")).isNegative();
    assertThat(NaturalOrder.compareVersions("apache-tomcat-9", "tomcat9")).isZero();
    assertThat(NaturalOrder.compareVersions("apache-tomcat", "tomcat")).isZero();
    assertThat(NaturalOrder.compareVersions("apache-tomcat", "apache-tomcat-9")).isNegative();
  }
}
