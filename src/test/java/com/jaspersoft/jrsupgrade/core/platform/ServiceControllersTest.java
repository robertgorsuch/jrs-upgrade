package com.jaspersoft.jrsupgrade.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.platform.FakeProcessRunner.Response;
import com.jaspersoft.jrsupgrade.core.platform.ServiceController.State;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceControllersTest {

  private static final Duration POLL = Duration.ofMillis(10);
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final String SERVICE = "jasperreportsTomcat";
  private static final List<String> SC_QUERY = List.of("sc.exe", "query", SERVICE);
  private static final List<String> SC_STOP = List.of("sc.exe", "stop", SERVICE);
  private static final List<String> SC_START = List.of("sc.exe", "start", SERVICE);

  private static Response scState(String state) {
    return Response.ok(
        "SERVICE_NAME: " + SERVICE,
        "        TYPE               : 10  WIN32_OWN_PROCESS",
        "        STATE              : 3  " + state,
        "                                (STOPPABLE, NOT_PAUSABLE, ACCEPTS_SHUTDOWN)");
  }

  @Test
  void should_fail_fast_when_windows_refuses_the_stop_command() {
    FakeProcessRunner runner =
        new FakeProcessRunner()
            .on(SC_QUERY, scState("RUNNING"))
            .on(SC_STOP, Response.failing(5, "[SC] OpenService FAILED 5:", "Access is denied."));
    WindowsServiceController controller = new WindowsServiceController(runner, SERVICE, POLL);

    long started = System.nanoTime();
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.stop(TIMEOUT))
        .as("a refused command must not be waited out as if the service were slow")
        .isInstanceOf(ServiceControlException.class)
        .hasMessageContaining("sc.exe stop")
        .hasMessageContaining("Access is denied")
        .hasMessageContaining("administrator");
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
  }

  @Test
  void should_fail_fast_when_systemd_refuses_the_start_command() {
    List<String> isActive = List.of("systemctl", "is-active", "jasperreports");
    List<String> start = List.of("systemctl", "start", "jasperreports");
    FakeProcessRunner runner =
        new FakeProcessRunner()
            .on(isActive, Response.failing(3, "inactive"))
            .on(
                start,
                Response.failing(
                    4,
                    "Failed to start jasperreports.service: Access denied",
                    "See system logs and 'systemctl status jasperreports.service' for details."));
    SystemdServiceController controller =
        new SystemdServiceController(runner, "jasperreports", POLL);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.start(TIMEOUT))
        .isInstanceOf(ServiceControlException.class)
        .hasMessageContaining("systemctl start")
        .hasMessageContaining("Access denied")
        .hasMessageContaining("root");
  }

  /**
   * A unit with KillMode=none reports inactive while the JVM is still going down; the controller
   * says stopping until the Tomcat process is gone (assessment item H5).
   */
  @Test
  void should_report_stopping_while_the_tomcat_jvm_outlives_an_inactive_systemd_unit(
      @TempDir Path install) {
    List<TomcatProcessFinder.TomcatProcess> running =
        List.of(FakeTomcatProcessFinder.tomcatUnder(install));
    FakeTomcatProcessFinder finder = new FakeTomcatProcessFinder(List.of(running, List.of()));
    FakeProcessRunner runner =
        new FakeProcessRunner()
            .on(List.of("systemctl", "is-active", "jasperserver"), Response.failing(3, "inactive"));
    SystemdServiceController controller =
        new SystemdServiceController(runner, "jasperserver", finder, Optional.of(install), POLL);

    assertThat(controller.state()).isEqualTo(State.STOPPING);
    assertThat(controller.state()).isEqualTo(State.STOPPED);
  }

  /** The script kinds fail fast like sc.exe and systemctl do (review 1.12; assessment item P3). */
  @Test
  void should_fail_fast_when_the_stop_script_cannot_run(@TempDir Path install) {
    Path script = install.resolve("ctlscript.sh");
    List<TomcatProcessFinder.TomcatProcess> running =
        List.of(FakeTomcatProcessFinder.tomcatUnder(install));
    FakeTomcatProcessFinder finder =
        new FakeTomcatProcessFinder(List.of(running, running, running, running, running));
    FakeProcessRunner runner =
        new FakeProcessRunner()
            .on(
                List.of(script.toString(), "stop", "tomcat"),
                Response.failing(126, "ctlscript.sh: Permission denied"));
    ScriptServiceController controller =
        new ScriptServiceController(runner, ServiceConfig.Kind.CTLSCRIPT, script, finder, POLL);

    long started = System.nanoTime();
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.stop(TIMEOUT))
        .isInstanceOf(ServiceControlException.class)
        .hasMessageContaining("Permission denied")
        .hasMessageContaining("executable");
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
  }

  @Test
  void should_stop_waiting_when_cancelled_while_a_service_stops() {
    FakeProcessRunner runner =
        new FakeProcessRunner()
            .on(SC_QUERY, scState("RUNNING"), scState("STOP_PENDING"))
            .on(SC_STOP, Response.ok());
    WindowsServiceController controller = new WindowsServiceController(runner, SERVICE, POLL);

    long started = System.nanoTime();
    State result = controller.stop(Duration.ofSeconds(30), () -> true);

    assertThat(result).as("the last observed state, not an assumption").isEqualTo(State.STOPPING);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
  }

  @Test
  void should_return_stopped_when_windows_service_passes_through_stop_pending() {
    FakeProcessRunner runner =
        new FakeProcessRunner()
            .on(
                SC_QUERY,
                scState("RUNNING"),
                scState("STOP_PENDING"),
                scState("STOP_PENDING"),
                scState("STOPPED"))
            .on(SC_STOP, Response.ok("[SC] ControlService SUCCESS"));
    WindowsServiceController controller = new WindowsServiceController(runner, SERVICE, POLL);

    State result = controller.stop(TIMEOUT);

    assertThat(result).isEqualTo(State.STOPPED);
    assertThat(runner.countOf(SC_STOP)).isEqualTo(1);
    assertThat(runner.countOf(SC_QUERY)).isGreaterThanOrEqualTo(4);
    assertThat(controller.describe()).isEqualTo("Windows service " + SERVICE);
  }

  @Test
  void should_return_last_state_when_windows_service_never_finishes_stopping() {
    FakeProcessRunner runner =
        new FakeProcessRunner()
            .on(SC_QUERY, scState("RUNNING"), scState("STOP_PENDING"))
            .on(SC_STOP, Response.ok());
    WindowsServiceController controller = new WindowsServiceController(runner, SERVICE, POLL);

    assertThat(controller.stop(Duration.ofMillis(80))).isEqualTo(State.STOPPING);
  }

  @Test
  void should_return_running_when_windows_service_passes_through_start_pending() {
    FakeProcessRunner runner =
        new FakeProcessRunner()
            .on(SC_QUERY, scState("STOPPED"), scState("START_PENDING"), scState("RUNNING"))
            .on(SC_START, Response.ok());
    WindowsServiceController controller = new WindowsServiceController(runner, SERVICE, POLL);

    assertThat(controller.start(TIMEOUT)).isEqualTo(State.RUNNING);
    assertThat(runner.countOf(SC_START)).isEqualTo(1);
  }

  @Test
  void should_report_unknown_when_windows_service_does_not_exist() {
    FakeProcessRunner runner =
        new FakeProcessRunner()
            .on(
                SC_QUERY,
                Response.failing(1060, "[SC] EnumQueryServicesStatus:OpenService FAILED"));

    assertThat(new WindowsServiceController(runner, SERVICE, POLL).state())
        .isEqualTo(State.UNKNOWN);
  }

  @Test
  void should_return_stopped_when_systemd_unit_passes_through_deactivating() {
    List<String> isActive = List.of("systemctl", "is-active", "jasperserver");
    FakeProcessRunner runner =
        new FakeProcessRunner()
            .on(
                isActive,
                Response.ok("active"),
                Response.failing(3, "deactivating"),
                Response.failing(3, "inactive"))
            .on(List.of("systemctl", "stop", "jasperserver"), Response.ok());
    SystemdServiceController controller =
        new SystemdServiceController(runner, "jasperserver", POLL);

    assertThat(controller.stop(TIMEOUT)).isEqualTo(State.STOPPED);
    assertThat(runner.countOf(List.of("systemctl", "stop", "jasperserver"))).isEqualTo(1);
    assertThat(controller.describe()).isEqualTo("systemd unit jasperserver");
  }

  @Test
  void should_return_running_when_systemd_unit_passes_through_activating() {
    List<String> isActive = List.of("systemctl", "is-active", "jasperserver");
    FakeProcessRunner runner =
        new FakeProcessRunner()
            .on(
                isActive,
                Response.failing(3, "inactive"),
                Response.failing(3, "activating"),
                Response.ok("active"))
            .on(List.of("systemctl", "start", "jasperserver"), Response.ok());

    assertThat(new SystemdServiceController(runner, "jasperserver", POLL).start(TIMEOUT))
        .isEqualTo(State.RUNNING);
  }

  @Test
  void should_stop_via_ctlscript_when_tomcat_process_disappears(@TempDir Path install) {
    Path script = install.resolve("ctlscript.sh");
    List<TomcatProcessFinder.TomcatProcess> running =
        List.of(FakeTomcatProcessFinder.tomcatUnder(install));
    FakeTomcatProcessFinder finder =
        new FakeTomcatProcessFinder(List.of(running, running, List.of()));
    FakeProcessRunner runner = new FakeProcessRunner();
    List<String> stopCommand =
        List.of(script.toAbsolutePath().normalize().toString(), "stop", "tomcat");
    runner.on(stopCommand, Response.ok("Stopped tomcat"));
    ScriptServiceController controller =
        new ScriptServiceController(runner, ServiceConfig.Kind.CTLSCRIPT, script, finder, POLL);

    assertThat(controller.state()).isEqualTo(State.RUNNING);
    assertThat(controller.stop(TIMEOUT)).isEqualTo(State.STOPPED);
    assertThat(runner.invocations()).containsExactly(stopCommand);
    assertThat(controller.watchedDir()).isEqualTo(install.toAbsolutePath().normalize());
    assertThat(controller.describe()).startsWith("ctlscript ");
  }

  @Test
  void should_start_via_catalina_script_when_tomcat_process_appears(@TempDir Path install) {
    Path script = install.resolve("apache-tomcat").resolve("bin").resolve("catalina.sh");
    List<TomcatProcessFinder.TomcatProcess> running =
        List.of(FakeTomcatProcessFinder.tomcatUnder(install));
    FakeTomcatProcessFinder finder =
        new FakeTomcatProcessFinder(List.of(List.of(), List.of(), running));
    FakeProcessRunner runner = new FakeProcessRunner();
    List<String> startCommand = List.of(script.toAbsolutePath().normalize().toString(), "start");
    runner.on(startCommand, Response.ok());
    ScriptServiceController controller =
        new ScriptServiceController(runner, ServiceConfig.Kind.CATALINA, script, finder, POLL);

    assertThat(controller.start(TIMEOUT)).isEqualTo(State.RUNNING);
    assertThat(runner.invocations()).containsExactly(startCommand);
    assertThat(controller.watchedDir())
        .isEqualTo(install.resolve("apache-tomcat").toAbsolutePath().normalize());
  }

  @Test
  void should_ignore_tomcats_of_other_installs_when_deriving_script_state(
      @TempDir Path install, @TempDir Path other) {
    FakeTomcatProcessFinder finder =
        new FakeTomcatProcessFinder(List.of(List.of(FakeTomcatProcessFinder.tomcatUnder(other))));
    ScriptServiceController controller =
        new ScriptServiceController(
            new FakeProcessRunner(),
            ServiceConfig.Kind.CTLSCRIPT,
            install.resolve("ctlscript.sh"),
            finder,
            POLL);

    assertThat(controller.state()).isEqualTo(State.STOPPED);
  }

  /**
   * Review finding 1.11, remainder: only the Windows and systemd controllers honoured the cancel
   * signal; the script and manual controllers fell back to the plain overload and sat out the whole
   * timeout after Ctrl-C.
   */
  @Test
  void should_stop_waiting_when_cancelled_while_a_scripted_stop_is_pending(@TempDir Path install) {
    Path script = install.resolve("ctlscript.sh");
    List<TomcatProcessFinder.TomcatProcess> running =
        List.of(FakeTomcatProcessFinder.tomcatUnder(install));
    FakeProcessRunner runner = new FakeProcessRunner();
    runner.on(
        List.of(script.toAbsolutePath().normalize().toString(), "stop", "tomcat"), Response.ok());
    ScriptServiceController controller =
        new ScriptServiceController(
            runner,
            ServiceConfig.Kind.CTLSCRIPT,
            script,
            new FakeTomcatProcessFinder(List.of(running)),
            POLL);

    long started = System.nanoTime();
    State result = controller.stop(Duration.ofSeconds(3), () -> true);

    assertThat(result).isEqualTo(State.RUNNING);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
  }

  @Test
  void should_stop_waiting_when_cancelled_while_the_operator_is_asked_to_start_tomcat(
      @TempDir Path install) {
    ManualServiceController controller =
        new ManualServiceController(
            new FakeProcessRunner(),
            recordingPrompt(new ArrayList<>(), true),
            Optional.of(install),
            new FakeTomcatProcessFinder(List.of(List.of())),
            POLL);

    long started = System.nanoTime();
    State result = controller.start(Duration.ofSeconds(3), () -> true);

    assertThat(result).isEqualTo(State.STOPPED);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
  }

  @Test
  void should_return_unknown_immediately_when_manual_kind_runs_non_interactively(
      @TempDir Path install) {
    List<TomcatProcessFinder.TomcatProcess> running =
        List.of(FakeTomcatProcessFinder.tomcatUnder(install));
    List<String> instructions = new ArrayList<>();
    OperatorPrompt prompt = recordingPrompt(instructions, false);
    ManualServiceController controller =
        new ManualServiceController(
            new FakeProcessRunner(),
            prompt,
            Optional.of(install),
            new FakeTomcatProcessFinder(List.of(running)),
            POLL);

    assertThat(controller.stop(TIMEOUT)).isEqualTo(State.UNKNOWN);
    assertThat(instructions).isEmpty();
  }

  @Test
  void should_instruct_operator_and_poll_when_manual_kind_is_interactive(@TempDir Path install) {
    List<TomcatProcessFinder.TomcatProcess> running =
        List.of(FakeTomcatProcessFinder.tomcatUnder(install));
    List<String> instructions = new ArrayList<>();
    ManualServiceController controller =
        new ManualServiceController(
            new FakeProcessRunner(),
            recordingPrompt(instructions, true),
            Optional.of(install),
            new FakeTomcatProcessFinder(List.of(running, running, running, List.of())),
            POLL);

    assertThat(controller.stop(TIMEOUT)).isEqualTo(State.STOPPED);
    assertThat(instructions).hasSize(1);
    assertThat(instructions.get(0)).contains("Stop").contains(install.toAbsolutePath().toString());
    assertThat(controller.describe()).startsWith("manual");
  }

  @Test
  void should_parse_sc_and_systemctl_states_when_given_raw_output() {
    assertThat(WindowsServiceController.parseState(List.of("STATE : 4 RUNNING")))
        .isEqualTo(State.RUNNING);
    assertThat(WindowsServiceController.parseState(List.of("  STATE   : 2  START_PENDING")))
        .isEqualTo(State.STARTING);
    assertThat(WindowsServiceController.parseState(List.of("nothing"))).isEqualTo(State.UNKNOWN);
    assertThat(SystemdServiceController.parseState(List.of("failed"))).isEqualTo(State.STOPPED);
    assertThat(SystemdServiceController.parseState(List.of("", "activating")))
        .isEqualTo(State.STARTING);
    assertThat(SystemdServiceController.parseState(List.of())).isEqualTo(State.UNKNOWN);
  }

  private static OperatorPrompt recordingPrompt(List<String> sink, boolean interactive) {
    return new OperatorPrompt() {
      @Override
      public void instruct(String message) {
        sink.add(message);
      }

      @Override
      public boolean interactive() {
        return interactive;
      }
    };
  }
}
