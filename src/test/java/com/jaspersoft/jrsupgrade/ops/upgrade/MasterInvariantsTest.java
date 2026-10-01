package com.jaspersoft.jrsupgrade.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Upgrade guide 10.1 pp.12-14 and installation guide 10.1 pp.16, 263 (review §1.3): an upgrade
 * never turns a Compact installation into a Split one or back, and Oracle needs {@code dbVersion}
 * from 10.1 on.
 */
class MasterInvariantsTest {

  private static final Map<String, String> COMPACT =
      Map.of("dbType", "postgresql", "dbHost", "localhost");
  private static final Map<String, String> SPLIT =
      Map.of(
          "dbType", "postgresql",
          "installType", "split",
          "audit.dbHost", "audit-host",
          "audit.dbName", "jsaudit");

  @Test
  void should_read_compact_when_install_type_is_absent() {
    assertThat(MasterInvariants.installType(COMPACT)).isEqualTo("compact");
    assertThat(MasterInvariants.installType(Map.of("installType", " Split "))).isEqualTo("split");
  }

  @Test
  void should_accept_when_the_target_has_no_master_properties() {
    assertThat(MasterInvariants.installTypeProblem(COMPACT, Map.of())).isEmpty();
    assertThat(MasterInvariants.installTypeProblem(SPLIT, Map.of())).isEmpty();
  }

  @Test
  void should_accept_when_the_target_repeats_the_installed_type_and_audit_keys() {
    assertThat(MasterInvariants.installTypeProblem(SPLIT, SPLIT)).isEmpty();
    assertThat(
            MasterInvariants.installTypeProblem(
                SPLIT, Map.of("installType", "split", "audit.dbHost", "other-host")))
        .as("a value the installed file overrides is not a crossing")
        .isEmpty();
  }

  @Test
  void should_refuse_when_the_target_would_switch_compact_to_split() {
    Optional<String> problem =
        MasterInvariants.installTypeProblem(COMPACT, Map.of("installType", "split"));

    assertThat(problem).isPresent();
    assertThat(problem.get())
        .contains("installed server is a compact installation")
        .contains("target default_master.properties says installType=split");
  }

  @Test
  void should_refuse_when_the_target_adds_audit_keys_the_installation_lacks() {
    Optional<String> problem =
        MasterInvariants.installTypeProblem(
            SPLIT, Map.of("installType", "split", "audit.dbPort", "5433", "audit.sid", "ORCL"));

    assertThat(problem).isPresent();
    assertThat(problem.get()).contains("audit.dbPort").contains("audit.sid");
  }

  @Test
  void should_ignore_audit_password_keys_the_operator_adds_to_the_target() {
    assertThat(
            MasterInvariants.installTypeProblem(
                SPLIT, Map.of("audit.dbPassword", "x", "audit.sysPassword", "y")))
        .isEmpty();
  }

  @Test
  void should_require_db_version_when_oracle_and_target_is_10_1_or_later() {
    Map<String, String> oracle = Map.of("dbType", "oracle", "dbHost", "localhost");

    assertThat(MasterInvariants.dbVersionProblem(oracle, Map.of(), "10.1.0")).isPresent();
    assertThat(MasterInvariants.dbVersionProblem(oracle, Map.of(), "10.1.0").get())
        .contains("dbType=oracle")
        .contains("dbVersion");
    assertThat(MasterInvariants.dbVersionProblem(oracle, Map.of(), "10.2.0")).isPresent();
    assertThat(MasterInvariants.dbVersionProblem(oracle, Map.of(), "11.0.0")).isPresent();
  }

  @Test
  void should_not_require_db_version_before_10_1_or_for_other_databases() {
    Map<String, String> oracle = Map.of("dbType", "oracle");

    assertThat(MasterInvariants.dbVersionProblem(oracle, Map.of(), "10.0.0")).isEmpty();
    assertThat(MasterInvariants.dbVersionProblem(oracle, Map.of(), "9.0.0")).isEmpty();
    assertThat(MasterInvariants.dbVersionProblem(COMPACT, Map.of(), "10.1.0")).isEmpty();
  }

  @Test
  void should_accept_db_version_from_either_side() {
    Map<String, String> oracle = Map.of("dbType", "Oracle", "dbVersion", "19c");

    assertThat(MasterInvariants.dbVersionProblem(oracle, Map.of(), "10.1.0")).isEmpty();
    assertThat(
            MasterInvariants.dbVersionProblem(
                Map.of("dbType", "oracle"), Map.of("dbVersion", "21c"), "10.1.0"))
        .isEmpty();
    assertThat(
            MasterInvariants.dbVersionProblem(
                Map.of("dbType", "oracle"), Map.of("dbVersion", "  "), "10.1.0"))
        .as("a blank value is as good as none")
        .isPresent();
  }

  @Test
  void should_take_the_database_type_from_the_target_when_the_installation_has_none() {
    assertThat(
            MasterInvariants.dbVersionProblem(
                Map.of("dbHost", "h"), Map.of("dbType", "oracle"), "10.1.0"))
        .isPresent();
  }
}
