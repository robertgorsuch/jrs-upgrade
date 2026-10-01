package com.jaspersoft.jrsupgrade.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.engine.RunRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #161: the smaller defects a real 2.0.0 support bundle showed. */
class SupportBundleDetailsTest {

  @TempDir Path tmp;

  private static final Instant START = Instant.parse("2026-09-25T16:19:00Z");
  private static final RunRecord RUN =
      new RunRecord(
          "r-7",
          "hotfix.apply",
          Optional.of("plan-7"),
          START,
          Optional.of(START.plusSeconds(300)),
          Optional.empty(),
          Optional.of(0));

  @Test
  void should_fill_in_the_run_id_when_the_plan_left_a_placeholder() {
    String plan =
        "{\"backupLocations\":[\"C:\\\\ProgramData\\\\jrs-upgrade\\\\snapshots\\\\{runId}\\\\snapshot\"]}";

    assertThat(SupportBundle.withRunId(plan, "r-7"))
        .doesNotContain("{runId}")
        .contains("snapshots\\\\r-7\\\\snapshot");
  }

  @Test
  void should_leave_out_the_buildomatic_log_when_every_log_predates_the_run() throws IOException {
    Path logs = Files.createDirectories(tmp.resolve("buildomatic").resolve("logs"));
    Path old =
        log(logs, "js-export-pro_2026-09-17_07-48_36783.log", START.minusSeconds(8 * 86_400));

    assertThat(SupportBundle.forRun(buildomatic(old), RUN)).isEmpty();
  }

  /**
   * Review of #166: the newest buildomatic log may belong to a later run; the run's own log is the
   * newest one written while it ran, not the newest one in the directory.
   */
  @Test
  void should_pick_the_runs_own_buildomatic_log_when_a_later_run_wrote_a_newer_one()
      throws IOException {
    Path logs = Files.createDirectories(tmp.resolve("buildomatic").resolve("logs"));
    log(logs, "js-export-pro_2026-09-17_07-48_36783.log", START.minusSeconds(8 * 86_400));
    Path own = log(logs, "js-upgrade-newdb_2026-09-25_09-20_4411.log", START.plusSeconds(120));
    Path later = log(logs, "js-import-pro_2026-09-25_14-00_5120.log", START.plusSeconds(5 * 3600));

    Optional<VendorLogs.Source> picked = SupportBundle.forRun(buildomatic(later), RUN);

    assertThat(picked).isPresent();
    assertThat(picked.get().file()).isEqualTo(own);
    assertThat(picked.get().entry()).isEqualTo("vendor/buildomatic/" + own.getFileName());
  }

  @Test
  void should_keep_the_server_logs_when_they_are_older_than_the_run() throws IOException {
    Path serverLog = log(tmp, "jasperserver.log", START.minusSeconds(86_400));
    VendorLogs.Source source = new VendorLogs.Source("vendor/jasperserver.log", serverLog, false);

    assertThat(SupportBundle.forRun(source, RUN)).contains(source);
  }

  @Test
  void should_default_the_bundle_to_the_home_when_run_from_inside_the_distribution() {
    Path dist = tmp.resolve("jrs-upgrade-2.0.0");
    Path home = tmp.resolve("home");

    assertThat(RunsCommand.defaultOut("r-7", dist.resolve("bin"), Optional.of(dist), home))
        .isEqualTo(home.resolve("r-7-support-bundle.zip"));
    assertThat(RunsCommand.defaultOut("r-7", tmp.resolve("work"), Optional.of(dist), home))
        .isEqualTo(tmp.resolve("work").resolve("r-7-support-bundle.zip"));
    assertThat(RunsCommand.defaultOut("r-7", tmp.resolve("work"), Optional.empty(), home))
        .isEqualTo(tmp.resolve("work").resolve("r-7-support-bundle.zip"));
  }

  private static Path log(Path dir, String name, Instant modified) throws IOException {
    Path file = Files.writeString(dir.resolve(name), name);
    Files.setLastModifiedTime(file, FileTime.from(modified));
    return file;
  }

  private static VendorLogs.Source buildomatic(Path file) {
    return new VendorLogs.Source("vendor/buildomatic/" + file.getFileName(), file, false);
  }
}
