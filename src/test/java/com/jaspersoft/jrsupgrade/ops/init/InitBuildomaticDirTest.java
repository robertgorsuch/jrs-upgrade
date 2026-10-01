package com.jaspersoft.jrsupgrade.ops.init;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.ops.FakeLayout;
import com.jaspersoft.jrsupgrade.ops.FakePlatform;
import com.jaspersoft.jrsupgrade.ops.FakeServices;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0013: {@code init} writes {@code server.buildomaticDir} with its source, from {@code
 * --buildomatic-dir} or from discovery, and never writes a hint it could not reach.
 */
class InitBuildomaticDirTest {

  @TempDir Path tmp;

  @Test
  void should_propose_the_directory_given_on_the_command_line() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    Path share = Files.createDirectories(tmp.resolve("share")).resolve("buildomatic");
    Files.move(install.resolve("buildomatic"), share);
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.LINUX)) {
      fake.platform.on(List.of("systemctl"), FakePlatform.Response.ok(""));
      InitOperation init = new InitOperation(fake.build(), Optional::empty);

      InitReport report = init.detect(Optional.of(install), Optional.of(share));
      Config config = init.toConfig(report);

      assertThat(config.server().buildomaticDir()).contains(share.toAbsolutePath().normalize());
      // #73: read from that directory's default_master.properties at run time, not copied
      assertThat(config.database().type()).isEmpty();
      assertThat(report.values())
          .anyMatch(v -> v.key().equals("database.type") && v.value().equals("postgresql"));
      assertThat(report.values())
          .anyMatch(
              v ->
                  v.key().equals("server.buildomaticDir")
                      && v.source().contains("--buildomatic-dir"));
    }
  }

  @Test
  void should_discover_an_installed_tree_beside_the_installation_without_a_hint() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("opt").resolve("jrs"));
    Path beside = tmp.resolve("opt").resolve("buildomatic");
    Files.move(install.resolve("buildomatic"), beside);
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.LINUX)) {
      fake.platform.on(List.of("systemctl"), FakePlatform.Response.ok(""));
      InitOperation init = new InitOperation(fake.build(), Optional::empty);

      InitReport report = init.detect(Optional.of(install));

      assertThat(init.toConfig(report).server().buildomaticDir())
          .contains(beside.toAbsolutePath().normalize());
      assertThat(report.values())
          .anyMatch(
              v ->
                  v.key().equals("server.buildomaticDir")
                      && v.source().contains("next to the installation directory"));
    }
  }

  @Test
  void should_report_but_not_write_a_hint_that_cannot_be_reached() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    Path nowhere = tmp.resolve("not-mounted").resolve("buildomatic");
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.LINUX)) {
      fake.platform.on(List.of("systemctl"), FakePlatform.Response.ok(""));
      InitOperation init = new InitOperation(fake.build(), Optional::empty);

      InitReport report = init.detect(Optional.of(install), Optional.of(nowhere));

      assertThat(init.toConfig(report).server().buildomaticDir()).isEmpty();
      assertThat(report.values())
          .anyMatch(
              v ->
                  v.key().equals("server.buildomaticDir")
                      && v.value().equals("(not detected)")
                      && v.source().contains("not a directory"));
    }
  }
}
