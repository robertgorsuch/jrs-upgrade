package com.jaspersoft.jrsupgrade.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.engine.FakePlatform;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskSpaceTest {

  @TempDir Path tmp;

  /**
   * Review finding 1.16: needs on the same volume add up, every volume is checked against its own
   * free space, and a safety margin is kept on each. A directory that does not exist yet is
   * measured through its nearest existing ancestor.
   */
  @Test
  void should_sum_needs_per_volume_and_keep_the_margin_when_checking() throws IOException {
    FakePlatform platform = new FakePlatform(tmp);
    Path a = Files.createDirectories(tmp.resolve("volA"));
    Path b = Files.createDirectories(tmp.resolve("volB"));
    platform.freeSpaceUnder.put(a, 1_000L + DiskSpace.MARGIN_BYTES);
    platform.freeSpaceUnder.put(b, 100L + DiskSpace.MARGIN_BYTES);
    FileOps files = platform.files();

    assertThat(
            DiskSpace.problems(
                files,
                List.of(
                    new DiskSpace.Need("staging", a.resolve("runs/r1/staging"), 600),
                    new DiskSpace.Need("snapshot", a.resolve("snapshots"), 400),
                    new DiskSpace.Need("landing", b.resolve("webapp"), 100))))
        .isEmpty();

    List<String> problems =
        DiskSpace.problems(
            files,
            List.of(
                new DiskSpace.Need("staging", a.resolve("runs/r1/staging"), 600),
                new DiskSpace.Need("snapshot", a.resolve("snapshots"), 401),
                new DiskSpace.Need("landing", b.resolve("webapp"), 100)));

    assertThat(problems)
        .singleElement()
        .asString()
        .contains("staging 600")
        .contains("snapshot 401")
        .contains("margin")
        .contains(a.toString());
  }

  @Test
  void should_sum_regular_files_when_measuring_a_tree() throws IOException {
    Path root = Files.createDirectories(tmp.resolve("tree/sub"));
    Files.writeString(root.resolve("a.txt"), "12345", StandardCharsets.UTF_8);
    Files.writeString(root.getParent().resolve("b.txt"), "123", StandardCharsets.UTF_8);

    assertThat(DiskSpace.treeBytes(root.getParent())).isEqualTo(8);
    assertThat(DiskSpace.treeBytes(tmp.resolve("missing"))).isZero();
  }

  /** Field test 3: a full disk is recognised on both platforms, also as a wrapped cause. */
  @Test
  void should_recognise_a_full_disk_when_windows_or_posix_says_so() {
    assertThat(
            DiskSpace.outOfSpace(
                new java.nio.file.FileSystemException(
                    "C:/x", null, "There is not enough space on the disk")))
        .isTrue();
    assertThat(
            DiskSpace.outOfSpace(
                new java.io.UncheckedIOException(
                    new java.io.IOException("write failed: No space left on device"))))
        .isTrue();
    assertThat(DiskSpace.outOfSpace(new java.io.IOException("Access is denied"))).isFalse();
  }
}
