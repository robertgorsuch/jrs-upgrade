package com.jaspersoft.jrsupgrade.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Review finding 3.4: the SQLite native library never runs from a noexec system temp directory. */
class NativeTempDirTest {

  private String saved;

  @AfterEach
  void restore() {
    if (saved == null) {
      System.clearProperty(NativeTempDir.PROPERTY);
    } else {
      System.setProperty(NativeTempDir.PROPERTY, saved);
    }
  }

  @Test
  void should_create_and_select_the_directory_when_the_property_is_unset(@TempDir Path home) {
    saved = System.getProperty(NativeTempDir.PROPERTY);
    System.clearProperty(NativeTempDir.PROPERTY);
    Path runs = home.resolve("runs");

    Path chosen = NativeTempDir.use(runs);

    assertThat(chosen).isEqualTo(runs);
    assertThat(runs).isDirectory();
    assertThat(System.getProperty(NativeTempDir.PROPERTY))
        .isEqualTo(runs.toAbsolutePath().normalize().toString());
    assertThat(NativeTempDir.current()).isEqualTo(runs.toAbsolutePath().normalize());
  }

  @Test
  void should_keep_the_operators_choice_when_the_property_is_already_set(@TempDir Path home) {
    saved = System.getProperty(NativeTempDir.PROPERTY);
    Path explicit = home.resolve("explicit");
    System.setProperty(NativeTempDir.PROPERTY, explicit.toString());

    Path chosen = NativeTempDir.use(home.resolve("runs"));

    assertThat(chosen).isEqualTo(explicit);
    assertThat(home.resolve("runs")).doesNotExist();
  }

  @Test
  void should_name_the_noexec_mount_when_the_directory_sits_on_one(@TempDir Path root)
      throws IOException {
    Path tmpMount = root.resolve("tmp");
    Path mounts = mounts(root, "tmpfs " + tmpMount + " tmpfs rw,nosuid,nodev,noexec,relatime 0 0");

    assertThat(NativeTempDir.noexecReason(tmpMount.resolve("jrs-upgrade"), mounts))
        .hasValueSatisfying(
            r -> assertThat(r).contains(tmpMount.toString()).contains("mounted noexec"));
  }

  @Test
  void should_prefer_the_longest_matching_mount_point(@TempDir Path root) throws IOException {
    Path tmpMount = root.resolve("tmp");
    Path workMount = tmpMount.resolve("work");
    Path mounts =
        mounts(
            root,
            "/dev/sda1 " + root + " ext4 rw,relatime 0 0",
            "tmpfs " + tmpMount + " tmpfs rw,noexec 0 0",
            "/dev/sdb1 " + workMount + " ext4 rw,relatime 0 0");

    assertThat(NativeTempDir.noexecReason(workMount.resolve("runs"), mounts)).isEmpty();
    assertThat(NativeTempDir.noexecReason(tmpMount.resolve("other"), mounts)).isPresent();
  }

  @Test
  void should_be_silent_when_there_is_no_mount_table(@TempDir Path root) {
    assertThat(NativeTempDir.noexecReason(root, root.resolve("absent"))).isEmpty();
  }

  private static Path mounts(Path root, String... lines) throws IOException {
    Path file = root.resolve("mounts");
    Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    return file;
  }
}
