package com.jaspersoft.jrsupgrade.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.platform.FakeProcessRunner.Response;
import com.jaspersoft.jrsupgrade.core.platform.ServiceController.State;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #42: after {@code catalina stop} JasperReports Server's JVM can stay alive on non-daemon
 * threads, so every stop timed out. With {@code service.forceStopAfterSeconds} the controller ends
 * the watched Tomcat's JVM after the grace period, and nothing else (ADR-0016).
 */
class ScriptServiceControllerForceStopTest {

  private static final Duration POLL = Duration.ofMillis(10);
  private static final Duration GRACE = Duration.ofMillis(150);

  /** A process listing that keeps a JVM until it is ended, and records what was ended. */
  static final class LingeringTomcats implements TomcatProcessFinder {
    final List<TomcatProcess> alive = new CopyOnWriteArrayList<>();
    final List<Long> ended = new CopyOnWriteArrayList<>();

    LingeringTomcats(List<TomcatProcess> initial) {
      alive.addAll(initial);
    }

    @Override
    public List<TomcatProcess> find() {
      return List.copyOf(alive);
    }

    boolean end(long pid) {
      ended.add(pid);
      return alive.removeIf(p -> p.pid() == pid);
    }
  }

  private static Path catalinaUnder(Path install) {
    return install.resolve("apache-tomcat").resolve("bin").resolve("catalina.sh");
  }

  private static FakeProcessRunner stopScriptSucceeds(Path script) {
    return new FakeProcessRunner()
        .on(List.of(script.toAbsolutePath().normalize().toString(), "stop"), Response.ok());
  }

  @Test
  void should_end_the_watched_tomcat_jvm_when_it_outlives_the_grace_period(@TempDir Path install) {
    Path script = catalinaUnder(install);
    LingeringTomcats tomcats =
        new LingeringTomcats(List.of(FakeTomcatProcessFinder.tomcatUnder(install)));
    ScriptServiceController controller =
        new ScriptServiceController(
            stopScriptSucceeds(script),
            ServiceConfig.Kind.CATALINA,
            script,
            tomcats,
            POLL,
            Optional.of(GRACE),
            tomcats::end);

    long started = System.nanoTime();
    State result = controller.stop(Duration.ofSeconds(5), () -> false);

    assertThat(result).isEqualTo(State.STOPPED);
    assertThat(tomcats.ended).containsExactly(4242L);
    assertThat(Duration.ofNanos(System.nanoTime() - started))
        .as("the grace period is honoured before anything is ended")
        .isGreaterThanOrEqualTo(GRACE);
  }

  @Test
  void should_wait_out_the_timeout_and_end_nothing_when_force_stop_is_not_set(
      @TempDir Path install) {
    Path script = catalinaUnder(install);
    LingeringTomcats tomcats =
        new LingeringTomcats(List.of(FakeTomcatProcessFinder.tomcatUnder(install)));
    ScriptServiceController controller =
        new ScriptServiceController(
            stopScriptSucceeds(script),
            ServiceConfig.Kind.CATALINA,
            script,
            tomcats,
            POLL,
            Optional.empty(),
            tomcats::end);

    State result = controller.stop(Duration.ofMillis(300), () -> false);

    assertThat(result).isEqualTo(State.RUNNING);
    assertThat(tomcats.ended).isEmpty();
  }

  @Test
  void should_leave_other_tomcats_and_unreadable_jvms_alone_when_forcing_the_stop(
      @TempDir Path install, @TempDir Path other) {
    Path script = catalinaUnder(install);
    TomcatProcessFinder.TomcatProcess watched = FakeTomcatProcessFinder.tomcatUnder(install);
    TomcatProcessFinder.TomcatProcess elsewhere =
        new TomcatProcessFinder.TomcatProcess(
            5151,
            "java -Dcatalina.home=" + other + " org.apache.catalina.startup.Bootstrap start",
            Optional.of(other.toAbsolutePath().normalize()),
            Optional.of(other.toAbsolutePath().normalize()),
            Optional.empty());
    TomcatProcessFinder.TomcatProcess opaque =
        new TomcatProcessFinder.TomcatProcess(
            6161, "", Optional.empty(), Optional.empty(), Optional.empty());
    LingeringTomcats tomcats = new LingeringTomcats(List.of(watched, elsewhere, opaque));
    ScriptServiceController controller =
        new ScriptServiceController(
            stopScriptSucceeds(script),
            ServiceConfig.Kind.CATALINA,
            script,
            tomcats,
            POLL,
            Optional.of(GRACE),
            tomcats::end);

    State result = controller.stop(Duration.ofMillis(600), () -> false);

    assertThat(tomcats.ended).containsExactly(4242L);
    assertThat(result)
        .as("an unreadable JVM remains, so whether this Tomcat is gone cannot be told")
        .isEqualTo(State.UNKNOWN);
  }

  @Test
  void should_end_nothing_when_the_stop_is_cancelled_during_the_grace_period(
      @TempDir Path install) {
    Path script = catalinaUnder(install);
    LingeringTomcats tomcats =
        new LingeringTomcats(List.of(FakeTomcatProcessFinder.tomcatUnder(install)));
    ScriptServiceController controller =
        new ScriptServiceController(
            stopScriptSucceeds(script),
            ServiceConfig.Kind.CATALINA,
            script,
            tomcats,
            POLL,
            Optional.of(Duration.ofSeconds(2)),
            tomcats::end);

    long started = System.nanoTime();
    State result = controller.stop(Duration.ofSeconds(5), () -> true);

    assertThat(result).isEqualTo(State.RUNNING);
    assertThat(tomcats.ended).isEmpty();
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
  }

  /** A real JVM that ignores the stop script stands in for the lingering Tomcat. */
  @Test
  void should_end_a_real_lingering_jvm_when_force_stop_is_set(@TempDir Path install)
      throws Exception {
    Path script = catalinaUnder(install);
    Path home = install.resolve("apache-tomcat").toAbsolutePath().normalize();
    Process lingering =
        new ProcessBuilder(DefaultProcessRunnerTest.helperCommand("sleep"))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    try {
      TomcatProcessFinder finder =
          () ->
              lingering.isAlive()
                  ? List.of(
                      new TomcatProcessFinder.TomcatProcess(
                          lingering.pid(),
                          "java -Dcatalina.home=" + home + " org.apache.catalina.startup.Bootstrap",
                          Optional.of(home),
                          Optional.of(home),
                          Optional.empty()))
                  : List.of();
      ScriptServiceController controller =
          new ScriptServiceController(
              stopScriptSucceeds(script),
              ServiceConfig.Kind.CATALINA,
              script,
              finder,
              POLL,
              Optional.of(GRACE),
              ScriptServiceController.ProcessTerminator.FORCIBLY);

      State result = controller.stop(Duration.ofSeconds(10), () -> false);

      assertThat(result).isEqualTo(State.STOPPED);
      assertThat(lingering.waitFor(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      lingering.destroyForcibly();
    }
  }
}
