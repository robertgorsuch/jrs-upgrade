package com.jaspersoft.jrsupgrade.ops.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.platform.RunningTomcats;
import com.jaspersoft.jrsupgrade.core.platform.ServiceConfig;
import com.jaspersoft.jrsupgrade.core.platform.ServiceController;
import com.jaspersoft.jrsupgrade.ops.ReportItem;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Issue #147: doctor sees a running Tomcat under the installation whatever service.kind says. */
class RunningTomcatCheckTest {

  private static final Path TOMCAT = Path.of("/opt/jasperreports-server/apache-tomcat");
  private static final RunningTomcats RUNNING =
      new RunningTomcats.Scanned(List.of(4242L), List.of());
  private static final RunningTomcats NONE = new RunningTomcats.Scanned(List.of(), List.of());

  private static ReportItem item(
      RunningTomcats scan, ServiceConfig.Kind kind, Optional<ServiceController.State> state) {
    return LocalChecks.runningTomcatItem(TOMCAT, scan, Optional.of(kind), state);
  }

  @Test
  void should_name_the_pid_when_a_tomcat_under_the_installation_runs() {
    ReportItem item = item(RUNNING, ServiceConfig.Kind.MANUAL, Optional.empty());

    assertThat(item.name()).isEqualTo(LocalChecks.RUNNING_TOMCAT);
    assertThat(item.status()).isEqualTo(ReportItem.Status.PASS);
    assertThat(item.detail()).contains(TOMCAT.toString()).contains("pid 4242");
  }

  @Test
  void should_warn_when_the_configured_service_is_stopped_but_the_tomcat_runs() {
    ReportItem item =
        item(
            RUNNING,
            ServiceConfig.Kind.WINDOWS_SERVICE,
            Optional.of(ServiceController.State.STOPPED));

    assertThat(item.status()).isEqualTo(ReportItem.Status.WARN);
    assertThat(item.detail()).contains("pid 4242").contains("STOPPED");
    assertThat(item.remediation()).contains("service.name").contains("catalina");
  }

  @Test
  void should_warn_when_the_configured_service_runs_but_no_tomcat_under_the_installation_does() {
    ReportItem item =
        item(NONE, ServiceConfig.Kind.SYSTEMD, Optional.of(ServiceController.State.RUNNING));

    assertThat(item.status()).isEqualTo(ReportItem.Status.WARN);
    assertThat(item.detail()).contains("RUNNING").contains("no Tomcat under");
    assertThat(item.remediation()).contains("service.name");
  }

  @Test
  void should_pass_when_nothing_runs_and_the_service_agrees() {
    ReportItem item =
        item(NONE, ServiceConfig.Kind.CATALINA, Optional.of(ServiceController.State.STOPPED));

    assertThat(item.status()).isEqualTo(ReportItem.Status.PASS);
    assertThat(item.detail()).contains("no Tomcat under " + TOMCAT + " is running");
  }

  /** The installer's LocalSystem service seen from an account that is not elevated (live run). */
  @Test
  void should_pass_when_the_service_runs_and_an_unreadable_process_may_be_its_tomcat() {
    ReportItem item =
        item(
            new RunningTomcats.Scanned(List.of(), List.of(11700L)),
            ServiceConfig.Kind.WINDOWS_SERVICE,
            Optional.of(ServiceController.State.RUNNING));

    assertThat(item.status()).isEqualTo(ReportItem.Status.PASS);
    assertThat(item.detail()).contains("RUNNING").contains("pid 11700").contains("cannot read");
  }

  @Test
  void should_warn_when_only_unreadable_processes_may_be_the_tomcat_and_nothing_confirms_it() {
    ReportItem item =
        item(
            new RunningTomcats.Scanned(List.of(), List.of(11700L, 9544L)),
            ServiceConfig.Kind.MANUAL,
            Optional.empty());

    assertThat(item.status()).isEqualTo(ReportItem.Status.WARN);
    assertThat(item.detail()).contains("2 processes").contains("pid 9544, pid 11700");
    assertThat(item.remediation()).contains("elevated");
  }

  @Test
  void should_skip_when_the_scan_is_unavailable() {
    ReportItem item =
        item(
            new RunningTomcats.Unavailable("the process scan failed: tasklist exited 1"),
            ServiceConfig.Kind.MANUAL,
            Optional.empty());

    assertThat(item.status()).isEqualTo(ReportItem.Status.SKIP);
    assertThat(item.detail()).contains("tasklist exited 1");
  }
}
