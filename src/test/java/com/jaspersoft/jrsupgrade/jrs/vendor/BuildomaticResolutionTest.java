package com.jaspersoft.jrsupgrade.jrs.vendor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.jrs.FakePlatform;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0013: where the installed buildomatic directory is taken from when it is not under the
 * install directory. The configured key wins and is never second-guessed; discovery only accepts a
 * tree that looks installed, and refuses to choose between two.
 */
class BuildomaticResolutionTest {

  @TempDir Path tmp;

  private final BuildomaticLocator linux =
      new BuildomaticLocator(new FakePlatform(Platform.OsFamily.LINUX));

  @Test
  void should_use_the_configured_directory_when_one_also_exists_under_the_install_dir()
      throws IOException {
    Path install = Files.createDirectories(tmp.resolve("jrs"));
    installed(install.resolve("buildomatic"), ".sh");
    Path share = installed(tmp.resolve("share").resolve("buildomatic"), ".sh");

    BuildomaticResolution r =
        linux.resolve(config(Optional.of(install), Optional.empty(), Optional.of(share)));

    assertThat(r).isInstanceOf(BuildomaticResolution.Found.class);
    BuildomaticResolution.Found found = (BuildomaticResolution.Found) r;
    assertThat(found.buildomatic().dir()).isEqualTo(normal(share));
    assertThat(found.source()).isEqualTo(BuildomaticLocator.SOURCE_CONFIGURED);
  }

  @Test
  void should_not_fall_back_when_the_configured_directory_is_unreachable() throws IOException {
    Path install = Files.createDirectories(tmp.resolve("jrs"));
    installed(install.resolve("buildomatic"), ".sh");
    Path gone = tmp.resolve("unmounted").resolve("buildomatic");

    BuildomaticResolution r =
        linux.resolve(config(Optional.of(install), Optional.empty(), Optional.of(gone)));

    assertThat(r.located()).isEmpty();
    BuildomaticResolution.NotFound missing = (BuildomaticResolution.NotFound) r;
    assertThat(missing.reason()).isEqualTo(BuildomaticResolution.Reason.CONFIGURED_UNREACHABLE);
    assertThat(missing.detail()).contains(normal(gone).toString());
    assertThat(missing.remediation()).contains("server.buildomaticDir");
  }

  @Test
  void should_keep_the_directory_under_the_install_dir_when_nothing_else_is_configured()
      throws IOException {
    Path install = Files.createDirectories(tmp.resolve("jrs"));
    Files.createDirectories(install.resolve("buildomatic"));

    BuildomaticResolution r =
        linux.resolve(config(Optional.of(install), Optional.empty(), Optional.empty()));

    assertThat(r.located().map(Buildomatic::dir)).contains(normal(install.resolve("buildomatic")));
    assertThat(((BuildomaticResolution.Found) r).source())
        .isEqualTo(BuildomaticLocator.SOURCE_UNDER_INSTALL);
  }

  @Test
  void should_find_an_installed_tree_beside_the_install_dir() throws IOException {
    Path install = Files.createDirectories(tmp.resolve("jrs"));
    Path beside = installed(tmp.resolve("buildomatic"), ".sh");

    BuildomaticResolution r =
        linux.resolve(config(Optional.of(install), Optional.empty(), Optional.empty()));

    assertThat(r.located().map(Buildomatic::dir)).contains(normal(beside));
    assertThat(((BuildomaticResolution.Found) r).source())
        .isEqualTo("next to the installation directory (server.installDir)");
  }

  @Test
  void should_find_the_tree_beside_a_tomcat_on_another_volume() throws IOException {
    Path install = Files.createDirectories(tmp.resolve("c").resolve("jrs"));
    Path tomcat = Files.createDirectories(tmp.resolve("d").resolve("tomcat"));
    Path beside = installed(tmp.resolve("d").resolve("buildomatic"), ".sh");

    BuildomaticResolution r =
        linux.resolve(config(Optional.of(install), Optional.of(tomcat), Optional.empty()));

    assertThat(r.located().map(Buildomatic::dir)).contains(normal(beside));
    assertThat(((BuildomaticResolution.Found) r).source())
        .isEqualTo("next to the Tomcat directory (server.tomcatDir), not inside it");
  }

  @Test
  void should_find_the_tree_in_a_vendor_distribution_directory_under_the_install_dir()
      throws IOException {
    Path install = Files.createDirectories(tmp.resolve("jrs"));
    Path dist =
        installed(install.resolve("jasperreports-server-8.2.0-bin").resolve("buildomatic"), ".sh");

    BuildomaticResolution r =
        linux.resolve(config(Optional.of(install), Optional.empty(), Optional.empty()));

    assertThat(r.located().map(Buildomatic::dir)).contains(normal(dist));
  }

  @Test
  void should_ignore_an_unpacked_upgrade_package_beside_the_installation() throws IOException {
    Path install = Files.createDirectories(tmp.resolve("jrs"));
    Path pkg = tmp.resolve("jasperreports-server-9.0.0-bin").resolve("buildomatic");
    Files.createDirectories(pkg);
    for (String name : Buildomatic.SCRIPT_NAMES) {
      Files.writeString(pkg.resolve(name + ".sh"), "#!/bin/sh\n", StandardCharsets.UTF_8);
    }

    BuildomaticResolution r =
        linux.resolve(config(Optional.of(install), Optional.empty(), Optional.empty()));

    BuildomaticResolution.NotFound missing = (BuildomaticResolution.NotFound) r;
    assertThat(missing.reason()).isEqualTo(BuildomaticResolution.Reason.NOT_FOUND);
    assertThat(missing.detail()).contains(normal(install.resolve("buildomatic")).toString());
    assertThat(missing.remediation()).contains("server.buildomaticDir").contains("network share");
  }

  @Test
  void should_refuse_to_choose_when_two_installed_trees_match() throws IOException {
    Path install = Files.createDirectories(tmp.resolve("jrs"));
    Path beside = installed(tmp.resolve("buildomatic"), ".sh");
    Path dist = installed(install.resolve("jasperreports-server-cp").resolve("buildomatic"), ".sh");

    BuildomaticResolution r =
        linux.resolve(config(Optional.of(install), Optional.empty(), Optional.empty()));

    BuildomaticResolution.NotFound missing = (BuildomaticResolution.NotFound) r;
    assertThat(missing.reason()).isEqualTo(BuildomaticResolution.Reason.AMBIGUOUS);
    assertThat(missing.detail())
        .contains(normal(beside).toString())
        .contains(normal(dist).toString());
  }

  @Test
  void should_only_accept_a_neighbour_holding_this_platforms_script() throws IOException {
    Path install = Files.createDirectories(tmp.resolve("jrs"));
    installed(tmp.resolve("buildomatic"), ".bat");

    BuildomaticResolution r =
        linux.resolve(config(Optional.of(install), Optional.empty(), Optional.empty()));

    assertThat(r.located()).isEmpty();
  }

  @Test
  void should_say_nothing_is_configured_when_neither_key_is_set() {
    BuildomaticResolution r =
        linux.resolve(config(Optional.empty(), Optional.empty(), Optional.empty()));

    assertThat(((BuildomaticResolution.NotFound) r).reason())
        .isEqualTo(BuildomaticResolution.Reason.NOT_CONFIGURED);
  }

  @Test
  void should_recognise_unc_paths_only() {
    assertThat(BuildomaticLocator.isUncPath(Path.of("\\\\fs01\\jrs\\buildomatic"))).isTrue();
    assertThat(BuildomaticLocator.isUncPath(Path.of("C:\\jrs\\buildomatic"))).isFalse();
    assertThat(BuildomaticLocator.isUncPath(Path.of("/opt/jrs/buildomatic"))).isFalse();
  }

  private static Path installed(Path dir, String ext) throws IOException {
    Files.createDirectories(dir);
    for (String name : Buildomatic.SCRIPT_NAMES) {
      Files.writeString(dir.resolve(name + ext), "echo\n", StandardCharsets.UTF_8);
    }
    Files.writeString(
        dir.resolve(Buildomatic.MASTER_PROPERTIES), "dbType=postgresql\n", StandardCharsets.UTF_8);
    return dir;
  }

  private static Path normal(Path path) {
    return path.toAbsolutePath().normalize();
  }

  private static Config config(
      Optional<Path> installDir, Optional<Path> tomcatDir, Optional<Path> buildomaticDir) {
    Config d = Config.defaults();
    return new Config(
        new Config.Server(
            Optional.empty(),
            Optional.empty(),
            installDir,
            tomcatDir,
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
