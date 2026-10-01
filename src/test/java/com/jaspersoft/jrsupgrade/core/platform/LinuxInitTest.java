package com.jaspersoft.jrsupgrade.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Review finding 3.2: the Linux service manager is identified, never assumed to be systemd. */
class LinuxInitTest {

  @Test
  void should_report_systemd_when_process_one_is_systemd(@TempDir Path root) throws IOException {
    LinuxInit.Detected detected = detect(root, "systemd", false, false);

    assertThat(detected.kind()).isEqualTo(LinuxInit.Kind.SYSTEMD);
    assertThat(detected.systemd()).isTrue();
    assertThat(detected.supervised()).isFalse();
  }

  @Test
  void should_report_supervisor_when_process_one_is_supervisord(@TempDir Path root)
      throws IOException {
    LinuxInit.Detected detected = detect(root, "supervisord", false, false);

    assertThat(detected.kind()).isEqualTo(LinuxInit.Kind.SUPERVISOR);
    assertThat(detected.supervised()).isTrue();
    assertThat(detected.detail()).contains("supervisord");
  }

  @Test
  void should_report_sysv_when_init_scripts_exist_without_systemd(@TempDir Path root)
      throws IOException {
    LinuxInit.Detected detected = detect(root, "init", true, false);

    assertThat(detected.kind()).isEqualTo(LinuxInit.Kind.SYSV);
    assertThat(detected.supervised()).isTrue();
    assertThat(detected.detail()).contains("systemctl is not available");
  }

  @Test
  void should_report_openrc_when_the_openrc_runtime_dir_exists(@TempDir Path root)
      throws IOException {
    LinuxInit.Detected detected = detect(root, "init", true, true);

    assertThat(detected.kind()).isEqualTo(LinuxInit.Kind.OPENRC);
    assertThat(detected.supervised()).isTrue();
  }

  @Test
  void should_report_no_service_manager_when_process_one_is_the_container_entrypoint(
      @TempDir Path root) throws IOException {
    LinuxInit.Detected detected = detect(root, "catalina.sh", false, false);

    assertThat(detected.kind()).isEqualTo(LinuxInit.Kind.OTHER);
    assertThat(detected.supervised()).isFalse();
    assertThat(detected.detail()).contains("no service manager detected");
  }

  @Test
  void should_report_unknown_when_proc_is_not_readable(@TempDir Path root) {
    LinuxInit.Detected detected =
        LinuxInit.detect(root.resolve("absent"), root.resolve("init.d"), root.resolve("openrc"));

    assertThat(detected.kind()).isEqualTo(LinuxInit.Kind.UNKNOWN);
    assertThat(detected.pid1()).isEmpty();
    assertThat(detected.detail()).contains("no service manager detected");
  }

  private static LinuxInit.Detected detect(Path root, String pid1, boolean initD, boolean openrc)
      throws IOException {
    Path comm = root.resolve("comm");
    Files.writeString(comm, pid1 + "\n", StandardCharsets.UTF_8);
    Path etcInitD = root.resolve("init.d");
    if (initD) {
      Files.createDirectories(etcInitD);
    }
    Path runOpenrc = root.resolve("openrc");
    if (openrc) {
      Files.createDirectories(runOpenrc);
    }
    return LinuxInit.detect(comm, etcInitD, runOpenrc);
  }
}
