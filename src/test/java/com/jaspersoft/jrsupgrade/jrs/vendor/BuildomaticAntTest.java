package com.jaspersoft.jrsupgrade.jrs.vendor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.jrs.FakePlatform;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #31: the vendor setup script finds its bundled Ant only next to the buildomatic directory, and
 * otherwise runs whatever {@code ant} is on PATH. A buildomatic moved away from the installation
 * without its Ant failed every export and import, so {@code doctor} needs to see the same thing.
 */
class BuildomaticAntTest {

  @TempDir Path tmp;

  private static Buildomatic buildomaticAt(Path dir) throws IOException {
    Files.createDirectories(dir);
    return new Buildomatic(dir, Map.of(), Optional.empty(), Map.of());
  }

  @Test
  void should_find_the_bundled_ant_next_to_the_buildomatic_directory() throws IOException {
    Buildomatic b = buildomaticAt(tmp.resolve("jrs").resolve("buildomatic"));
    Path antBin = Files.createDirectories(tmp.resolve("jrs").resolve("apache-ant").resolve("bin"));
    Files.writeString(antBin.resolve("ant"), "#!/bin/sh\n", StandardCharsets.UTF_8);
    Files.writeString(antBin.resolve("ant.bat"), "@echo off\r\n", StandardCharsets.UTF_8);

    assertThat(new BuildomaticLocator(new FakePlatform(Platform.OsFamily.LINUX)).ant(b, Map.of()))
        .contains(antBin.resolve("ant"));
    assertThat(new BuildomaticLocator(new FakePlatform(Platform.OsFamily.WINDOWS)).ant(b, Map.of()))
        .contains(antBin.resolve("ant.bat"));
  }

  @Test
  void should_fall_back_to_an_ant_launcher_on_path_matched_case_insensitively() throws IOException {
    Buildomatic b = buildomaticAt(tmp.resolve("share").resolve("buildomatic"));
    Path tools = Files.createDirectories(tmp.resolve("tools").resolve("ant").resolve("bin"));
    Files.writeString(tools.resolve("ant.cmd"), "@echo off\r\n", StandardCharsets.UTF_8);
    String path = tmp.resolve("empty") + ";\"" + tools + "\";";

    Optional<Path> ant =
        new BuildomaticLocator(new FakePlatform(Platform.OsFamily.WINDOWS))
            .ant(b, Map.of("Path", path));

    assertThat(ant).contains(tools.resolve("ant.cmd"));
  }

  @Test
  void should_find_nothing_when_neither_bundled_nor_on_path() throws IOException {
    Buildomatic b = buildomaticAt(tmp.resolve("bare").resolve("buildomatic"));

    assertThat(
            new BuildomaticLocator(new FakePlatform(Platform.OsFamily.WINDOWS))
                .ant(b, Map.of("PATH", tmp.resolve("nothing-here").toString())))
        .isEmpty();
  }
}
