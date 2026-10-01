package com.jaspersoft.jrsupgrade.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.engine.CancellationToken;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Review finding 4.3: Ctrl-C must end the process with the run's exit code (5 when cancelled) after
 * the outcome block has been written, instead of the JVM's 130/143 with the output cut mid-line.
 * The guard is what the shutdown hook runs; these tests drive it directly.
 */
class RunGuardTest {

  @Test
  void should_halt_with_the_rendered_exit_code_once_the_worker_stopped_and_output_was_rendered()
      throws Exception {
    CancellationToken cancel = new CancellationToken();
    Thread worker =
        new Thread(
            () -> {
              while (!cancel.isCancelled()) {
                sleep(10);
              }
            },
            "worker");
    worker.start();
    List<Integer> halted = new CopyOnWriteArrayList<>();
    CountDownLatch renderedAt = new CountDownLatch(1);
    RunGuard guard =
        new RunGuard(cancel, worker, Duration.ofSeconds(5), Duration.ofSeconds(5), halted::add);
    Thread main =
        new Thread(
            () -> {
              try {
                worker.join();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              // the main thread renders the outcome block, then reports its exit code
              guard.rendered(ExitCodes.CANCELLED);
              renderedAt.countDown();
            },
            "main");
    main.start();

    guard.onShutdown();

    assertThat(cancel.isCancelled()).isTrue();
    assertThat(renderedAt.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(guard.wasRendered()).as("halt waited for the outcome to be rendered").isTrue();
    assertThat(halted).containsExactly(ExitCodes.CANCELLED);
  }

  @Test
  void should_halt_with_cancelled_when_the_worker_ignores_the_cancel_and_nothing_is_rendered()
      throws Exception {
    CancellationToken cancel = new CancellationToken();
    CountDownLatch release = new CountDownLatch(1);
    Thread worker =
        new Thread(
            () -> {
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            },
            "stuck-worker");
    worker.setDaemon(true);
    worker.start();
    List<Integer> halted = new CopyOnWriteArrayList<>();
    RunGuard guard =
        new RunGuard(cancel, worker, Duration.ofMillis(200), Duration.ofMillis(200), halted::add);

    long start = System.nanoTime();
    guard.onShutdown();
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

    assertThat(halted).containsExactly(ExitCodes.CANCELLED);
    assertThat(guard.wasRendered()).isFalse();
    assertThat(elapsedMillis).as("bounded by the two grace periods").isLessThan(3_000);
    release.countDown();
  }

  @Test
  void should_halt_with_the_outcome_code_when_the_run_finished_with_a_rollback() {
    CancellationToken cancel = new CancellationToken();
    Thread worker = new Thread(() -> {}, "done-worker");
    worker.start();
    List<Integer> halted = new CopyOnWriteArrayList<>();
    RunGuard guard =
        new RunGuard(cancel, worker, Duration.ofSeconds(1), Duration.ofSeconds(1), halted::add);
    guard.rendered(ExitCodes.FAILED_ROLLED_BACK);

    guard.onShutdown();

    assertThat(halted).containsExactly(ExitCodes.FAILED_ROLLED_BACK);
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
