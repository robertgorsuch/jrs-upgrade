package com.jaspersoft.jrsupgrade.core.selfcheck;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SelfCheckTest {

  @Test
  void should_pass_all_builtin_checks_when_running_on_the_build_jdk() {
    SelfCheck.Report report = new SelfCheck().run();
    assertThat(report.ok()).as(report.toString()).isTrue();
    assertThat(report.items())
        .extracting(SelfCheck.Item::name)
        .contains("runtime", "version", "config-schema");
  }

  /** Review finding 3.1: selfcheck names the host and fails it when it is outside ADR-0002. */
  @Test
  void should_fail_the_platform_item_when_the_host_is_not_windows_or_linux_x86_64() {
    SelfCheck.Item mac = SelfCheck.platform("Mac OS X", "aarch64");
    SelfCheck.Item arm = SelfCheck.platform("Linux", "aarch64");
    SelfCheck.Item ok = SelfCheck.platform("Windows 11", "amd64");

    assertThat(mac.name()).isEqualTo(SelfCheck.PLATFORM);
    assertThat(mac.status()).isEqualTo(SelfCheck.Status.FAIL);
    assertThat(mac.detail()).contains("Mac OS X").contains("aarch64").contains("ADR-0002");
    assertThat(arm.status()).isEqualTo(SelfCheck.Status.FAIL);
    assertThat(ok.status()).isEqualTo(SelfCheck.Status.PASS);
    assertThat(ok.detail()).contains("Windows 11").contains("amd64");
    assertThat(new SelfCheck().run().items())
        .extracting(SelfCheck.Item::name)
        .contains(SelfCheck.PLATFORM);
  }

  @Test
  void should_report_a_failing_check_when_a_registered_check_throws() {
    SelfCheck.Report report =
        new SelfCheck()
            .add(
                () -> {
                  throw new IllegalStateException("boom");
                })
            .run();
    assertThat(report.ok()).isFalse();
    assertThat(report.items())
        .anyMatch(i -> i.status() == SelfCheck.Status.FAIL && i.detail().contains("boom"));
  }
}
