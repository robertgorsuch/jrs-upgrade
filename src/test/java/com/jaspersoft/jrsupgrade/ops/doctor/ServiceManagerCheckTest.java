package com.jaspersoft.jrsupgrade.ops.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.platform.LinuxInit;
import com.jaspersoft.jrsupgrade.core.platform.ServiceConfig;
import com.jaspersoft.jrsupgrade.ops.ReportItem;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Review finding 3.2: doctor names the Linux service manager rather than assuming systemd. */
class ServiceManagerCheckTest {

  private static final LinuxInit.Detected SYSTEMD =
      new LinuxInit.Detected(
          LinuxInit.Kind.SYSTEMD, Optional.of("systemd"), "systemd is process 1");
  private static final LinuxInit.Detected SUPERVISOR =
      new LinuxInit.Detected(
          LinuxInit.Kind.SUPERVISOR, Optional.of("supervisord"), "supervisord is process 1");
  private static final LinuxInit.Detected NONE =
      new LinuxInit.Detected(
          LinuxInit.Kind.OTHER, Optional.of("catalina.sh"), "no service manager detected");

  @Test
  void should_pass_when_process_one_is_systemd() {
    ReportItem item =
        LocalChecks.serviceManagerItem(Optional.of(ServiceConfig.Kind.SYSTEMD), SYSTEMD);

    assertThat(item.name()).isEqualTo(LocalChecks.SERVICE_MANAGER);
    assertThat(item.status()).isEqualTo(ReportItem.Status.PASS);
  }

  @Test
  void should_fail_when_service_kind_is_systemd_but_process_one_is_not() {
    ReportItem item =
        LocalChecks.serviceManagerItem(Optional.of(ServiceConfig.Kind.SYSTEMD), SUPERVISOR);

    assertThat(item.status()).isEqualTo(ReportItem.Status.FAIL);
    assertThat(item.detail()).contains("supervisord");
    assertThat(item.remediation()).contains("manual");
  }

  @Test
  void should_warn_when_a_script_kind_is_used_under_a_supervisor() {
    ReportItem item =
        LocalChecks.serviceManagerItem(Optional.of(ServiceConfig.Kind.CATALINA), SUPERVISOR);

    assertThat(item.status()).isEqualTo(ReportItem.Status.WARN);
    assertThat(item.remediation()).contains("may restart the server mid-run");
  }

  @Test
  void should_warn_rather_than_contradict_the_configuration_when_process_one_cannot_be_read() {
    LinuxInit.Detected unknown =
        new LinuxInit.Detected(
            LinuxInit.Kind.UNKNOWN, Optional.empty(), "cannot read /proc/1/comm");

    ReportItem item =
        LocalChecks.serviceManagerItem(Optional.of(ServiceConfig.Kind.SYSTEMD), unknown);

    assertThat(item.status()).isEqualTo(ReportItem.Status.WARN);
    assertThat(item.remediation()).contains("taken on trust");
  }

  @Test
  void should_warn_when_no_service_manager_is_detected() {
    ReportItem item = LocalChecks.serviceManagerItem(Optional.empty(), NONE);

    assertThat(item.status()).isEqualTo(ReportItem.Status.WARN);
    assertThat(item.detail()).contains("no service manager detected");
  }
}
