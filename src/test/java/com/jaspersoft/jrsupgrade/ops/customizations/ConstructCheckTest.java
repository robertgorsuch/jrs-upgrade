package com.jaspersoft.jrsupgrade.ops.customizations;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.compat.CompatMatrix;
import com.jaspersoft.jrsupgrade.core.compat.UpgradeRules;
import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.ConstructFinding;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Issue #8: constructs in NGRA's 8.2 overrides that a 10.1 target handles differently. */
class ConstructCheckTest {

  private final List<UpgradeRules.ConstructRule> rules =
      CompatMatrix.load().rules().constructs("8.2.0", "10.1.0");

  static final String NGRA_CONTEXT =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <beans xmlns="http://www.springframework.org/schema/beans"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xmlns:security="http://www.springframework.org/schema/security"
             xsi:schemaLocation="http://www.springframework.org/schema/beans
               http://www.springframework.org/schema/beans/spring-beans.xsd
               http://www.springframework.org/schema/security
               http://www.springframework.org/schema/security/spring-security-4.2.xsd">
        <bean id="sessionFactory" class="org.springframework.orm.hibernate5.LocalSessionFactoryBean">
          <property name="annotatedClasses">
            <list><value>com.ngra.ReportLog</value></list>
          </property>
        </bean>
        <bean id="other">
          <property name="annotatedClasses"><list/></property>
        </bean>
        <security:filter-chain pattern="/rest_v2/**" filters="ngraPreAuthFilter"/>
      </beans>
      """;

  private List<ConstructFinding> check(String path, String content) {
    return ConstructCheck.check(path, content.getBytes(StandardCharsets.UTF_8), rules);
  }

  /** The acceptance of issue #8. */
  @Test
  void should_report_annotated_classes_on_the_session_factory_with_the_persistence_xml_mechanism() {
    List<ConstructFinding> found = check("WEB-INF/ngra-applicationContext.xml", NGRA_CONTEXT);

    assertThat(found)
        .filteredOn(f -> f.rule().equals("session-factory-annotated-classes"))
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.hint()).contains("persistence.xml");
              assertThat(f.construct())
                  .contains("<property name=\"annotatedClasses\">")
                  .contains("inside <bean id=\"sessionFactory\">");
              assertThat(f.line()).isEqualTo(10);
              assertThat(f.source()).contains("NGRA");
            });
    assertThat(found)
        .extracting(ConstructFinding::rule)
        .contains("spring-security-versioned-schema", "custom-security-filter-chain");
  }

  @Test
  void should_report_javax_servlet_classes_in_web_xml_and_not_the_rest() {
    String webXml =
        """
        <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee">
          <servlet><servlet-name>a</servlet-name><servlet-class>javax.ws.rs.Legacy</servlet-class></servlet>
          <servlet><servlet-name>b</servlet-name><servlet-class>com.ngra.Fine</servlet-class></servlet>
        </web-app>
        """;
    assertThat(check("WEB-INF/web.xml", webXml))
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.rule()).isEqualTo("web-xml-javax-classes");
              assertThat(f.construct()).contains("javax.ws.rs.Legacy");
              assertThat(f.line()).isEqualTo(2);
            });
  }

  @Test
  void should_read_a_file_naming_an_old_dtd_without_fetching_it() {
    String dtd =
        """
        <?xml version="1.0"?>
        <!DOCTYPE beans PUBLIC "-//SPRING//DTD BEAN//EN" "http://www.springframework.org/dtd/spring-beans.dtd">
        <beans><bean id="sessionFactory"><property name="annotatedClasses"/></bean></beans>
        """;
    assertThat(check("WEB-INF/old-context.xml", dtd))
        .extracting(ConstructFinding::rule)
        .containsExactly("session-factory-annotated-classes");
  }

  @Test
  void should_report_csrfguard_properties_the_target_handles_differently() {
    String props =
        "org.owasp.csrfguard.UnprotectedMethods=GET,HEAD\n"
            + "org.owasp.csrfguard.TokenPerPage=false\n"
            + "org.owasp.csrfguard.Enabled=true\n";
    assertThat(check("WEB-INF/csrf/jrs.csrfguard.properties", props))
        .extracting(ConstructFinding::rule, ConstructFinding::line)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("csrfguard-unprotected-methods", 1),
            org.assertj.core.groups.Tuple.tuple("csrfguard-token-per-page-off", 2));
    assertThat(
            check(
                "WEB-INF/csrf/jrs.csrfguard.properties", "org.owasp.csrfguard.TokenPerPage=true\n"))
        .isEmpty();
  }

  @Test
  void should_report_a_file_that_does_not_parse_instead_of_failing() {
    assertThat(check("WEB-INF/broken.xml", "<beans><bean>"))
        .singleElement()
        .satisfies(f -> assertThat(f.rule()).isEqualTo(ConstructCheck.UNPARSABLE));
  }

  @Test
  void should_apply_no_rule_below_10_0() {
    assertThat(
            ConstructCheck.check(
                "WEB-INF/ngra-applicationContext.xml",
                NGRA_CONTEXT.getBytes(StandardCharsets.UTF_8),
                CompatMatrix.load().rules().constructs("8.2.0", "9.0.0")))
        .isEmpty();
  }
}
