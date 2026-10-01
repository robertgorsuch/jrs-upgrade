package com.jaspersoft.jrsupgrade.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #147: doctor sees a running Tomcat under the installation whatever service.kind says. */
class RunningTomcatsTest {

  @TempDir Path install;

  private static TomcatProcessFinder.TomcatProcess opaque(long pid, Set<Integer> ports) {
    return new TomcatProcessFinder.TomcatProcess(
        pid, "", Optional.empty(), Optional.empty(), Optional.empty(), ports);
  }

  @Test
  void should_list_the_pid_when_a_tomcat_under_the_directory_runs() {
    TomcatProcessFinder.TomcatProcess elsewhere =
        FakeTomcatProcessFinder.tomcatUnder(install.resolve("other-install"));
    TomcatProcessFinder.TomcatProcess ours = FakeTomcatProcessFinder.tomcatUnder(install);
    elsewhere =
        new TomcatProcessFinder.TomcatProcess(
            7,
            elsewhere.commandLine(),
            elsewhere.catalinaHome(),
            elsewhere.catalinaBase(),
            Optional.empty());

    RunningTomcats result =
        RunningTomcats.scan(
            new FakeTomcatProcessFinder(List.of(List.of(elsewhere, ours))),
            install.resolve("apache-tomcat"));

    assertThat(result).isEqualTo(new RunningTomcats.Scanned(List.of(4242L), List.of()));
  }

  @Test
  void should_count_unreadable_jvms_when_the_ports_are_unknown() {
    RunningTomcats result =
        RunningTomcats.scan(
            new FakeTomcatProcessFinder(List.of(List.of(opaque(9, Set.of())))), install);

    assertThat(result).isEqualTo(new RunningTomcats.Scanned(List.of(), List.of(9L)));
  }

  @Test
  void should_report_nothing_when_no_tomcat_runs() {
    RunningTomcats result =
        RunningTomcats.scan(new FakeTomcatProcessFinder(List.of(List.of())), install);

    assertThat(result).isEqualTo(new RunningTomcats.Scanned(List.of(), List.of()));
  }

  @Test
  void should_be_unavailable_when_the_scan_fails() {
    TomcatProcessFinder failing =
        () -> {
          throw new TomcatScanException("tasklist exited 1");
        };

    RunningTomcats result = RunningTomcats.scan(failing, install);

    assertThat(result)
        .isInstanceOfSatisfying(
            RunningTomcats.Unavailable.class,
            u -> assertThat(u.reason()).contains("tasklist exited 1"));
  }

  @Test
  void should_scan_through_the_platform_when_asked_for_a_directory() {
    Platform windows =
        new WindowsPlatform(
            Platform.Arch.X86_64,
            new FakeProcessRunner(),
            new DefaultFileOps(),
            OperatorPrompt.nonInteractive(),
            new FakeTomcatProcessFinder(
                List.of(List.of(FakeTomcatProcessFinder.tomcatUnder(install)))));

    assertThat(windows.runningTomcats(install))
        .isEqualTo(new RunningTomcats.Scanned(List.of(4242L), List.of()));
  }
}
