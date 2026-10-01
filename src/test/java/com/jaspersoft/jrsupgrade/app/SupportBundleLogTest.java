package com.jaspersoft.jrsupgrade.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.engine.RunRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #160: the bundle's log is the run's own lines, picked by the {@code runId} the runner puts
 * on every line it writes, or by the run's time window for lines written without one (another
 * thread, or a build before #160), instead of the last 2,000 lines of whatever the log held.
 */
class SupportBundleLogTest {

  @TempDir Path tmp;

  private static final RunRecord RUN =
      new RunRecord(
          "r-1",
          "hotfix.apply",
          Optional.empty(),
          Instant.parse("2026-09-25T16:19:00Z"),
          Optional.of(Instant.parse("2026-09-25T16:24:00Z")),
          Optional.empty(),
          Optional.of(0));

  @Test
  void should_keep_a_line_when_it_carries_the_run_id_or_falls_inside_the_runs_window()
      throws IOException {
    Path log = tmp.resolve("jrs-upgrade.log");
    Files.write(
        log,
        List.of(
            line("2026-09-11T10:00:00-07:00", null, "old test noise"),
            line("2026-09-25T09:19:30-07:00", "r-1", "step s1 RUNNING"),
            line("2026-09-25T09:20:00-07:00", "r-0", "another run"),
            line("2026-09-25T09:22:21-07:00", null, "could not restore owner"),
            "not json at all",
            line("2026-09-25T09:30:00-07:00", null, "after the run")),
        StandardCharsets.UTF_8);

    List<String> kept = List.copyOf(SupportBundle.runLines(log, RUN, 100));

    assertThat(kept).hasSize(2);
    assertThat(kept.get(0)).contains("step s1 RUNNING");
    assertThat(kept.get(1)).contains("could not restore owner");
  }

  @Test
  void should_cap_and_say_so_when_the_run_has_more_lines_than_the_limit() throws IOException {
    Path log = tmp.resolve("jrs-upgrade.log");
    Files.write(
        log,
        List.of(
            line("2026-09-25T16:20:00Z", "r-1", "one"),
            line("2026-09-25T16:20:01Z", "r-1", "two"),
            line("2026-09-25T16:20:02Z", "r-1", "three")),
        StandardCharsets.UTF_8);

    List<String> kept = List.copyOf(SupportBundle.runLines(log, RUN, 2));

    assertThat(kept).hasSize(3);
    assertThat(kept.get(0)).contains("earlier lines of r-1 omitted");
    assertThat(kept.get(2)).contains("three");
  }

  private static String line(String ts, String runId, String message) {
    return "{\"ts\":\""
        + ts
        + "\",\"level\":\"INFO\",\"logger\":\"x\",\"thread\":\"main\""
        + (runId == null ? "" : ",\"runId\":\"" + runId + "\"")
        + ",\"message\":\""
        + message
        + "\"}";
  }
}
