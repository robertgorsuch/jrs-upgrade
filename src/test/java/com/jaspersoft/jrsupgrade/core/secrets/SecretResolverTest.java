package com.jaspersoft.jrsupgrade.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jaspersoft.jrsupgrade.core.platform.FileOps;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretResolverTest {

  @TempDir Path tmp;

  private final FileOps fileOps = mock(FileOps.class);

  private SecretResolver resolver(Map<String, String> env) {
    EncryptedSecretStore store =
        new EncryptedSecretStore(
            tmp.resolve("secrets.enc"),
            new PassphraseSource.Fixed(Secret.fromString("pp")),
            "host");
    return new SecretResolver(env, fileOps, store);
  }

  /** Field test 2, D1: a check that must not prompt asks this first. */
  @Test
  void should_say_whether_a_reference_resolves_without_a_prompt() throws IOException {
    Path present = Files.writeString(tmp.resolve("s"), "x", StandardCharsets.UTF_8);
    SecretResolver withEnv = resolver(Map.of("JRS_PASSWORD", "hunter2"));
    SecretResolver withoutEnv = resolver(Map.of());
    EncryptedSecretStore consoleOnly =
        new EncryptedSecretStore(
            tmp.resolve("secrets.enc"), new PassphraseSource.FromConsole(), "host");
    SecretResolver promptingStore = new SecretResolver(Map.of(), fileOps, consoleOnly);

    assertThat(withEnv.availableWithoutPrompt(SecretRef.parse("env:JRS_PASSWORD"))).isTrue();
    assertThat(withoutEnv.availableWithoutPrompt(SecretRef.parse("env:JRS_PASSWORD"))).isFalse();
    assertThat(withoutEnv.availableWithoutPrompt(new SecretRef.File(present))).isTrue();
    assertThat(withoutEnv.availableWithoutPrompt(new SecretRef.File(tmp.resolve("gone"))))
        .isFalse();
    assertThat(withoutEnv.availableWithoutPrompt(SecretRef.parse("enc:JRS_PASSWORD")))
        .as("a fixed passphrase unlocks the store without a prompt")
        .isTrue();
    assertThat(promptingStore.availableWithoutPrompt(SecretRef.parse("enc:JRS_PASSWORD")))
        .as("a console-only passphrase source would prompt")
        .isFalse();
  }

  @Test
  void should_read_environment_when_ref_is_env() {
    try (Secret s =
        resolver(Map.of("JRS_PASSWORD", "hunter2")).resolve(SecretRef.parse("env:JRS_PASSWORD"))) {
      assertThat(new String(s.chars())).isEqualTo("hunter2");
    }
  }

  @Test
  void should_name_variable_when_env_is_unset() {
    SecretResolver r = resolver(Map.of());

    assertThatThrownBy(() -> r.resolve(new SecretRef.Env("JRS_PASSWORD")))
        .isInstanceOf(SecretException.class)
        .hasMessageContaining("JRS_PASSWORD is not set");
  }

  @Test
  void should_read_owner_only_file_when_ref_is_file() throws IOException {
    Path f = tmp.resolve("db.pw");
    Files.writeString(f, "hunter2\n", StandardCharsets.UTF_8);
    when(fileOps.isOwnerOnly(f)).thenReturn(true);

    try (Secret s = resolver(Map.of()).resolve(new SecretRef.File(f))) {
      assertThat(new String(s.chars())).isEqualTo("hunter2");
    }
  }

  @Test
  void should_refuse_file_when_it_is_readable_by_others() throws IOException {
    Path f = tmp.resolve("db.pw");
    Files.writeString(f, "hunter2", StandardCharsets.UTF_8);
    when(fileOps.isOwnerOnly(f)).thenReturn(false);
    SecretResolver r = resolver(Map.of());

    assertThatThrownBy(() -> r.resolve(new SecretRef.File(f)))
        .isInstanceOf(SecretException.class)
        .hasMessageContaining("readable by other users")
        .hasMessageContaining("chmod 600")
        .satisfies(t -> assertThat(t.getMessage()).doesNotContain("hunter2"));
  }

  @Test
  void should_fail_when_secret_file_is_missing_or_empty() throws IOException {
    when(fileOps.isOwnerOnly(any())).thenReturn(true);
    SecretResolver r = resolver(Map.of());
    Path missing = tmp.resolve("missing");
    Path empty = tmp.resolve("empty");
    Files.writeString(empty, "\n", StandardCharsets.UTF_8);

    assertThatThrownBy(() -> r.resolve(new SecretRef.File(missing)))
        .isInstanceOf(SecretException.class)
        .hasMessageContaining("does not exist");
    assertThatThrownBy(() -> r.resolve(new SecretRef.File(empty)))
        .isInstanceOf(SecretException.class)
        .hasMessageContaining("is empty");
  }

  @Test
  void should_delegate_to_store_when_ref_is_enc() {
    EncryptedSecretStore store =
        new EncryptedSecretStore(
            tmp.resolve("secrets.enc"),
            new PassphraseSource.Fixed(Secret.fromString("pp")),
            "host");
    store.init();
    try (Secret v = Secret.fromString("from-store")) {
      store.set("db", v);
    }
    SecretResolver r = new SecretResolver(Map.of(), fileOps, store);

    try (Secret s = r.resolve(new SecretRef.Enc("db"))) {
      assertThat(new String(s.chars())).isEqualTo("from-store");
    }
    assertThatThrownBy(() -> r.resolve(new SecretRef.Enc("other")))
        .isInstanceOf(SecretException.class)
        .hasMessageContaining("jrs-upgrade secrets set other");
  }
}
