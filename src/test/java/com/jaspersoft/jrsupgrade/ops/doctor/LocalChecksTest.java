package com.jaspersoft.jrsupgrade.ops.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.platform.ProcessRunner;
import com.jaspersoft.jrsupgrade.core.platform.TomcatLayout;
import com.jaspersoft.jrsupgrade.ops.FakeLayout;
import com.jaspersoft.jrsupgrade.ops.FakePlatform;
import com.jaspersoft.jrsupgrade.ops.FakeServices;
import com.jaspersoft.jrsupgrade.ops.ReportItem;
import com.jaspersoft.jrsupgrade.ops.Services;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The doctor checks that need no server: what they report when the installation is sound, and what
 * they report when it is not. Each is read-only and each non-PASS item has to name the next action,
 * which is the part an operator actually uses.
 */
class LocalChecksTest {

  @TempDir Path tmp;

  @Test
  void should_warn_when_there_is_no_config_file_and_fail_when_it_has_no_base_url()
      throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"))) {
      Services services = fake.build();

      ReportItem missing = LocalChecks.config(services);

      assertThat(missing.status()).isEqualTo(ReportItem.Status.WARN);
      assertThat(missing.remediation()).contains("jrs-upgrade init");
    }
  }

  /**
   * The Windows elevation probe is {@code fltmc} (80 ms), not {@code whoami /groups}, which asks
   * the domain controller for every group of the user and took seconds on a domain-joined machine.
   */
  @Test
  void should_judge_windows_elevation_by_fltmc_alone() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("elevated"), Platform.OsFamily.WINDOWS)) {
      fake.platform.on(List.of("fltmc"), new FakePlatform.Response(0, List.of("Filter Name")));

      ReportItem item = LocalChecks.elevated(fake.build());

      assertThat(item.status()).isEqualTo(ReportItem.Status.WARN);
      assertThat(fake.platform.invocations).containsExactly(List.of("fltmc"));
    }
    try (FakeServices fake = FakeServices.in(tmp.resolve("plain"), Platform.OsFamily.WINDOWS)) {
      fake.platform.on(
          List.of("fltmc"), new FakePlatform.Response(5, List.of("Access is denied.")));

      ReportItem item = LocalChecks.elevated(fake.build());

      assertThat(item.status()).isEqualTo(ReportItem.Status.PASS);
      assertThat(fake.platform.invocations).containsExactly(List.of("fltmc"));
    }
  }

  @Test
  void should_count_a_windows_probe_that_cannot_run_as_not_elevated() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("missing"), Platform.OsFamily.WINDOWS)) {
      // nothing scripted: the fake answers exit code 1, as a probe that failed would
      assertThat(LocalChecks.elevated(fake.build()).status()).isEqualTo(ReportItem.Status.PASS);
    }
    ProcessRunner throwing =
        (request, onLine) -> {
          throw new IllegalStateException("no such program");
        };
    assertThat(LocalChecks.windowsElevated(throwing)).isFalse();
  }

  @Test
  void should_pass_the_disk_check_with_room_and_fail_it_without() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("disk"))) {
      fake.platform.freeSpace = 100L * LocalChecks.GIB;
      assertThat(LocalChecks.disk(fake.build()).status()).isEqualTo(ReportItem.Status.PASS);
    }
    try (FakeServices fake = FakeServices.in(tmp.resolve("full"))) {
      fake.platform.freeSpace = LocalChecks.DISK_FAIL_BYTES / 2;
      ReportItem item = LocalChecks.disk(fake.build());

      assertThat(item.status()).isEqualTo(ReportItem.Status.FAIL);
      assertThat(item.remediation()).contains("prune");
    }
    try (FakeServices fake = FakeServices.in(tmp.resolve("tight"))) {
      fake.platform.freeSpace = 2 * LocalChecks.GIB;
      assertThat(LocalChecks.disk(fake.build()).status()).isEqualTo(ReportItem.Status.WARN);
    }
  }

  /**
   * Field test 2, U3: the disk item measured the installation's volume only, so a nearly full /home
   * holding the jrs-upgrade home passed. Both volumes are judged and named.
   */
  @Test
  void should_measure_the_home_volume_as_well_as_the_installation() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("home-full"))) {
      fake.platform.freeSpace = 100L * LocalChecks.GIB;
      fake.platform.freeSpaceUnder.put(fake.home.root(), LocalChecks.DISK_FAIL_BYTES / 2);

      ReportItem item = LocalChecks.disk(fake.build());

      assertThat(item.status()).isEqualTo(ReportItem.Status.FAIL);
      assertThat(item.detail()).contains(fake.home.root().toString()).contains("backups and state");
      // field test 3: the advice names the short volume and how to make room or move the home
      assertThat(item.remediation())
          .contains("the jrs-upgrade home's volume")
          .contains("jrs-upgrade runs prune")
          .contains("jrs-upgrade home set <dir>");
    }
  }

  @Test
  void should_skip_the_permission_and_vendor_checks_without_a_layout() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("nolayout"))) {
      Services services = fake.build();

      assertThat(LocalChecks.permissions(services, Optional.empty()).status())
          .isEqualTo(ReportItem.Status.SKIP);
      assertThat(LocalChecks.vendor(services).status()).isEqualTo(ReportItem.Status.SKIP);
      assertThat(LocalChecks.layout(services, Optional.empty()).status())
          .isEqualTo(ReportItem.Status.FAIL);
    }
  }

  @Test
  void should_report_the_vendor_scripts_and_notice_a_missing_one() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("vendor"))) {
      fake.yaml(serverYaml("  installDir: " + yamlPath(install) + "\n"));
      Services services = fake.build();
      Optional<TomcatLayout> layout = services.platform().detectTomcat(install);
      assertThat(layout).isPresent();

      ReportItem found = LocalChecks.vendor(services);
      assertThat(found.status()).isEqualTo(ReportItem.Status.PASS);
      assertThat(found.detail()).contains("inside the installation directory");

      Files.delete(install.resolve("buildomatic").resolve("js-import.sh"));
      ReportItem missing = LocalChecks.vendor(services);

      assertThat(missing.status()).isEqualTo(ReportItem.Status.FAIL);
      assertThat(missing.detail()).contains("js-import.sh");
      assertThat(missing.remediation()).contains("vendor scripts");
    }
  }

  /** ADR-0013: the vendor tree may live on another volume or a share, away from the install. */
  @Test
  void should_check_the_vendor_scripts_where_server_buildomatic_dir_points() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    Path volume = Files.createDirectories(tmp.resolve("vendor-volume"));
    Path elsewhere = volume.resolve("buildomatic");
    Files.move(install.resolve("buildomatic"), elsewhere);
    Files.move(install.resolve("apache-ant"), volume.resolve("apache-ant"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("moved"))) {
      fake.yaml(
          serverYaml(
              "  installDir: "
                  + yamlPath(install)
                  + "\n  buildomaticDir: "
                  + yamlPath(elsewhere)
                  + "\n"));
      Services services = fake.build();

      ReportItem vendor = LocalChecks.vendor(services, Map.of());
      ReportItem permissions =
          LocalChecks.permissions(services, services.platform().detectTomcat(install));

      assertThat(vendor.status()).isEqualTo(ReportItem.Status.PASS);
      assertThat(vendor.detail())
          .contains(elsewhere.toString())
          .contains("server.buildomaticDir")
          .contains("apache-ant");
      assertThat(permissions.detail()).contains("3 target dirs");
    }
  }

  /**
   * #31: the vendor setup script finds its bundled Ant only at {@code ..\apache-ant}, so a
   * buildomatic moved without it fails every export and import with "ant is not recognized".
   */
  @Test
  void should_warn_when_a_relocated_buildomatic_can_find_no_ant() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    Path elsewhere = Files.createDirectories(tmp.resolve("bare-volume")).resolve("buildomatic");
    Files.move(install.resolve("buildomatic"), elsewhere);
    try (FakeServices fake = FakeServices.in(tmp.resolve("no-ant"))) {
      fake.yaml(
          serverYaml(
              "  installDir: "
                  + yamlPath(install)
                  + "\n  buildomaticDir: "
                  + yamlPath(elsewhere)
                  + "\n"));

      ReportItem vendor = LocalChecks.vendor(fake.build(), Map.of("PATH", ""));

      assertThat(vendor.status()).isEqualTo(ReportItem.Status.WARN);
      assertThat(vendor.detail()).contains("no Ant").contains("apache-ant");
      assertThat(vendor.remediation()).contains("apache-ant").contains("PATH");
    }
  }

  @Test
  void should_fail_instead_of_falling_back_when_the_configured_buildomatic_dir_is_unreachable()
      throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    Path unmounted = tmp.resolve("unmounted-share").resolve("buildomatic");
    try (FakeServices fake = FakeServices.in(tmp.resolve("unreachable"))) {
      fake.yaml(
          serverYaml(
              "  installDir: "
                  + yamlPath(install)
                  + "\n  buildomaticDir: "
                  + yamlPath(unmounted)
                  + "\n"));

      ReportItem vendor = LocalChecks.vendor(fake.build());

      assertThat(vendor.status()).isEqualTo(ReportItem.Status.FAIL);
      assertThat(vendor.detail()).contains("server.buildomaticDir").contains("buildomatic");
      assertThat(vendor.remediation()).contains("mount");
    }
  }

  private static String serverYaml(String keys) {
    return "server:\n  baseUrl: http://localhost:8081/jasperserver-pro\n" + keys;
  }

  private static String yamlPath(Path path) {
    return "'" + path.toAbsolutePath().toString().replace('\\', '/') + "'";
  }

  @Test
  void should_report_a_free_lock_no_pending_runs_and_an_empty_snapshot_store() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("clean"))) {
      Services services = fake.build();

      assertThat(LocalChecks.lock(services).status()).isEqualTo(ReportItem.Status.PASS);
      assertThat(LocalChecks.runs(services).status()).isEqualTo(ReportItem.Status.PASS);
      assertThat(LocalChecks.snapshots(services).detail()).contains("0 snapshot");
      assertThat(LocalChecks.state(services).status()).isEqualTo(ReportItem.Status.PASS);
    }
  }

  @Test
  void should_warn_when_isolated_mode_is_paired_with_a_proxy() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("network"))) {
      Services services =
          fake.yaml(
                  """
                  network:
                    mode: isolated
                    proxy:
                      host: proxy.example.internal
                      port: 8080
                  """)
              .build();

      ReportItem item = LocalChecks.network(services);

      assertThat(item.status()).isEqualTo(ReportItem.Status.WARN);
      assertThat(item.remediation()).contains("network.proxy");
    }
  }

  @Test
  void should_report_a_human_readable_size() throws IOException {
    assertThat(LocalChecks.human(5L * LocalChecks.GIB)).isEqualTo("5.0 GB");
    assertThat(LocalChecks.human(3L << 20)).isEqualTo("3.0 MB");
    assertThat(LocalChecks.human(512)).isEqualTo("512 B");
  }
}
