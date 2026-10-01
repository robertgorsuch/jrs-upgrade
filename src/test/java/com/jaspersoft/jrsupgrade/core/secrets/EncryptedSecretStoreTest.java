package com.jaspersoft.jrsupgrade.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptedSecretStoreTest {

  @TempDir Path tmp;

  private static PassphraseSource passphrase(String text) {
    return new PassphraseSource.Fixed(Secret.fromString(text));
  }

  private EncryptedSecretStore store(String passphrase, String machine) {
    return new EncryptedSecretStore(tmp.resolve("secrets.enc"), passphrase(passphrase), machine);
  }

  private EncryptedSecretStore store(String passphrase, String machine, String legacyMachine) {
    return new EncryptedSecretStore(
        tmp.resolve("secrets.enc"), passphrase(passphrase), machine, Optional.of(legacyMachine));
  }

  /** Rewrites the file's version field, everything else being layout-identical across versions. */
  private void stampVersion(Path file, int version) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode root;
    try (InputStream in = Files.newInputStream(file)) {
      root = (ObjectNode) mapper.readTree(in);
    }
    root.put("version", version);
    Files.writeString(file, mapper.writeValueAsString(root), StandardCharsets.UTF_8);
  }

  private static int versionOf(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      return new ObjectMapper().readTree(in).get("version").asInt();
    }
  }

  @Test
  void should_unlock_a_version_1_store_with_the_legacy_host_name_and_rewrite_it_as_version_2()
      throws IOException {
    // a store created before the machine-id change: salted with the DNS host name, version 1
    EncryptedSecretStore legacy = store("pp", "old-dns-name");
    legacy.init();
    try (Secret value = Secret.fromString("db-pass")) {
      legacy.set("db", value);
    }
    stampVersion(legacy.file(), 1);

    EncryptedSecretStore current = store("pp", "machine-id-abc", "old-dns-name");
    try (Secret got = current.get("db").orElseThrow()) {
      assertThat(new String(got.chars())).isEqualTo("db-pass");
    }

    assertThat(versionOf(current.file())).as("rewritten in place").isEqualTo(2);
    try (Secret again = store("pp", "machine-id-abc").get("db").orElseThrow()) {
      assertThat(new String(again.chars())).as("now bound to the machine id").isEqualTo("db-pass");
    }
    assertThatThrownBy(() -> store("pp", "old-dns-name").get("db"))
        .as("the legacy identity no longer unlocks it")
        .isInstanceOf(SecretException.class);
  }

  @Test
  void should_report_wrong_passphrase_when_neither_identity_unlocks_a_version_1_store()
      throws IOException {
    EncryptedSecretStore legacy = store("hunter2-xyz", "some-other-host");
    legacy.init();
    stampVersion(legacy.file(), 1);

    assertThatCode(() -> store("hunter2-xyz", "machine-id-abc", "old-dns-name").list())
        .as("listing needs no passphrase")
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> store("hunter2-xyz", "machine-id-abc", "old-dns-name").get("db"))
        .isInstanceOf(SecretException.class)
        .hasMessageContaining("passphrase does not unlock")
        .hasMessageNotContaining("hunter2");
    assertThat(versionOf(tmp.resolve("secrets.enc"))).as("left untouched").isEqualTo(1);
  }

  @Test
  void should_never_try_the_legacy_identity_on_a_version_2_store() {
    EncryptedSecretStore other = store("pp", "host-a");
    other.init();

    assertThatThrownBy(() -> store("pp", "host-b", "host-a").get("db"))
        .as("a version 2 store stays bound to the machine it was created on")
        .isInstanceOf(SecretException.class)
        .hasMessageContaining("passphrase does not unlock");
  }

  @Test
  void should_refuse_a_store_from_a_newer_build() throws IOException {
    EncryptedSecretStore store = store("pp", "host-a");
    store.init();
    stampVersion(store.file(), 3);

    assertThatThrownBy(() -> store.list())
        .isInstanceOf(SecretException.class)
        .hasMessageContaining("version 3");
  }

  @Test
  void should_write_documented_layout_when_initialised() throws IOException {
    EncryptedSecretStore store = store("pp", "host-a");
    store.init();

    JsonNode root;
    try (InputStream in = Files.newInputStream(store.file())) {
      root = new ObjectMapper().readTree(in);
    }
    assertThat(root.get("version").asInt()).isEqualTo(2);
    assertThat(root.get("kdf").asText()).isEqualTo("PBKDF2WithHmacSHA256");
    assertThat(root.get("iterations").asInt()).isEqualTo(600_000);
    assertThat(root.get("salt").asText()).isNotBlank();
    assertThat(root.get("entries").isObject()).isTrue();
    assertThat(store.list()).isEmpty();
  }

  @Test
  void should_round_trip_set_get_list_remove_when_passphrase_matches() {
    EncryptedSecretStore store = store("pp", "host-a");
    store.init();

    try (Secret value = Secret.fromString("s3cr3t-value")) {
      store.set("db", value);
    }
    assertThat(store.list()).containsExactly("db");

    try (Secret got = store.get("db").orElseThrow()) {
      assertThat(new String(got.chars())).isEqualTo("s3cr3t-value");
    }
    assertThat(store.get("missing")).isEmpty();
    assertThat(store.remove("db")).isTrue();
    assertThat(store.remove("db")).isFalse();
    assertThat(store.list()).isEmpty();

    String raw;
    try {
      raw = Files.readString(store.file(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    assertThat(raw).doesNotContain("s3cr3t-value");
  }

  @Test
  void should_keep_ciphertext_out_of_file_when_secret_is_stored() throws IOException {
    EncryptedSecretStore store = store("pp", "host-a");
    store.init();
    try (Secret value = Secret.fromString("plain-text-marker")) {
      store.set("db", value);
    }

    assertThat(Files.readString(store.file(), StandardCharsets.UTF_8))
        .doesNotContain("plain-text-marker")
        .doesNotContain("pp\"");
  }

  @Test
  void should_reject_wrong_passphrase_without_revealing_it_when_unlocking() {
    store("alpha-pass-1", "host-a").init();
    EncryptedSecretStore wrong = store("beta-pass-2", "host-a");

    assertThatThrownBy(() -> wrong.get("db"))
        .isInstanceOf(SecretException.class)
        .hasMessageContaining("passphrase does not unlock")
        .satisfies(
            t ->
                assertThat(t.getMessage())
                    .doesNotContain("alpha-pass-1")
                    .doesNotContain("beta-pass-2"));
  }

  @Test
  void should_refuse_to_unlock_when_store_is_moved_to_another_machine() {
    EncryptedSecretStore origin = store("pp", "host-a");
    origin.init();
    try (Secret value = Secret.fromString("value")) {
      origin.set("db", value);
    }
    EncryptedSecretStore elsewhere = store("pp", "host-b");

    assertThatThrownBy(() -> elsewhere.get("db")).isInstanceOf(SecretException.class);
  }

  @Test
  void should_refuse_to_overwrite_when_already_initialised() {
    EncryptedSecretStore store = store("pp", "host-a");
    store.init();

    assertThatThrownBy(store::init)
        .isInstanceOf(SecretException.class)
        .hasMessageContaining("already exists");
  }

  @Test
  void should_point_at_secrets_init_when_file_is_missing() {
    EncryptedSecretStore store = store("pp", "host-a");

    assertThatThrownBy(store::list)
        .isInstanceOf(SecretException.class)
        .hasMessageContaining("jrs-upgrade secrets init");
  }

  @Test
  void should_reject_invalid_entry_names_when_setting() {
    EncryptedSecretStore store = store("pp", "host-a");
    store.init();

    try (Secret value = Secret.fromString("v")) {
      assertThatThrownBy(() -> store.set("has space", value))
          .isInstanceOf(SecretException.class)
          .hasMessageContaining("invalid secret name");
    }
  }

  /**
   * The platform supplies the owner-only restriction; the store applies it before it writes (S6).
   */
  @Test
  void should_restrict_the_file_through_the_platform_before_writing_when_a_restrictor_is_given() {
    java.util.List<Path> restricted = new java.util.ArrayList<>();
    EncryptedSecretStore store =
        new EncryptedSecretStore(
            tmp.resolve("restricted.enc"),
            passphrase("pp"),
            "host",
            Optional.empty(),
            restricted::add);

    store.init();

    assertThat(restricted).isNotEmpty();
    assertThat(restricted).allSatisfy(p -> assertThat(p.getParent()).isEqualTo(tmp));
    assertThat(tmp.resolve("restricted.enc")).exists();
  }

  @Test
  void should_not_need_passphrase_when_listing_or_removing() {
    store("pp", "host-a").init();
    EncryptedSecretStore noPassphrase =
        new EncryptedSecretStore(
            tmp.resolve("secrets.enc"),
            PassphraseSource.standard(
                java.util.Map.of(),
                java.util.Optional.empty(),
                org.mockito.Mockito.mock(com.jaspersoft.jrsupgrade.core.platform.FileOps.class)),
            "host-a");

    assertThat(noPassphrase.list()).isEmpty();
    assertThat(noPassphrase.remove("nothing")).isFalse();
    assertThatThrownBy(() -> noPassphrase.get("x"))
        .isInstanceOf(PassphraseUnavailableException.class);
  }
}
