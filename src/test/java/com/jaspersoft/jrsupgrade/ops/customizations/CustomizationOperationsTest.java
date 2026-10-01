package com.jaspersoft.jrsupgrade.ops.customizations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsupgrade.core.state.Customization;
import com.jaspersoft.jrsupgrade.core.state.SnapshotRecord;
import com.jaspersoft.jrsupgrade.ops.FakeLayout;
import com.jaspersoft.jrsupgrade.ops.FakeServices;
import com.jaspersoft.jrsupgrade.ops.Services;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CustomizationOperationsTest {

  @TempDir Path tmp;

  private FakeServices fake;
  private Path installDir;

  private DefaultCustomizationOperations ops() throws Exception {
    installDir = FakeLayout.linux(Files.createDirectories(tmp.resolve("jrs")));
    fake = FakeServices.in(tmp.resolve("home"));
    fake.platform.realFiles = true;
    fake.yaml(
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
          webappName: jasperserver-pro
          installDir: %s
          tomcatDir: %s
        service:
          kind: manual
        """
            .formatted(slashes(installDir), slashes(installDir.resolve("apache-tomcat"))));
    Services services = fake.build();
    return new DefaultCustomizationOperations(
        services, new SnapshotStore(fake.home, services.platform().files(), fake.clock));
  }

  private static String slashes(Path p) {
    return p.toAbsolutePath().toString().replace('\\', '/');
  }

  private Path customized() throws Exception {
    Path file =
        installDir
            .resolve("apache-tomcat")
            .resolve("webapps")
            .resolve("jasperserver-pro")
            .resolve("WEB-INF")
            .resolve("classes")
            .resolve("jasperserver.properties");
    Files.writeString(file, "a=1\nb=2\n", StandardCharsets.UTF_8);
    return file;
  }

  @Test
  void should_snapshot_and_record_original_hash_when_registering() throws Exception {
    DefaultCustomizationOperations ops = ops();
    try (FakeServices unused = fake) {
      Path file = customized();

      Customization c = ops.register(file);

      assertThat(c.path()).isEqualTo(file.toAbsolutePath().normalize());
      assertThat(c.originalSha256()).isEqualTo(fake.platform.files().sha256(file));
      String runId = DefaultCustomizationOperations.runIdFor(file);
      assertThat(runId).startsWith("cust-").hasSize(21);
      assertThat(c.snapshotRef()).contains(runId + "/file");
      assertThat(fake.home.snapshots().resolve(runId).resolve("file").resolve("manifest.json"))
          .exists();
      List<Customization> listed = ops.list();
      assertThat(listed).hasSize(1);
      Optional<SnapshotRecord> row = fake.stateStore().snapshot(runId + "/file");
      assertThat(row).isPresent();
      assertThat(row.get().referencedBy()).contains("customization");
      assertThat(fake.stateStore().auditRows(5))
          .anyMatch(a -> a.action().equals("customizations.registered"));
    }
  }

  @Test
  void should_refuse_when_file_is_outside_the_installation() throws Exception {
    DefaultCustomizationOperations ops = ops();
    try (FakeServices unused = fake) {
      Path outside = tmp.resolve("outside.txt");
      Files.writeString(outside, "x", StandardCharsets.UTF_8);

      assertThatThrownBy(() -> ops.register(outside))
          .isInstanceOf(CustomizationException.class)
          .hasMessageContaining("outside the installation");
      assertThat(ops.list()).isEmpty();
    }
  }

  @Test
  void should_refuse_when_already_registered() throws Exception {
    DefaultCustomizationOperations ops = ops();
    try (FakeServices unused = fake) {
      Path file = customized();
      ops.register(file);

      assertThatThrownBy(() -> ops.register(file))
          .isInstanceOf(CustomizationException.class)
          .hasMessageContaining("already registered");
    }
  }

  @Test
  void should_record_pristine_hash_as_original_when_given() throws Exception {
    DefaultCustomizationOperations ops = ops();
    try (FakeServices unused = fake) {
      Path file = customized();
      Path pristine = tmp.resolve("pristine.properties");
      Files.writeString(pristine, "a=1\n", StandardCharsets.UTF_8);

      Customization c = ops.register(file, Optional.of(pristine));

      assertThat(c.originalSha256()).isEqualTo(fake.platform.files().sha256(pristine));
      CustomizationOperations.Diff diff = ops.diff(file);
      assertThat(diff.identical()).isTrue();
      assertThat(diff.registeredSha256()).isEqualTo(fake.platform.files().sha256(file));
    }
  }

  @Test
  void should_report_unified_diff_when_file_changed_since_registration() throws Exception {
    DefaultCustomizationOperations ops = ops();
    try (FakeServices unused = fake) {
      Path file = customized();
      ops.register(file);
      assertThat(ops.diff(file).identical()).isTrue();
      Files.writeString(file, "a=1\nb=3\n", StandardCharsets.UTF_8);

      CustomizationOperations.Diff diff = ops.diff(file);

      assertThat(diff.identical()).isFalse();
      assertThat(diff.lines()).contains("-b=2", "+b=3");
      assertThat(diff.lines().get(0)).startsWith("--- registered:");
      assertThat(diff.lines().get(1)).startsWith("+++ current:");
    }
  }

  @Test
  void should_remove_row_and_snapshot_when_unregistering() throws Exception {
    DefaultCustomizationOperations ops = ops();
    try (FakeServices unused = fake) {
      Path file = customized();
      ops.register(file);
      String runId = DefaultCustomizationOperations.runIdFor(file);

      assertThat(ops.unregister(file)).isTrue();

      assertThat(ops.list()).isEmpty();
      assertThat(fake.home.snapshots().resolve(runId)).doesNotExist();
      assertThat(fake.stateStore().snapshot(runId + "/file")).isEmpty();
      assertThat(ops.unregister(file)).isFalse();
      assertThatThrownBy(() -> ops.diff(file))
          .isInstanceOf(CustomizationException.class)
          .hasMessageContaining("not registered");
    }
  }
}
