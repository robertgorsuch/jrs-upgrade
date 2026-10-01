package com.jaspersoft.jrsupgrade.core.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.platform.DiskSpace;
import com.jaspersoft.jrsupgrade.core.platform.FileOps;
import com.jaspersoft.jrsupgrade.core.platform.Platforms;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class SnapshotStoreTest {

  /** A clock the test moves by hand so snapshots get distinct, controllable timestamps. */
  private static final class ManualClock extends Clock {
    private Instant now = Instant.parse("2026-09-08T10:00:00Z");

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @TempDir Path homeDir;
  @TempDir Path install;

  private final FileOps files = Platforms.detect().files();
  private final ManualClock clock = new ManualClock();
  private SnapshotStore store;
  private Path jar;
  private Path props;

  @BeforeEach
  void setUp() throws IOException {
    store = new SnapshotStore(new JrsUpgradeHome(homeDir), files, clock);
    Path lib = install.resolve("apache-tomcat").resolve("webapps/jasperserver-pro/WEB-INF/lib");
    Files.createDirectories(lib);
    jar = lib.resolve("jasperserver-api.jar");
    Files.writeString(jar, "original jar bytes", StandardCharsets.UTF_8);
    props = install.resolve("buildomatic").resolve("default_master.properties");
    Files.createDirectories(props.getParent());
    Files.writeString(props, "dbType=postgresql", StandardCharsets.UTF_8);
  }

  @Test
  void should_write_manifest_and_payload_when_creating() throws IOException {
    Snapshot snapshot = store.create("run-1", "step-a", List.of(jar, props), install);

    assertThat(snapshot.dir()).isEqualTo(homeDir.resolve("snapshots/run-1/step-a"));
    assertThat(snapshot.manifestFile()).isRegularFile();
    assertThat(snapshot.manifest().createdAt()).isEqualTo(clock.instant());
    assertThat(snapshot.manifest().baseDir()).isEqualTo(install.toAbsolutePath().normalize());
    assertThat(snapshot.manifest().entries())
        .extracting(SnapshotManifest.Entry::path)
        .containsExactly(
            "apache-tomcat/webapps/jasperserver-pro/WEB-INF/lib/jasperserver-api.jar",
            "buildomatic/default_master.properties");
    SnapshotManifest.Entry jarEntry = snapshot.manifest().entries().get(0);
    assertThat(jarEntry.sha256()).isEqualTo(files.sha256(jar));
    assertThat(jarEntry.size()).isEqualTo(Files.size(jar));
    assertThat(jarEntry.permissions().owner()).isEqualTo(files.capturePermissions(jar).owner());
    assertThat(snapshot.payloadFile(jarEntry)).hasSameTextualContentAs(jar);
    assertThat(snapshot.manifest().totalSize()).isEqualTo(Files.size(jar) + Files.size(props));
    assertThat(Files.readString(snapshot.manifestFile(), StandardCharsets.UTF_8))
        .contains("\"runId\" : \"run-1\"")
        .contains("\"sha256\"")
        .doesNotContain("file:/");
  }

  /**
   * Review finding 1.16: the store never asked how much room the snapshot volume had, so a large
   * snapshot could fill the disk half-way through the copy.
   */
  @Test
  void should_refuse_to_create_when_the_snapshot_volume_is_short_of_space() throws IOException {
    long needed = Files.size(jar) + Files.size(props);
    JrsUpgradeHome home = new JrsUpgradeHome(homeDir);
    SnapshotStore starved =
        new SnapshotStore(home, new ShortVolume(files, needed - 1 + DiskSpace.MARGIN_BYTES), clock);

    assertThatThrownBy(() -> starved.create("r1", "snapshot", List.of(jar, props), install))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("free");
    assertThat(home.snapshots().resolve("r1")).doesNotExist();

    SnapshotStore fed =
        new SnapshotStore(home, new ShortVolume(files, needed + DiskSpace.MARGIN_BYTES), clock);
    assertThat(fed.create("r1", "snapshot", List.of(jar, props), install).manifestFile()).exists();
  }

  /** Real file operations with a fixed answer for free space. */
  private record ShortVolume(FileOps real, long free) implements FileOps {
    @Override
    public String sha256(Path file) throws IOException {
      return real.sha256(file);
    }

    @Override
    public void atomicReplace(Path source, Path target) throws IOException {
      real.atomicReplace(source, target);
    }

    @Override
    public void copyPreserving(Path source, Path target) throws IOException {
      real.copyPreserving(source, target);
    }

    @Override
    public boolean isLocked(Path file) {
      return real.isLocked(file);
    }

    @Override
    public Optional<String> lockHolder(Path file) {
      return real.lockHolder(file);
    }

    @Override
    public Optional<String> lockInspectionLimit() {
      return real.lockInspectionLimit();
    }

    @Override
    public Permissions capturePermissions(Path path) throws IOException {
      return real.capturePermissions(path);
    }

    @Override
    public void applyPermissions(Path path, Permissions permissions) throws IOException {
      real.applyPermissions(path, permissions);
    }

    @Override
    public long freeSpaceBytes(Path anyPathOnVolume) {
      return free;
    }

    @Override
    public String volumeId(Path anyPathOnVolume) {
      return "short";
    }

    @Override
    public boolean isWritable(Path dir) {
      return real.isWritable(dir);
    }

    @Override
    public boolean isOwnerOnly(Path file) throws IOException {
      return real.isOwnerOnly(file);
    }
  }

  @Test
  void should_throw_listing_mismatch_when_payload_was_tampered() throws IOException {
    Snapshot snapshot = store.create("run-1", "step-a", List.of(jar, props), install);
    Path payloadJar = snapshot.payloadFile(snapshot.manifest().entries().get(0));
    Files.writeString(payloadJar, "tampered jar bytes", StandardCharsets.UTF_8);

    assertThatThrownBy(() -> store.verify(snapshot))
        .isInstanceOf(SnapshotCorruptException.class)
        .hasMessageContaining("run-1/step-a")
        .hasMessageContaining("jasperserver-api.jar")
        .satisfies(
            e ->
                assertThat(((SnapshotCorruptException) e).mismatches())
                    .hasSize(1)
                    .allMatch(m -> m.contains("sha256")));
    assertThatThrownBy(() -> store.restore(snapshot)).isInstanceOf(SnapshotCorruptException.class);
    assertThat(Files.readString(jar, StandardCharsets.UTF_8)).isEqualTo("original jar bytes");
  }

  @Test
  void should_report_missing_payload_when_verifying_after_deletion() throws IOException {
    Snapshot snapshot = store.create("run-1", "step-a", List.of(props), install);
    Files.delete(snapshot.payloadFile(snapshot.manifest().entries().get(0)));

    assertThatThrownBy(() -> store.verify(snapshot))
        .isInstanceOf(SnapshotCorruptException.class)
        .hasMessageContaining("payload missing");
  }

  @Test
  void should_bring_original_hash_back_when_restoring_after_modification() throws IOException {
    String originalHash = files.sha256(jar);
    Snapshot snapshot = store.create("run-1", "step-a", List.of(jar, props), install);
    Files.writeString(jar, "hotfixed jar bytes that are longer", StandardCharsets.UTF_8);
    Files.delete(props);

    store.restore(snapshot);

    assertThat(files.sha256(jar)).isEqualTo(originalHash);
    assertThat(Files.readString(props, StandardCharsets.UTF_8)).isEqualTo("dbType=postgresql");
    assertThat(files.capturePermissions(jar).owner())
        .isEqualTo(snapshot.manifest().entries().get(0).permissions().owner());
    try (var siblings = Files.list(jar.getParent())) {
      assertThat(siblings).as("no restore temp files left behind").containsExactly(jar);
    }
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void should_reapply_posix_mode_when_restoring() throws IOException {
    Files.setPosixFilePermissions(jar, PosixFilePermissions.fromString("rw-r-----"));
    Snapshot snapshot = store.create("run-1", "step-a", List.of(jar), install);
    Files.setPosixFilePermissions(jar, PosixFilePermissions.fromString("rw-rw-rw-"));
    Files.writeString(jar, "changed", StandardCharsets.UTF_8);

    store.restore(snapshot);

    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(jar)))
        .isEqualTo("rw-r-----");
  }

  @Test
  void should_reuse_existing_snapshot_when_creating_same_run_and_step_again() throws IOException {
    Snapshot first = store.create("run-1", "step-a", List.of(jar), install);
    Files.writeString(jar, "changed after snapshot", StandardCharsets.UTF_8);

    Snapshot second = store.create("run-1", "step-a", List.of(jar), install);

    assertThat(second).isEqualTo(first);
    store.restore(second);
    assertThat(Files.readString(jar, StandardCharsets.UTF_8)).isEqualTo("original jar bytes");
  }

  @Test
  void should_reject_files_outside_base_dir_when_creating(@TempDir Path elsewhere)
      throws IOException {
    Path stranger = elsewhere.resolve("stranger.txt");
    Files.writeString(stranger, "x", StandardCharsets.UTF_8);

    assertThatThrownBy(() -> store.create("run-1", "step-a", List.of(stranger), install))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("outside");
    assertThatThrownBy(() -> store.create("../run", "step-a", List.of(jar), install))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void should_list_find_and_size_snapshots_when_several_exist() throws IOException {
    Snapshot a = store.create("run-1", "step-a", List.of(jar), install);
    clock.advance(Duration.ofMinutes(1));
    Snapshot b = store.create("run-1", "step-b", List.of(props), install);
    clock.advance(Duration.ofMinutes(1));
    Snapshot c = store.create("run-2", "step-a", List.of(jar, props), install);
    Files.createDirectories(homeDir.resolve("snapshots/run-3/partial/payload"));

    assertThat(store.list()).containsExactly(a, b, c);
    assertThat(store.find("run-1", "step-b")).contains(b);
    assertThat(store.find("run-9", "step-b")).isEmpty();
    assertThat(store.totalBytes())
        .isGreaterThan(
            a.manifest().totalSize() + b.manifest().totalSize() + c.manifest().totalSize());
  }

  @Test
  void should_keep_protected_runs_when_pruning_by_count() throws IOException {
    Snapshot oldest = store.create("run-1", "step-a", List.of(jar), install);
    clock.advance(Duration.ofDays(1));
    Snapshot protectedOne = store.create("run-2", "step-a", List.of(jar), install);
    clock.advance(Duration.ofDays(1));
    Snapshot middle = store.create("run-3", "step-a", List.of(jar), install);
    clock.advance(Duration.ofDays(1));
    Snapshot newest = store.create("run-4", "step-a", List.of(jar), install);

    List<Snapshot> removed = store.prune(Duration.ofDays(30), 2, Set.of("run-2"));

    assertThat(removed).containsExactly(oldest, middle);
    assertThat(store.list()).containsExactly(protectedOne, newest);
    assertThat(homeDir.resolve("snapshots/run-1")).doesNotExist();
  }

  @Test
  void should_delete_expired_unprotected_snapshots_when_pruning_by_age() throws IOException {
    Snapshot expiredProtected = store.create("run-1", "step-a", List.of(jar), install);
    Snapshot expired = store.create("run-2", "step-a", List.of(jar), install);
    clock.advance(Duration.ofDays(10));
    Snapshot fresh = store.create("run-3", "step-a", List.of(jar), install);

    List<Snapshot> removed = store.prune(Duration.ofDays(7), 0, Set.of("run-1"));

    assertThat(removed).containsExactly(expired);
    assertThat(store.list()).containsExactly(expiredProtected, fresh);
  }

  @Test
  void should_round_trip_manifest_json_when_paths_contain_platform_separators() throws IOException {
    SnapshotManifest manifest =
        new SnapshotManifest(
            "run-1",
            "step-a",
            Instant.parse("2026-09-08T10:00:00Z"),
            install.toAbsolutePath(),
            List.of(
                new SnapshotManifest.Entry(
                    "a/b.jar",
                    "00",
                    7,
                    new FileOps.Permissions("owner", List.of("posix:rw-r-----", "group:g")))));
    Path file = homeDir.resolve("manifest.json");

    SnapshotJson.write(manifest, file);

    assertThat(SnapshotJson.read(file)).isEqualTo(manifest);
  }
}
