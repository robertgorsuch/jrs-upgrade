package com.jaspersoft.jrsupgrade.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.platform.Platform.OsFamily;
import com.jaspersoft.jrsupgrade.core.platform.ServiceConfig;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ServiceSwitchTest {

  private static final Path OLD = Path.of("/opt/jrs/apache-tomcat");
  private static final Path NEW = Path.of("/opt/tomcat-11");

  @Test
  void should_give_the_service_bat_remove_and_install_steps_for_a_windows_service() {
    String steps =
        ServiceSwitch.reregistration(
            ServiceConfig.Kind.WINDOWS_SERVICE,
            Optional.of("jasperreportsTomcat"),
            Optional.empty(),
            Path.of("C:/jrs/apache-tomcat"),
            Path.of("C:/tomcat-11"),
            OsFamily.WINDOWS);

    assertThat(steps)
        .contains("elevated")
        .contains("C:\\jrs\\apache-tomcat\\bin\\service.bat remove jasperreportsTomcat")
        .contains("C:\\tomcat-11\\bin\\service.bat install jasperreportsTomcat")
        .contains("//ES//jasperreportsTomcat");
  }

  @Test
  void should_give_the_unit_edit_and_daemon_reload_for_systemd() {
    String steps =
        ServiceSwitch.reregistration(
            ServiceConfig.Kind.SYSTEMD,
            Optional.of("jasperreports"),
            Optional.empty(),
            OLD,
            NEW,
            OsFamily.LINUX);

    assertThat(steps)
        .contains("systemctl cat jasperreports")
        .contains("CATALINA_HOME")
        .contains(NEW.toString())
        .contains("systemctl daemon-reload")
        .contains("systemctl restart jasperreports");
  }

  @Test
  void should_point_the_catalina_kind_at_the_new_script_through_config_set() {
    String steps =
        ServiceSwitch.reregistration(
            ServiceConfig.Kind.CATALINA,
            Optional.empty(),
            Optional.of(OLD.resolve("bin").resolve("catalina.sh")),
            OLD,
            NEW,
            OsFamily.LINUX);

    assertThat(steps)
        .contains(
            "jrs-upgrade config set service.scriptPath "
                + NEW.resolve("bin").resolve("catalina.sh"));
  }

  @Test
  void should_explain_that_ctlscript_starts_the_bundled_tomcat_only() {
    String steps =
        ServiceSwitch.reregistration(
            ServiceConfig.Kind.CTLSCRIPT,
            Optional.empty(),
            Optional.of(Path.of("/opt/jrs/ctlscript.sh")),
            OLD,
            NEW,
            OsFamily.LINUX);

    assertThat(steps)
        .contains(Path.of("/opt/jrs/ctlscript.sh").toString())
        .contains("service.kind catalina")
        .contains(NEW.resolve("bin").resolve("catalina.sh").toString());
  }

  @Test
  void should_give_this_operating_systems_registered_kind_with_a_placeholder_when_manual() {
    String linux =
        ServiceSwitch.reregistration(
            ServiceConfig.Kind.MANUAL,
            Optional.empty(),
            Optional.empty(),
            OLD,
            NEW,
            OsFamily.LINUX);
    String windows =
        ServiceSwitch.reregistration(
            ServiceConfig.Kind.MANUAL,
            Optional.empty(),
            Optional.empty(),
            Path.of("C:/jrs/apache-tomcat"),
            Path.of("C:/tomcat-11"),
            OsFamily.WINDOWS);

    assertThat(linux)
        .contains("startup.sh")
        .contains("systemctl daemon-reload")
        .contains("systemctl restart <name>")
        .doesNotContain("service.bat");
    assertThat(windows)
        .contains("C:\\tomcat-11\\bin\\startup.bat")
        .contains("C:\\tomcat-11\\bin\\service.bat install <name>")
        .doesNotContain("systemctl");
  }

  @Test
  void should_use_the_placeholder_when_no_service_name_is_configured() {
    String steps =
        ServiceSwitch.reregistration(
            ServiceConfig.Kind.SYSTEMD,
            Optional.empty(),
            Optional.empty(),
            OLD,
            NEW,
            OsFamily.LINUX);

    assertThat(steps).contains("systemctl restart <name>");
  }
}
