package com.jaspersoft.jrsupgrade.ops.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.platform.ServiceConfig;
import com.jaspersoft.jrsupgrade.core.platform.ServiceController;
import com.jaspersoft.jrsupgrade.ops.FakeLayout;
import com.jaspersoft.jrsupgrade.ops.FakeServices;
import com.jaspersoft.jrsupgrade.ops.JsConfig;
import com.jaspersoft.jrsupgrade.ops.ReportItem;
import com.jaspersoft.jrsupgrade.ops.ReportItem.Status;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Vendor documentation review §3.3 (issue #113): the doctor items the vendor guides give a reason
 * for. Each names the guide page in its text, so an operator can read on.
 */
class VendorDoctorItemsTest {

  @TempDir Path tmp;

  private Path webapp(String properties) throws Exception {
    Path webapp = Files.createDirectories(tmp.resolve("webapps").resolve("jasperserver-pro"));
    Path file = Files.createDirectories(webapp.resolve("WEB-INF")).resolve("js.config.properties");
    Files.writeString(file, properties, StandardCharsets.ISO_8859_1);
    return webapp;
  }

  @Test
  void should_read_the_two_switches_from_js_config_properties() throws Exception {
    Path webapp = webapp("heartbeat.enabled=True\nfeature.audit_monitoring.enabled = false\nx=y\n");
    JsConfig cfg = JsConfig.read(webapp).orElseThrow();
    assertThat(cfg.flag(JsConfig.HEARTBEAT)).contains(true);
    assertThat(cfg.flag(JsConfig.AUDIT)).contains(false);
    assertThat(cfg.flag("x")).isEmpty();
    assertThat(cfg.flag("missing")).isEmpty();
    assertThat(JsConfig.read(tmp.resolve("nowhere"))).isEmpty();
  }

  @Test
  void should_warn_on_telemetry_upload_and_name_the_isolated_mode() throws Exception {
    Path webapp = webapp("heartbeat.enabled=true\n");
    ReportItem on = LocalChecks.telemetryItem(JsConfig.read(webapp), true, webapp);
    assertThat(on.status()).isEqualTo(Status.WARN);
    assertThat(on.detail()).contains("telemetry").contains("isolated");
    assertThat(on.remediation()).contains("heartbeat.enabled=false").contains("hotfix apply");

    Path off = webapp("heartbeat.enabled=false\n");
    assertThat(LocalChecks.telemetryItem(JsConfig.read(off), false, off).status())
        .isEqualTo(Status.PASS);

    Path unset = webapp("other=1\n");
    assertThat(LocalChecks.telemetryItem(JsConfig.read(unset), false, unset).status())
        .isEqualTo(Status.PASS);

    ReportItem none = LocalChecks.telemetryItem(Optional.empty(), false, tmp);
    assertThat(none.status()).isEqualTo(Status.SKIP);
    assertThat(none.remediation()).contains("cumulative hotfix");
  }

  @Test
  void should_warn_that_event_exports_can_be_very_large_when_audit_monitoring_is_on()
      throws Exception {
    Path webapp = webapp("feature.audit_monitoring.enabled=true\n");
    ReportItem on = LocalChecks.auditItem(JsConfig.read(webapp), webapp);
    assertThat(on.status()).isEqualTo(Status.WARN);
    assertThat(on.detail())
        .contains("very large")
        .contains("pp.250, 416")
        .contains("--include-events");

    Path off = webapp("feature.audit_monitoring.enabled=false\n");
    assertThat(LocalChecks.auditItem(JsConfig.read(off), off).status()).isEqualTo(Status.PASS);
    assertThat(LocalChecks.auditItem(Optional.empty(), tmp).status()).isEqualTo(Status.SKIP);
  }

  private static ServiceController companion(ServiceController.State state) {
    return new ServiceController() {
      @Override
      public State state() {
        return state;
      }

      @Override
      public State stop(Duration timeout) {
        return state;
      }

      @Override
      public State start(Duration timeout) {
        return state;
      }

      @Override
      public String describe() {
        return "Windows service jasperreportsPostgreSQL";
      }
    };
  }

  @Test
  void should_judge_the_bundled_database_service_by_kind_and_state() {
    ReportItem running =
        LocalChecks.databaseServiceItem(
            ServiceConfig.Kind.WINDOWS_SERVICE,
            Optional.of(companion(ServiceController.State.RUNNING)));
    assertThat(running.status()).isEqualTo(Status.PASS);
    assertThat(running.detail()).contains("jasperreportsPostgreSQL").contains("p.51");

    ReportItem stopped =
        LocalChecks.databaseServiceItem(
            ServiceConfig.Kind.SYSTEMD, Optional.of(companion(ServiceController.State.STOPPED)));
    assertThat(stopped.status()).isEqualTo(Status.WARN);
    assertThat(stopped.remediation()).contains("start-service");

    assertThat(
            LocalChecks.databaseServiceItem(ServiceConfig.Kind.WINDOWS_SERVICE, Optional.empty())
                .status())
        .isEqualTo(Status.PASS);
    ReportItem script =
        LocalChecks.databaseServiceItem(ServiceConfig.Kind.CTLSCRIPT, Optional.empty());
    assertThat(script.status()).isEqualTo(Status.PASS);
    assertThat(script.detail()).contains("ctlscript.sh");
    assertThat(
            LocalChecks.databaseServiceItem(ServiceConfig.Kind.MANUAL, Optional.empty()).status())
        .isEqualTo(Status.PASS);
  }

  @Test
  void should_name_a_stale_pid_file_and_pass_a_live_or_absent_one() throws Exception {
    Path tomcat = Files.createDirectories(tmp.resolve("apache-tomcat"));
    assertThat(LocalChecks.pidFileItem(tomcat, pid -> true).status()).isEqualTo(Status.PASS);

    Path file = Files.createDirectories(tomcat.resolve("temp")).resolve("catalina.pid");
    Files.writeString(file, "4242\n", StandardCharsets.UTF_8);
    assertThat(LocalChecks.pidFileItem(tomcat, pid -> pid == 4242L).status())
        .isEqualTo(Status.PASS);

    ReportItem stale = LocalChecks.pidFileItem(tomcat, pid -> false);
    assertThat(stale.status()).isEqualTo(Status.WARN);
    assertThat(stale.detail()).contains("4242").contains("p.237");
    assertThat(stale.remediation()).contains("start-service");
    assertThat(file).as("doctor is read-only").exists();
  }

  @Test
  void should_spot_non_ascii_credentials() {
    assertThat(ServerChecks.hasNonAscii("jasperadmin")).isFalse();
    assertThat(ServerChecks.hasNonAscii("jasperädmin")).isTrue();
    assertThat(ServerChecks.hasNonAscii(new char[] {'a', 'b'})).isFalse();
    assertThat(ServerChecks.hasNonAscii(new char[] {'p', 'ü'})).isTrue();
  }

  private static Map<String, ReportItem> byName(DoctorReport report) {
    return report.items().stream().collect(Collectors.toMap(ReportItem::name, Function.identity()));
  }

  @Test
  void should_warn_on_basic_auth_with_non_ascii_credentials_and_point_at_the_form()
      throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    String yaml =
        """
        server:
          baseUrl: http://localhost:8081/jasperserver-pro
          webappName: jasperserver-pro
          installDir: %s
          runAsUser: jasperserver
          auth:
            mode: basic
            username: jasperädmin
            passwordRef: env:JRS_PASSWORD
        service:
          kind: systemd
          name: jasperreports
        network:
          mode: public
        """
            .formatted(install.toString().replace("\\", "/"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home")).yaml(yaml)) {
      DoctorReport report = new DoctorOperation(fake.build()).run(DoctorOptions.DEFAULT);
      Map<String, ReportItem> items = byName(report);
      assertThat(items.get("auth").status()).isEqualTo(Status.WARN);
      assertThat(items.get("auth").detail()).contains("non-ASCII").contains("p.24");
      assertThat(items.get("auth").remediation()).contains("server.auth.mode form");
      assertThat(items.keySet()).containsExactlyInAnyOrderElementsOf(DoctorOperation.CHECKS);
      assertThat(items.get(LocalChecks.PID_FILE).status()).isEqualTo(Status.PASS);
      assertThat(items.get(LocalChecks.DATABASE_SERVICE).status()).isEqualTo(Status.PASS);
      assertThat(items.get(LocalChecks.TELEMETRY).status()).isEqualTo(Status.SKIP);
      assertThat(items.get(LocalChecks.AUDIT).status()).isEqualTo(Status.SKIP);
    }
  }
}
