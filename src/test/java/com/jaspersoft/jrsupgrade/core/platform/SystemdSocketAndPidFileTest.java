package com.jaspersoft.jrsupgrade.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.platform.FakeProcessRunner.Response;
import com.jaspersoft.jrsupgrade.core.platform.ServiceController.State;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #113: a socket-activated Tomcat (AWS guide p.30) is driven through its socket and its
 * service together, and a stale {@code catalina.pid} (installation guide p.237) is removed before
 * the start script runs.
 */
class SystemdSocketAndPidFileTest {

  private static final Duration POLL = Duration.ofMillis(10);
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final List<String> IS_ACTIVE = List.of("systemctl", "is-active", "tomcat.service");
  private static final List<String> STOP =
      List.of("systemctl", "stop", "tomcat.socket", "tomcat.service");
  private static final List<String> START =
      List.of("systemctl", "start", "tomcat.socket", "tomcat.service");

  @Test
  void should_stop_the_socket_and_its_service_together_when_the_unit_is_a_socket() {
    FakeProcessRunner runner =
        new FakeProcessRunner()
            .on(
                IS_ACTIVE,
                Response.ok("active"),
                Response.ok("active"),
                Response.failing(3, "inactive"))
            .on(STOP, Response.ok());
    SystemdServiceController controller =
        new SystemdServiceController(runner, "tomcat.socket", POLL);

    assertThat(controller.state()).isEqualTo(State.RUNNING);
    assertThat(controller.stop(TIMEOUT)).isEqualTo(State.STOPPED);
    assertThat(runner.countOf(STOP)).isEqualTo(1);
    assertThat(runner.countOf(List.of("systemctl", "stop", "tomcat.socket"))).isEqualTo(0);
    assertThat(controller.describe())
        .isEqualTo("systemd socket unit tomcat.socket with tomcat.service");
  }

  @Test
  void should_start_the_socket_and_its_service_together_when_the_unit_is_a_socket() {
    FakeProcessRunner runner =
        new FakeProcessRunner()
            .on(IS_ACTIVE, Response.failing(3, "inactive"), Response.ok("active"))
            .on(START, Response.ok());
    SystemdServiceController controller =
        new SystemdServiceController(runner, "tomcat.socket", POLL);

    assertThat(controller.start(TIMEOUT)).isEqualTo(State.RUNNING);
    assertThat(runner.countOf(START)).isEqualTo(1);
  }

  @Test
  void should_stop_a_listening_socket_even_when_its_service_is_already_inactive() {
    FakeProcessRunner runner =
        new FakeProcessRunner()
            .on(IS_ACTIVE, Response.failing(3, "inactive"))
            .on(STOP, Response.ok());
    SystemdServiceController controller =
        new SystemdServiceController(runner, "tomcat.socket", POLL);

    assertThat(controller.stop(TIMEOUT)).isEqualTo(State.STOPPED);
    assertThat(runner.countOf(STOP)).isEqualTo(1);
  }

  @Test
  void should_leave_a_plain_unit_as_it_was() {
    assertThat(SystemdServiceController.serviceUnitOf("jasperreports")).isEqualTo("jasperreports");
    assertThat(SystemdServiceController.serviceUnitOf("tomcat.socket")).isEqualTo("tomcat.service");
    FakeProcessRunner runner =
        new FakeProcessRunner()
            .on(List.of("systemctl", "is-active", "jasperreports"), Response.ok("active"));
    assertThat(new SystemdServiceController(runner, "jasperreports", POLL).describe())
        .isEqualTo("systemd unit jasperreports");
  }

  @Test
  void should_remove_a_stale_pid_file_before_the_start_script_runs(@TempDir Path install)
      throws Exception {
    Path tomcat = install.resolve("apache-tomcat");
    Path script = tomcat.resolve("bin").resolve("catalina.sh");
    Path pidFile = Files.createDirectories(tomcat.resolve("temp")).resolve("catalina.pid");
    Files.writeString(pidFile, "4242", StandardCharsets.UTF_8);
    List<TomcatProcessFinder.TomcatProcess> running =
        List.of(FakeTomcatProcessFinder.tomcatUnder(install));
    FakeTomcatProcessFinder finder =
        new FakeTomcatProcessFinder(List.of(List.of(), List.of(), running));
    FakeProcessRunner runner = new FakeProcessRunner();
    List<String> startCommand = List.of(script.toAbsolutePath().normalize().toString(), "start");
    runner.on(startCommand, Response.ok());
    ScriptServiceController controller =
        new ScriptServiceController(
            runner,
            ServiceConfig.Kind.CATALINA,
            script,
            finder,
            POLL,
            Optional.empty(),
            ScriptServiceController.ProcessTerminator.FORCIBLY,
            pid -> false);

    assertThat(controller.start(TIMEOUT)).isEqualTo(State.RUNNING);
    assertThat(pidFile).doesNotExist();
    assertThat(runner.invocations()).containsExactly(startCommand);
  }

  @Test
  void should_keep_a_pid_file_that_names_a_live_process(@TempDir Path install) throws Exception {
    Path script = install.resolve("ctlscript.sh");
    Path tomcat = install.resolve("apache-tomcat");
    Path pidFile = Files.createDirectories(tomcat.resolve("temp")).resolve("catalina.pid");
    Files.writeString(pidFile, "4242", StandardCharsets.UTF_8);
    List<TomcatProcessFinder.TomcatProcess> running =
        List.of(FakeTomcatProcessFinder.tomcatUnder(install));
    FakeTomcatProcessFinder finder =
        new FakeTomcatProcessFinder(List.of(List.of(), List.of(), running));
    FakeProcessRunner runner = new FakeProcessRunner();
    List<String> startCommand =
        List.of(script.toAbsolutePath().normalize().toString(), "start", "tomcat");
    runner.on(startCommand, Response.ok());
    ScriptServiceController controller =
        new ScriptServiceController(
            runner,
            ServiceConfig.Kind.CTLSCRIPT,
            script,
            finder,
            POLL,
            Optional.empty(),
            ScriptServiceController.ProcessTerminator.FORCIBLY,
            pid -> pid == 4242L);

    assertThat(controller.tomcatDirs())
        .containsExactly(
            install.toAbsolutePath().normalize().resolve("apache-tomcat"),
            install.toAbsolutePath().normalize().resolve("tomcat"));
    assertThat(controller.start(TIMEOUT)).isEqualTo(State.RUNNING);
    assertThat(pidFile).exists();
  }
}
