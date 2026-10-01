package com.jaspersoft.jrsupgrade.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jaspersoft.jrsupgrade.core.platform.FileOps;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PassphraseSourceTest {

  /** A file system on which every file is owner-only; the refusal test builds its own. */
  private final FileOps files = ownerOnly(true);

  private static FileOps ownerOnly(boolean value) {
    FileOps files = mock(FileOps.class);
    try {
      when(files.isOwnerOnly(any())).thenReturn(value);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return files;
  }

  @TempDir Path tmp;

  /** Field test 2, D1: doctor asks this before touching the store, so it never prompts. */
  @Test
  void should_say_whether_a_passphrase_is_available_without_a_prompt() throws IOException {
    Path file = Files.writeString(tmp.resolve("pp"), "pp\n", StandardCharsets.UTF_8);

    assertThat(
            new PassphraseSource.FromEnv(Map.of("JRS_UPGRADE_PASSPHRASE", "pp"))
                .availableWithoutPrompt())
        .isTrue();
    assertThat(new PassphraseSource.FromEnv(Map.of()).availableWithoutPrompt()).isFalse();
    assertThat(new PassphraseSource.FromFile(file, files).availableWithoutPrompt()).isTrue();
    assertThat(
            new PassphraseSource.FromFile(tmp.resolve("missing"), files).availableWithoutPrompt())
        .isFalse();
    assertThat(new PassphraseSource.FromConsole().availableWithoutPrompt()).isFalse();
    assertThat(new PassphraseSource.Fixed(Secret.fromString("pp")).availableWithoutPrompt())
        .isTrue();
    assertThat(
            new PassphraseSource.Chain(
                    List.of(
                        new PassphraseSource.FromEnv(Map.of()), new PassphraseSource.FromConsole()))
                .availableWithoutPrompt())
        .isFalse();
    assertThat(
            new PassphraseSource.Chain(
                    List.of(
                        new PassphraseSource.FromConsole(),
                        new PassphraseSource.FromFile(file, files)))
                .availableWithoutPrompt())
        .isTrue();
  }

  @Test
  void should_read_env_var_when_jrsupgrade_passphrase_is_set() {
    try (Secret s =
        new PassphraseSource.FromEnv(Map.of("JRS_UPGRADE_PASSPHRASE", "pp")).require()) {
      assertThat(s.chars()).containsExactly('p', 'p');
    }
    assertThat(new PassphraseSource.FromEnv(Map.of()).read()).isEmpty();
    assertThat(new PassphraseSource.FromEnv(Map.of("JRS_UPGRADE_PASSPHRASE", "")).read()).isEmpty();
  }

  @Test
  void should_strip_trailing_newline_when_reading_passphrase_file() throws IOException {
    Path f = tmp.resolve("pp");
    Files.writeString(f, "correct horse\r\n", StandardCharsets.UTF_8);

    try (Secret s = new PassphraseSource.FromFile(f, files).require()) {
      assertThat(new String(s.chars())).isEqualTo("correct horse");
    }
  }

  @Test
  void should_fail_with_path_when_passphrase_file_is_missing() {
    Path missing = tmp.resolve("nope");

    assertThatThrownBy(() -> new PassphraseSource.FromFile(missing, files).read())
        .isInstanceOf(SecretException.class)
        .hasMessageContaining("nope");
  }

  @Test
  void should_refuse_passphrase_file_when_readable_by_other_users() throws IOException {
    Path f = tmp.resolve("pp");
    Files.writeString(f, "correct horse", StandardCharsets.UTF_8);

    assertThatThrownBy(() -> new PassphraseSource.FromFile(f, ownerOnly(false)).read())
        .isInstanceOf(SecretException.class)
        .hasMessageContaining("readable by other users")
        .hasMessageContaining("chmod 600")
        .satisfies(t -> assertThat(t.getMessage()).doesNotContain("correct horse"));
  }

  @Test
  void should_take_first_available_source_when_chained() {
    PassphraseSource chain =
        new PassphraseSource.Chain(
            List.of(
                new PassphraseSource.FromEnv(Map.of()),
                new PassphraseSource.Fixed(Secret.fromString("second")),
                new PassphraseSource.Fixed(Secret.fromString("third"))));

    try (Secret s = chain.require()) {
      assertThat(new String(s.chars())).isEqualTo("second");
    }
  }

  @Test
  void should_explain_non_interactive_options_when_no_source_is_available() {
    PassphraseSource standard = PassphraseSource.standard(Map.of(), Optional.empty(), files);

    assertThat(standard.read()).isEmpty();
    assertThatThrownBy(standard::require)
        .isInstanceOf(PassphraseUnavailableException.class)
        .hasMessageContaining(
            "set JRS_UPGRADE_PASSPHRASE or pass --passphrase-file for non-interactive runs");
  }

  @Test
  void should_prefer_env_over_file_when_both_are_given() throws IOException {
    Path f = tmp.resolve("pp");
    Files.writeString(f, "from-file", StandardCharsets.UTF_8);
    PassphraseSource standard =
        PassphraseSource.standard(
            Map.of("JRS_UPGRADE_PASSPHRASE", "from-env"), Optional.of(f), files);

    try (Secret s = standard.require()) {
      assertThat(new String(s.chars())).isEqualTo("from-env");
    }
  }
}
