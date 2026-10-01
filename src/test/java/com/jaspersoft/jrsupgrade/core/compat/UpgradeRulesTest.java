package com.jaspersoft.jrsupgrade.core.compat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class UpgradeRulesTest {

  private final UpgradeRules rules = CompatMatrix.load().rules();

  @Test
  void should_load_the_bundled_rules_with_a_source_for_every_one() {
    assertThat(rules.jarRules()).isNotEmpty();
    assertThat(rules.relocations()).isNotEmpty();
    assertThat(rules.constructs()).isNotEmpty();
    assertThat(rules.jarRules()).allSatisfy(r -> assertThat(r.source()).isNotBlank());
    assertThat(rules.relocations()).allSatisfy(r -> assertThat(r.source()).isNotBlank());
    assertThat(rules.constructs()).allSatisfy(r -> assertThat(r.source()).isNotBlank());
  }

  @Test
  void should_apply_a_rule_only_across_its_versions() {
    assertThat(rules.relocations("8.2.0", "10.1.0")).anyMatch(r -> r.id().equals("ehcache-xml"));
    assertThat(rules.relocations("10.0.0", "10.1.0")).noneMatch(r -> r.id().equals("ehcache-xml"));
    assertThat(rules.relocations("8.2.0", "9.0.0")).noneMatch(r -> r.id().equals("ehcache-xml"));
    assertThat(rules.jarRules("8.2.0", "9.0.0"))
        .anyMatch(r -> r.id().equals("progress-datadirect-drivers"));
    assertThat(rules.relocations("garbage", "10.1.0")).isEmpty();
  }

  @Test
  void should_match_globs_within_and_across_directories() {
    assertThat(UpgradeRules.glob("TI*.jar", "TIredshift.jar")).isTrue();
    assertThat(UpgradeRules.glob("WEB-INF/*.xml", "WEB-INF/web.xml")).isTrue();
    assertThat(UpgradeRules.glob("WEB-INF/*.xml", "WEB-INF/classes/x.xml")).isFalse();
    assertThat(UpgradeRules.glob("WEB-INF/**.xml", "WEB-INF/classes/x.xml")).isTrue();
    assertThat(
            UpgradeRules.glob("**/*csrfguard*.properties", "WEB-INF/csrf/jrs.csrfguard.properties"))
        .isTrue();
  }

  @Test
  void should_match_an_element_by_local_name_and_whole_attribute_values() {
    UpgradeRules.ElementMatch bean =
        new UpgradeRules.ElementMatch("bean", Map.of("id", "sessionFactory"));
    assertThat(bean.matches("bean", Map.of("id", "sessionFactory", "class", "x"))).isTrue();
    assertThat(bean.matches("bean", Map.of("id", "sessionFactory2"))).isFalse();
    assertThat(bean.matches("property", Map.of("id", "sessionFactory"))).isFalse();
  }
}
