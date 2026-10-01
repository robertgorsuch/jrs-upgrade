package com.jaspersoft.jrsupgrade.core.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DurabilityTest {

  private static final String ABC_SHA256 =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

  @Test
  void should_write_the_same_bytes_and_hash_when_copying_hashing(@TempDir Path dir)
      throws IOException {
    Path source = dir.resolve("source.txt");
    Files.writeString(source, "abc", StandardCharsets.US_ASCII);
    Path target = dir.resolve("target.txt");

    String hash = Durability.copyHashing(source, target);

    assertThat(hash).isEqualTo(ABC_SHA256);
    assertThat(Files.readString(target, StandardCharsets.US_ASCII)).isEqualTo("abc");
  }

  @Test
  void should_refuse_to_overwrite_when_the_target_already_exists(@TempDir Path dir)
      throws IOException {
    Path source = dir.resolve("source.txt");
    Files.writeString(source, "new", StandardCharsets.US_ASCII);
    Path target = dir.resolve("target.txt");
    Files.writeString(target, "old", StandardCharsets.US_ASCII);

    assertThatThrownBy(() -> Durability.copy(source, target))
        .isInstanceOf(FileAlreadyExistsException.class);
    assertThat(Files.readString(target, StandardCharsets.US_ASCII)).isEqualTo("old");
  }

  @Test
  void should_force_a_file_that_was_written_earlier(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("manifest.json");
    Files.writeString(file, "{}", StandardCharsets.UTF_8);

    assertThatCode(() -> Durability.sync(file)).doesNotThrowAnyException();
    assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("{}");
  }

  @Test
  void should_stay_quiet_when_a_directory_cannot_be_forced(@TempDir Path dir) {
    // Windows refuses to open a directory as a channel; that must never fail a snapshot.
    assertThatCode(() -> Durability.syncDirectory(dir)).doesNotThrowAnyException();
    assertThatCode(() -> Durability.syncDirectory(dir.resolve("absent")))
        .doesNotThrowAnyException();
    assertThatCode(() -> Durability.syncDirectory(null)).doesNotThrowAnyException();
  }
}
