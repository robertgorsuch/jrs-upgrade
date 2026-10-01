package com.jaspersoft.jrsupgrade.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.platform.OperatorPrompt;
import com.jaspersoft.jrsupgrade.core.platform.Platforms;
import com.jaspersoft.jrsupgrade.jrs.vendor.BuildomaticLocator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What an upgrade package turns out to contain. The version an upgrade reports it is going to is
 * read from the package itself, from a webapp directory, a war, a jar name or the directory name,
 * so an operator pointing at the wrong archive is told before anything is stopped.
 */
class TargetPackageTest {

  @TempDir Path tmp;

  private static final BuildomaticLocator NONE =
      new BuildomaticLocator(Platforms.detect(OperatorPrompt.nonInteractive()));

  @Test
  void should_report_everything_absent_when_the_directory_does_not_exist() {
    TargetPackage pkg = TargetPackage.inspect(tmp.resolve("absent"), NONE);

    assertThat(pkg.buildomatic()).isEmpty();
    assertThat(pkg.webappDir()).isEmpty();
    assertThat(pkg.warFile()).isEmpty();
    assertThat(pkg.discoveredVersion()).isEmpty();
    assertThat(pkg.dir()).isAbsolute();
  }

  @Test
  void should_find_a_webapp_directory_and_its_version_from_a_jar_name() throws IOException {
    Path dir = Files.createDirectories(tmp.resolve("package"));
    Path lib =
        Files.createDirectories(dir.resolve("jasperserver-pro").resolve("WEB-INF").resolve("lib"));
    Files.writeString(
        lib.resolve("jasperserver-api-common-10.0.0.jar"), "x", StandardCharsets.UTF_8);

    TargetPackage pkg = TargetPackage.inspect(dir, NONE);

    assertThat(pkg.webappDir()).isPresent();
    assertThat(pkg.webappDir().orElseThrow().getFileName().toString())
        .isEqualTo("jasperserver-pro");
    assertThat(pkg.discoveredVersion()).contains("10.0.0");
  }

  @Test
  void should_find_a_war_file_when_there_is_no_exploded_webapp() throws IOException {
    Path dir = Files.createDirectories(tmp.resolve("war-package"));
    Files.writeString(dir.resolve("jasperserver-pro.war"), "war", StandardCharsets.UTF_8);

    TargetPackage pkg = TargetPackage.inspect(dir, NONE);

    assertThat(pkg.warFile()).isPresent();
    assertThat(pkg.webappDir()).isEmpty();
  }

  @Test
  void should_read_the_version_from_the_directory_name_when_nothing_else_says_it()
      throws IOException {
    Path dir = Files.createDirectories(tmp.resolve("jasperreports-server-pro-9.0.0"));
    Files.createDirectories(dir.resolve("jasperserver-pro").resolve("WEB-INF").resolve("lib"));

    TargetPackage pkg = TargetPackage.inspect(dir, NONE);

    assertThat(pkg.discoveredVersion()).contains("9.0.0");
  }
}
