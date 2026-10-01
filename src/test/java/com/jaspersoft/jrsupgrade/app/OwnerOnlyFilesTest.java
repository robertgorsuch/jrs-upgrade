package com.jaspersoft.jrsupgrade.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.platform.OperatorPrompt;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.platform.Platforms;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review finding 4.2: a secret file is restricted before it holds a secret, and a file that is
 * still readable by anyone else never keeps one.
 */
class OwnerOnlyFilesTest {

  private final Platform platform = Platforms.detect(OperatorPrompt.nonInteractive());

  /**
   * A home jrs-upgrade created hands its owner's entry down to every file. Restricting a file there
   * set the same entry explicitly, which Windows folds into the inherited one, and then removed the
   * inherited entries, leaving an empty access list: the owner could no longer write the secret.
   */
  @Test
  void should_keep_the_owner_able_to_write_when_the_directory_was_restricted_by_cli(
      @TempDir Path dir) throws IOException {
    Path home = Files.createDirectories(dir.resolve("home"));
    OwnerOnlyFiles.restrictDirectoryToOwner(platform, home);

    Path token = home.resolve("console.token");
    OwnerOnlyFiles.write(platform, token, "t0k3n");
    Path temp = Files.createTempFile(home, "secrets", ".tmp");
    OwnerOnlyFiles.restrictToOwner(platform, temp);
    Files.writeString(temp, "ciphertext", StandardCharsets.UTF_8);

    assertThat(Files.readString(token, StandardCharsets.UTF_8)).isEqualTo("t0k3n");
    assertThat(platform.files().isOwnerOnly(token)).isTrue();
    assertThat(Files.readString(temp, StandardCharsets.UTF_8)).isEqualTo("ciphertext");
    assertThat(platform.files().isOwnerOnly(temp)).isTrue();
  }

  @Test
  void should_write_a_file_only_its_owner_can_read(@TempDir Path dir) throws IOException {
    Path secret = dir.resolve("nested").resolve("key.pem");

    OwnerOnlyFiles.write(platform, secret, "s3cret\n");

    assertThat(Files.readString(secret, StandardCharsets.UTF_8)).isEqualTo("s3cret\n");
    assertThat(platform.files().isOwnerOnly(secret)).isTrue();
  }

  @Test
  void should_restrict_the_file_before_any_content_exists(@TempDir Path dir) throws IOException {
    Path secret = dir.resolve("observed.pem");
    RecordingPlatform recorder = new RecordingPlatform(platform, secret);

    OwnerOnlyFiles.write(recorder, secret, "s3cret\n");

    assertThat(recorder.sizeWhenRestricted)
        .as("the file must be empty when its permissions are applied")
        .isZero();
  }

  @Test
  void should_refuse_to_overwrite_an_existing_file(@TempDir Path dir) throws IOException {
    Path secret = dir.resolve("key.pem");
    OwnerOnlyFiles.write(platform, secret, "first\n");

    assertThatThrownBy(() -> OwnerOnlyFiles.write(platform, secret, "second\n"))
        .isInstanceOf(FileAlreadyExistsException.class);
    assertThat(Files.readString(secret, StandardCharsets.UTF_8)).isEqualTo("first\n");
  }

  @Test
  void should_delete_the_file_when_it_cannot_be_made_owner_only(@TempDir Path dir) {
    Path secret = dir.resolve("wide-open.pem");
    Platform lying = new LyingPlatform(platform);

    assertThatThrownBy(() -> OwnerOnlyFiles.write(lying, secret, "s3cret\n"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("still readable");
    assertThat(secret).doesNotExist();
  }

  /** Delegates to the real platform but records the file size when permissions are applied. */
  private static final class RecordingPlatform extends DelegatingPlatform {
    private final Path watched;
    private long sizeWhenRestricted = -1;

    RecordingPlatform(Platform delegate, Path watched) {
      super(delegate);
      this.watched = watched;
    }

    @Override
    public com.jaspersoft.jrsupgrade.core.platform.FileOps files() {
      com.jaspersoft.jrsupgrade.core.platform.FileOps real = super.files();
      return new DelegatingFileOps(real) {
        @Override
        public void applyPermissions(Path path, Permissions permissions) throws IOException {
          if (path.equals(watched.toAbsolutePath().normalize())) {
            sizeWhenRestricted = Files.size(path);
          }
          real.applyPermissions(path, permissions);
        }
      };
    }
  }

  /** Applies permissions but always reports the file as world-readable. */
  private static final class LyingPlatform extends DelegatingPlatform {
    LyingPlatform(Platform delegate) {
      super(delegate);
    }

    @Override
    public com.jaspersoft.jrsupgrade.core.platform.FileOps files() {
      com.jaspersoft.jrsupgrade.core.platform.FileOps real = super.files();
      return new DelegatingFileOps(real) {
        @Override
        public boolean isOwnerOnly(Path file) {
          return false;
        }
      };
    }
  }
}
