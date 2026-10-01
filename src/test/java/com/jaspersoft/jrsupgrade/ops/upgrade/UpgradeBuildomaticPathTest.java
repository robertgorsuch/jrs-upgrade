package com.jaspersoft.jrsupgrade.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.json.Json;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.jrs.vendor.BuildomaticLocator;
import com.jaspersoft.jrsupgrade.ops.FakeServices;
import com.jaspersoft.jrsupgrade.ops.hotfix.HotfixPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0013: an upgrade backs up and a rollback restores the buildomatic tree the locator settled
 * on, which may be away from the install directory; a rollback puts the archive back where the run
 * recorded it came from, and planning refuses to guess.
 */
class UpgradeBuildomaticPathTest {

  @TempDir Path tmp;

  @Test
  void should_read_the_recorded_buildomatic_path_back_from_the_point_b_manifest() throws Exception {
    SnapshotSet set = new SnapshotSet(tmp.resolve("snap"), Platform.OsFamily.WINDOWS);
    Files.createDirectories(set.manifest().getParent());
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("runId", "r-up");
    manifest.put("buildomatic", "\\\\fs01\\jrs\\buildomatic");
    Files.writeString(set.manifest(), Json.writePretty(manifest), StandardCharsets.UTF_8);

    assertThat(DefaultUpgradeOperations.recordedBuildomatic(set))
        .contains(Path.of("\\\\fs01\\jrs\\buildomatic"));
  }

  @Test
  void should_resolve_from_the_configuration_when_no_manifest_was_written() {
    SnapshotSet set = new SnapshotSet(tmp.resolve("empty"), Platform.OsFamily.LINUX);

    assertThat(DefaultUpgradeOperations.recordedBuildomatic(set)).isEmpty();
  }

  @Test
  void should_refuse_to_plan_against_an_unreachable_configured_buildomatic_dir() throws Exception {
    Path install = Files.createDirectories(tmp.resolve("jrs"));
    HotfixPaths paths = new HotfixPaths(install, install.resolve("apache-tomcat"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.LINUX)) {
      BuildomaticLocator locator = new BuildomaticLocator(fake.platform);
      Config unreachable = config(install, Optional.of(tmp.resolve("gone").resolve("buildomatic")));

      assertThatThrownBy(
              () -> UpgradeInput.resolveInstalledBuildomatic(locator, unreachable, paths))
          .isInstanceOf(UpgradeException.class)
          .hasMessageContaining("server.buildomaticDir");
      assertThat(
              UpgradeInput.resolveInstalledBuildomatic(
                  locator, config(install, Optional.empty()), paths))
          .isEqualTo(paths.installDir().resolve("buildomatic"));
    }
  }

  private static Config config(Path installDir, Optional<Path> buildomaticDir) {
    Config d = Config.defaults();
    return new Config(
        new Config.Server(
            Optional.empty(),
            Optional.empty(),
            Optional.of(installDir),
            Optional.empty(),
            buildomaticDir,
            Optional.empty(),
            Config.Auth.defaults()),
        d.service(),
        d.database(),
        d.vendor(),
        d.network(),
        d.backups(),
        d.smoke());
  }
}
