package com.jaspersoft.jrsupgrade.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.platform.Platform.OsFamily;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TomcatJavaOptsTest {

  @TempDir Path tmp;

  private Path tomcat(String version) throws IOException {
    Path dir = Files.createDirectories(tmp.resolve("tomcat-" + version));
    Files.createDirectories(dir.resolve("bin"));
    Files.writeString(dir.resolve("RELEASE-NOTES"), "Apache Tomcat Version " + version + "\n");
    return dir;
  }

  @Test
  void should_name_setenv_sh_on_linux_and_setenv_bat_on_windows() {
    Path dir = tmp.resolve("t");
    assertThat(TomcatJavaOpts.setenv(dir, OsFamily.LINUX))
        .isEqualTo(dir.resolve("bin").resolve("setenv.sh"));
    assertThat(TomcatJavaOpts.setenv(dir, OsFamily.WINDOWS))
        .isEqualTo(dir.resolve("bin").resolve("setenv.bat"));
  }

  @Test
  void should_report_the_setenv_file_when_a_tomcat_10_carries_no_add_opens() throws IOException {
    Path dir = tomcat("10.1.41");
    // the vendor's own bundled installer writes exactly this on 10.0.0: no --add-opens at all
    Files.writeString(
        dir.resolve("bin").resolve("setenv.bat"),
        "set \"JAVA_OPTS=-Djs.license.directory=C:/Jaspersoft -Xms1024m -Xmx4096m %JAVA_OPTS%\"\n",
        StandardCharsets.UTF_8);

    assertThat(TomcatJavaOpts.missingAddOpens(dir, OsFamily.WINDOWS))
        .contains(dir.resolve("bin").resolve("setenv.bat"));
  }

  @Test
  void should_report_the_setenv_file_when_it_does_not_exist_on_a_tomcat_11() throws IOException {
    Path dir = tomcat("11.0.11");

    assertThat(TomcatJavaOpts.missingAddOpens(dir, OsFamily.LINUX))
        .contains(dir.resolve("bin").resolve("setenv.sh"));
  }

  @Test
  void should_be_satisfied_when_setenv_carries_add_opens() throws IOException {
    Path dir = tomcat("11.0.11");
    Files.writeString(
        dir.resolve("bin").resolve("setenv.sh"),
        "export JAVA_OPTS=\"$JAVA_OPTS --add-opens java.base/java.io=ALL-UNNAMED"
            + " --add-opens java.base/java.lang=ALL-UNNAMED\"\n",
        StandardCharsets.UTF_8);

    assertThat(TomcatJavaOpts.missingAddOpens(dir, OsFamily.LINUX)).isEmpty();
  }

  @Test
  void should_not_judge_a_tomcat_9_or_one_whose_version_is_unknown() throws IOException {
    Path nine = tomcat("9.0.85");
    Path unknown = Files.createDirectories(tmp.resolve("unknown"));

    assertThat(TomcatJavaOpts.missingAddOpens(nine, OsFamily.LINUX)).isEmpty();
    assertThat(TomcatJavaOpts.missingAddOpens(unknown, OsFamily.LINUX)).isEmpty();
  }
}
