package com.jaspersoft.jrsupgrade.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class TreesTest {

  @TempDir Path tmp;

  @Test
  void should_delete_a_tree_and_ignore_a_missing_one() throws IOException {
    Path root = Files.createDirectories(tmp.resolve("tree/sub"));
    Files.writeString(root.resolve("a.txt"), "a", StandardCharsets.UTF_8);
    Files.writeString(root.getParent().resolve("b.txt"), "b", StandardCharsets.UTF_8);

    Trees.deleteRecursively(root.getParent());
    Trees.deleteRecursively(root.getParent());

    assertThat(root.getParent()).doesNotExist();
  }

  /**
   * Review finding 1.19: Windows refuses to delete a file carrying the read-only attribute, which
   * vendor archives set on some files, so a staging or snapshot tree could never be removed. The
   * attribute is cleared and the delete retried.
   */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void should_delete_a_read_only_file_when_deleting_a_tree_on_windows() throws IOException {
    Path root = Files.createDirectories(tmp.resolve("tree/lib"));
    Path jar = Files.writeString(root.resolve("vendor.jar"), "bytes", StandardCharsets.UTF_8);
    Files.setAttribute(jar, "dos:readonly", true);

    Trees.deleteRecursively(root.getParent());

    assertThat(root.getParent()).doesNotExist();
  }
}
