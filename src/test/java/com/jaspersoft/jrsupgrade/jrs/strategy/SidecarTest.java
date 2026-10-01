package com.jaspersoft.jrsupgrade.jrs.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaspersoft.jrsupgrade.core.json.Json;
import com.jaspersoft.jrsupgrade.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsupgrade.jrs.api.ExportRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The sidecar records the key alias an export used, and reads one written before it existed. */
class SidecarTest {

  @TempDir Path tmp;

  private static Sidecar sidecar(Optional<String> keyAlias) {
    return new Sidecar(
        Instant.parse("2026-09-18T00:00:00Z"),
        "http://srv:8080/jasperserver-pro|8.2.0|PRO|MT|1",
        "8.2.0",
        Optional.of("abcd"),
        new Sidecar.Flags(
            ExportRequest.Scope.REPOSITORY,
            List.of("/public"),
            true,
            false,
            false,
            false,
            false,
            false,
            keyAlias),
        "0000",
        ExportImportStrategy.Kind.REST);
  }

  @Test
  void should_round_trip_the_organisation() throws Exception {
    Path file = tmp.resolve("o.zip.jrs-upgrade.json");
    Sidecar base = sidecar(Optional.empty());
    Sidecar.Flags f = base.flags();
    Sidecar.write(
        file,
        new Sidecar(
            base.exportedAt(),
            base.serverIdentity(),
            base.serverVersion(),
            base.keystoreFingerprint(),
            new Sidecar.Flags(
                f.scope(),
                f.uris(),
                f.includeUsersRoles(),
                f.includeAccessEvents(),
                f.includeAuditEvents(),
                f.includeMonitoring(),
                f.includeSettings(),
                f.fullServer(),
                Optional.empty(),
                Optional.of("org1")),
            base.sha256(),
            base.strategy()));

    Optional<Sidecar> read = Sidecar.read(file);

    assertThat(read).isPresent();
    assertThat(read.get().flags().organization()).contains("org1");
  }

  @Test
  void should_round_trip_the_key_alias() throws Exception {
    Path file = tmp.resolve("a.zip.jrs-upgrade.json");
    Sidecar.write(file, sidecar(Optional.of(ExportRequest.PORTABLE_KEY_ALIAS)));

    Optional<Sidecar> read = Sidecar.read(file);

    assertThat(read).isPresent();
    assertThat(read.get().flags().keyAlias()).contains(ExportRequest.PORTABLE_KEY_ALIAS);
    assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("keyAlias");
  }

  @Test
  void should_read_a_sidecar_written_before_the_key_alias_existed() throws Exception {
    Path file = tmp.resolve("old.zip.jrs-upgrade.json");
    Sidecar.write(file, sidecar(Optional.empty()));
    ObjectNode tree =
        (ObjectNode) Json.mapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
    ((ObjectNode) tree.get("flags")).remove("keyAlias");
    Files.writeString(file, Json.write(tree), StandardCharsets.UTF_8);
    assertThat(Files.readString(file, StandardCharsets.UTF_8)).doesNotContain("keyAlias");

    Optional<Sidecar> read = Sidecar.read(file);

    assertThat(read).isPresent();
    assertThat(read.get().flags().keyAlias()).isEmpty();
    assertThat(read.get().flags().uris()).containsExactly("/public");
  }
}
