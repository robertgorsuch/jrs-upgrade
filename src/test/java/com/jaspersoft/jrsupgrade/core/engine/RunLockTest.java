package com.jaspersoft.jrsupgrade.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunLockTest {

  private static final Instant NOW = Instant.parse("2026-09-08T10:15:00Z");

  @TempDir Path tmp;

  @Test
  void should_throw_lock_held_with_first_run_id_when_second_lock_is_taken_in_same_jvm() {
    JrsUpgradeHome home = new JrsUpgradeHome(tmp);
    try (RunLock first = new RunLock(home, "r-first", NOW)) {
      assertThat(first.runId()).isEqualTo("r-first");
      assertThatThrownBy(() -> new RunLock(home, "r-second", NOW))
          .isInstanceOf(LockHeldException.class)
          .satisfies(
              e -> {
                LockHeldException lhe = (LockHeldException) e;
                assertThat(lhe.holderRunId()).isEqualTo("r-first");
                assertThat(lhe.holderPid())
                    .isEqualTo(String.valueOf(ProcessHandle.current().pid()));
              })
          .hasMessageContaining("r-first");
    }
  }

  @Test
  void should_answer_held_by_from_the_registry_when_this_jvm_holds_the_lock() throws Exception {
    JrsUpgradeHome home = new JrsUpgradeHome(tmp);
    try (RunLock unusedLock = new RunLock(home, "r-mine", NOW)) {
      assertThat(RunLock.heldBy(home.runLock())).map(RunLock.Holder::runId).contains("r-mine");
      assertThat(RunLock.readHolder(home.runLock()))
          .map(RunLock.Holder::pid)
          .contains(String.valueOf(ProcessHandle.current().pid()));
      assertThatThrownBy(() -> new RunLock(home, "r-again", NOW))
          .isInstanceOf(LockHeldException.class)
          .hasMessageContaining("r-mine");
    }
    assertThat(RunLock.heldBy(home.runLock())).isEmpty();
  }

  /**
   * On Linux the run lock is a POSIX record lock, which the kernel drops when any descriptor of the
   * holding process on that file is closed; a probe that read the lock file through a second handle
   * therefore released the live run's lock (assessment item E1). The child takes the lock and
   * probes it the way doctor and the console do; this process then checks from outside that the OS
   * lock is still there. Same-JVM assertions cannot see this, because the JDK's own lock table
   * still reports the lock as held after the kernel let it go.
   */
  @Test
  void should_keep_the_os_lock_when_the_holder_probes_its_own_lock_file() throws Exception {
    JrsUpgradeHome home = new JrsUpgradeHome(tmp);
    String java = ProcessHandle.current().info().command().orElseThrow();
    Process child =
        new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                RunLockChildHolder.class.getName(),
                tmp.toString())
            .redirectErrorStream(true)
            .start();
    try (BufferedReader out =
        new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
      String first = out.readLine();
      assertThat(first).as("the child took the lock").isEqualTo("ready r-child");
      try (FileChannel ch =
          FileChannel.open(home.runLock(), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
        FileLock probe = ch.tryLock(RunLock.LOCK_POSITION, 1, false);
        if (probe != null) {
          probe.release();
        }
        assertThat(probe)
            .as("the child still holds the OS lock after reading its own lock file")
            .isNull();
      }
      assertThat(RunLock.heldBy(home.runLock())).map(RunLock.Holder::runId).contains("r-child");
    } finally {
      child.getOutputStream().close();
      if (!child.waitFor(15, TimeUnit.SECONDS)) {
        child.destroyForcibly();
      }
    }
    assertThat(RunLock.heldBy(home.runLock())).as("released when the child exited").isEmpty();
  }

  @Test
  void should_write_holder_info_while_held_and_truncate_when_released() throws Exception {
    JrsUpgradeHome home = new JrsUpgradeHome(tmp);
    try (RunLock unusedLock = new RunLock(home, "r-abc", NOW)) {
      String text = Files.readString(home.runLock(), StandardCharsets.UTF_8);
      assertThat(text).isEqualTo("r-abc " + ProcessHandle.current().pid() + " " + NOW);
      assertThat(RunLock.readHolder(home.runLock()))
          .contains(
              new RunLock.Holder(
                  "r-abc", String.valueOf(ProcessHandle.current().pid()), NOW.toString()));
    }
    assertThat(Files.size(home.runLock())).isZero();
    assertThat(RunLock.readHolder(home.runLock())).isEmpty();
  }

  /**
   * Review finding 1.18: {@code close()} runs after the run's outcome is journaled, so it must
   * never throw; the second close finds a closed channel and must be a no-op.
   */
  @Test
  void should_not_throw_when_closed_twice() {
    JrsUpgradeHome home = new JrsUpgradeHome(tmp);
    RunLock lock = new RunLock(home, "r-twice", NOW);

    lock.close();
    lock.close();

    assertThat(RunLock.readHolder(home.runLock())).isEmpty();
  }

  @Test
  void should_allow_reacquiring_when_previous_holder_closed() {
    JrsUpgradeHome home = new JrsUpgradeHome(tmp);
    new RunLock(home, "r-1", NOW).close();
    try (RunLock second = new RunLock(home, "r-2", NOW)) {
      assertThat(second.runId()).isEqualTo("r-2");
    }
  }

  @Test
  void should_create_missing_home_directory_when_acquiring() {
    JrsUpgradeHome home = new JrsUpgradeHome(tmp.resolve("nested").resolve("home"));
    try (RunLock lock = new RunLock(home, "r-1", NOW)) {
      assertThat(Files.exists(lock.file())).isTrue();
    }
  }
}
