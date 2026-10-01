package com.jaspersoft.jrsupgrade.core.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class DefaultFileOpsTest {

  private static final long TWO_AND_A_HALF_GB = 2_500L * 1024 * 1024;

  private final FileOps files = Platforms.detect().files();

  @Test
  void should_compute_known_sha256_when_hashing_small_file(@TempDir Path dir) throws IOException {
    Path file = dir.resolve("abc.txt");
    Files.writeString(file, "abc", StandardCharsets.US_ASCII);

    assertThat(files.sha256(file))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @Test
  void should_hash_sparse_2_5_gb_file_when_streaming_with_default_heap(@TempDir Path dir)
      throws Exception {
    assumeTrue(
        files.freeSpaceBytes(dir) > 3 * TWO_AND_A_HALF_GB,
        "needs about 7.5 GB free on the temp volume");
    Path sparse = dir.resolve("zeros.bin");
    try (RandomAccessFile raf = new RandomAccessFile(sparse.toFile(), "rw")) {
      raf.setLength(TWO_AND_A_HALF_GB);
    }
    assertThat(Files.size(sparse)).isEqualTo(TWO_AND_A_HALF_GB);

    String actual = files.sha256(sparse);

    assertThat(actual).isEqualTo(sha256OfZeros(TWO_AND_A_HALF_GB));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void should_keep_target_posix_permissions_when_atomically_replacing(@TempDir Path dir)
      throws IOException {
    Path target = dir.resolve("app.jar");
    Path source = dir.resolve("app.jar.new");
    Files.writeString(target, "old", StandardCharsets.UTF_8);
    Files.writeString(source, "new", StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r-----"));
    Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rw-rw-rw-"));

    files.atomicReplace(source, target);

    assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("new");
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
        .isEqualTo("rw-r-----");
    assertThat(source).doesNotExist();
  }

  /**
   * #157: a file this account created needs no owner restore, and the probe leaves the directory,
   * the file and its owner exactly as they were.
   */
  @Test
  void should_report_the_owner_restorable_and_change_nothing_when_this_account_owns_the_file(
      @TempDir Path dir) throws IOException {
    Path target = dir.resolve("app.jar");
    Files.writeString(target, "old", StandardCharsets.UTF_8);
    UserPrincipal ownerBefore = Files.getOwner(target);

    boolean restorable = files.canRestoreOwner(target);

    assertThat(restorable).isTrue();
    assertThat(Files.getOwner(target)).isEqualTo(ownerBefore);
    assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("old");
    try (var entries = Files.list(dir)) {
      assertThat(entries).containsExactly(target);
    }
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void should_keep_target_owner_when_atomically_replacing(@TempDir Path dir) throws IOException {
    Path target = dir.resolve("app.jar");
    Path source = dir.resolve("app.jar.new");
    Files.writeString(target, "old", StandardCharsets.UTF_8);
    Files.writeString(source, "new", StandardCharsets.UTF_8);
    UserPrincipal ownerBefore = Files.getOwner(target);

    files.atomicReplace(source, target);

    assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("new");
    assertThat(Files.getOwner(target)).isEqualTo(ownerBefore);
    assertThat(source).doesNotExist();
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void should_inherit_destination_directory_permissions_when_landing_a_new_file(@TempDir Path root)
      throws IOException {
    // Regression: a new file used to be renamed in from the staging directory and kept that
    // directory's rules. Under WEB-INF/lib that left a jar the service account could not open.
    Path staging = Files.createDirectories(root.resolve("staging"));
    Path destination = Files.createDirectories(root.resolve("webinf-lib"));
    Path reference = destination.resolve("reference.jar");
    Files.writeString(reference, "ref", StandardCharsets.UTF_8);
    Path source = staging.resolve("new.jar");
    Files.writeString(source, "new", StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rw-------"));
    Path target = destination.resolve("new.jar");

    files.atomicReplace(source, target);

    assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("new");
    assertThat(source).doesNotExist();
    assertThat(Files.getPosixFilePermissions(target))
        .isEqualTo(Files.getPosixFilePermissions(reference));
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
        .isNotEqualTo("rw-------");
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void should_inherit_destination_directory_acl_when_landing_a_new_file(@TempDir Path root)
      throws IOException {
    // Regression: see the Linux twin. On Windows the staged file carried an explicit ACL that did
    // not include the service account, so Tomcat could not open the jar and the context failed.
    Path staging = Files.createDirectories(root.resolve("staging"));
    Path destination = Files.createDirectories(root.resolve("webinf-lib"));
    Path reference = destination.resolve("reference.jar");
    Files.writeString(reference, "ref", StandardCharsets.UTF_8);
    Path source = staging.resolve("new.jar");
    Files.writeString(source, "new", StandardCharsets.UTF_8);
    AclFileAttributeView sourceAcl = Files.getFileAttributeView(source, AclFileAttributeView.class);
    assumeTrue(sourceAcl != null);
    AclEntry ownerOnly =
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(Files.getOwner(source))
            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
            .build();
    sourceAcl.setAcl(List.of(ownerOnly));
    List<String> restricted = files.capturePermissions(source).entries();
    Path target = destination.resolve("new.jar");

    files.atomicReplace(source, target);

    assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("new");
    assertThat(source).doesNotExist();
    assertThat(files.capturePermissions(target).entries())
        .isEqualTo(files.capturePermissions(reference).entries())
        .isNotEqualTo(restricted);
  }

  @Test
  void should_create_target_when_atomically_replacing_a_missing_file(@TempDir Path dir)
      throws IOException {
    Path target = dir.resolve("missing.txt");
    Path source = dir.resolve("source.txt");
    Files.writeString(source, "content", StandardCharsets.UTF_8);

    files.atomicReplace(source, target);

    assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("content");
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void should_report_locked_when_another_handle_denies_delete_sharing(@TempDir Path dir)
      throws IOException {
    Path jar = dir.resolve("held.jar");
    Files.writeString(jar, "jar bytes", StandardCharsets.UTF_8);

    // Tomcat opens jars through java.util.zip, i.e. via java.io without FILE_SHARE_DELETE; NIO's
    // FileChannel.open shares delete by default, so hold the file the way Tomcat does.
    try (RandomAccessFile held = new RandomAccessFile(jar.toFile(), "r")) {
      assertThat(held.getChannel().isOpen()).isTrue();
      assertThat(files.isLocked(jar)).isTrue();
      assertThat(Files.readString(jar, StandardCharsets.UTF_8))
          .as("a refused rename must leave the file exactly where it was")
          .isEqualTo("jar bytes");
      // Assessment item C3: nothing may be created beside a held jar, because on Windows
      // builds without POSIX delete semantics a guard link made before the rename could not
      // be removed while the jar was held and stayed in WEB-INF/lib.
      try (var siblings = Files.list(dir)) {
        assertThat(siblings).as("no guard or probe beside a held file").containsExactly(jar);
      }
    }

    assertThat(files.isLocked(jar)).isFalse();
    assertThat(Files.readString(jar, StandardCharsets.UTF_8)).isEqualTo("jar bytes");
    try (var siblings = Files.list(dir)) {
      assertThat(siblings).containsExactly(jar);
    }
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void should_leave_one_name_and_the_same_bytes_when_probing_a_free_file(@TempDir Path dir)
      throws IOException {
    // The success path renames the file aside and links its own name back to the probe; what
    // remains must be exactly the file, under exactly its name, with its bytes.
    Path jar = dir.resolve("free.jar");
    Files.writeString(jar, "jar bytes", StandardCharsets.UTF_8);

    assertThat(files.isLocked(jar)).isFalse();

    assertThat(Files.readString(jar, StandardCharsets.UTF_8)).isEqualTo("jar bytes");
    try (var siblings = Files.list(dir)) {
      assertThat(siblings).containsExactly(jar);
    }
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void should_restore_the_file_when_only_the_probe_name_was_left_behind(@TempDir Path dir)
      throws IOException {
    // The leftover shape of the current probe: no guard link exists before the rename, so a
    // crash between the rename and the restore leaves the probe name alone.
    Path jar = dir.resolve("held.jar");
    Files.writeString(
        dir.resolve("held.jar.jrs-upgrade-lockprobe"), "jar bytes", StandardCharsets.UTF_8);

    assertThat(files.isLocked(jar)).isFalse();

    assertThat(Files.readString(jar, StandardCharsets.UTF_8)).isEqualTo("jar bytes");
    try (var siblings = Files.list(dir)) {
      assertThat(siblings).containsExactly(jar);
    }
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void should_restore_the_file_when_an_earlier_lock_probe_was_interrupted(@TempDir Path dir)
      throws IOException {
    // What a power cut between the probe's two renames leaves behind.
    Path jar = dir.resolve("held.jar");
    Path leftover = dir.resolve("held.jar.jrs-upgrade-lockprobe");
    Files.writeString(leftover, "jar bytes", StandardCharsets.UTF_8);
    Files.createLink(dir.resolve("held.jar.jrs-upgrade-lockguard"), leftover);

    assertThat(files.isLocked(jar)).isFalse();

    assertThat(Files.readString(jar, StandardCharsets.UTF_8)).isEqualTo("jar bytes");
    try (var siblings = Files.list(dir)) {
      assertThat(siblings).containsExactly(jar);
    }
  }

  @Test
  void should_report_unlocked_when_file_is_missing_or_free(@TempDir Path dir) throws IOException {
    Path free = dir.resolve("free.txt");
    Files.writeString(free, "x", StandardCharsets.UTF_8);

    assertThat(files.isLocked(free)).isFalse();
    assertThat(files.isLocked(dir.resolve("absent.txt"))).isFalse();
    assertThat(files.lockHolder(free)).isEmpty();
  }

  @Test
  void should_copy_content_and_permissions_when_copying_preserving(@TempDir Path dir)
      throws IOException {
    Path source = dir.resolve("source.properties");
    Path target = dir.resolve("copy.properties");
    Files.writeString(source, "a=b", StandardCharsets.UTF_8);
    FileOps.Permissions before = files.capturePermissions(source);

    files.copyPreserving(source, target);

    assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("a=b");
    assertThat(files.capturePermissions(target).owner()).isEqualTo(before.owner());
    // Linux copies the timestamp through utimes(2), which keeps microseconds, not nanoseconds.
    assertThat(Files.getLastModifiedTime(target).toInstant().truncatedTo(ChronoUnit.MICROS))
        .isEqualTo(Files.getLastModifiedTime(source).toInstant().truncatedTo(ChronoUnit.MICROS));
  }

  @Test
  void should_round_trip_permissions_when_captured_and_applied(@TempDir Path dir)
      throws IOException {
    Path file = dir.resolve("secret.enc");
    Files.writeString(file, "s", StandardCharsets.UTF_8);
    FileOps.Permissions captured = files.capturePermissions(file);

    files.applyPermissions(file, captured);

    assertThat(captured.owner()).isNotEmpty();
    assertThat(files.capturePermissions(file).owner()).isEqualTo(captured.owner());
  }

  @Test
  void should_probe_writability_when_directory_exists_or_not(@TempDir Path dir) {
    assertThat(files.isWritable(dir)).isTrue();
    assertThat(files.isWritable(dir.resolve("nope"))).isFalse();
    try (var contents = Files.list(dir)) {
      assertThat(contents).as("probe file removed").isEmpty();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void should_report_free_space_when_path_does_not_exist_yet(@TempDir Path dir) throws IOException {
    assertThat(files.freeSpaceBytes(dir.resolve("future").resolve("child"))).isPositive();
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void should_detect_owner_only_when_posix_mode_has_no_group_or_other_bits(@TempDir Path dir)
      throws IOException {
    Path secret = dir.resolve("secret");
    Files.writeString(secret, "s", StandardCharsets.UTF_8);

    Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-------"));
    assertThat(files.isOwnerOnly(secret)).isTrue();

    Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-r--r--"));
    assertThat(files.isOwnerOnly(secret)).isFalse();
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void should_detect_owner_only_when_acl_grants_nobody_but_owner_and_admins(@TempDir Path dir)
      throws IOException {
    Path secret = dir.resolve("secret");
    Files.writeString(secret, "s", StandardCharsets.UTF_8);
    AclFileAttributeView view = Files.getFileAttributeView(secret, AclFileAttributeView.class);
    UserPrincipal owner = view.getOwner();
    AclEntry ownerFull =
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(owner)
            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
            .build();
    view.setAcl(List.of(ownerFull));
    assertThat(files.isOwnerOnly(secret)).isTrue();

    UserPrincipal everyone;
    try {
      everyone =
          secret.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("Everyone");
    } catch (IOException e) {
      assumeTrue(false, "well-known group 'Everyone' not resolvable on this locale");
      return;
    }
    List<AclEntry> widened = new ArrayList<>(view.getAcl());
    widened.add(
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(everyone)
            .setPermissions(EnumSet.of(AclEntryPermission.READ_DATA))
            .build());
    view.setAcl(widened);

    assertThat(files.isOwnerOnly(secret)).isFalse();
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void should_round_trip_acl_entries_when_serialised_and_parsed(@TempDir Path dir)
      throws IOException {
    Path file = dir.resolve("acl.txt");
    Files.writeString(file, "x", StandardCharsets.UTF_8);
    AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
    var lookup = file.getFileSystem().getUserPrincipalLookupService();

    for (AclEntry entry : view.getAcl()) {
      String serialised = WindowsFileOps.serialise(entry);
      assertThat(WindowsFileOps.deserialise(serialised, lookup))
          .as(serialised)
          .hasValueSatisfying(
              parsed -> {
                assertThat(parsed.type()).isEqualTo(entry.type());
                assertThat(parsed.principal()).isEqualTo(entry.principal());
                assertThat(parsed.permissions()).isEqualTo(entry.permissions());
                assertThat(parsed.flags()).isEqualTo(entry.flags());
              });
    }
  }

  private static String sha256OfZeros(long length) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] zeros = new byte[64 * 1024];
    long remaining = length;
    while (remaining > 0) {
      int chunk = (int) Math.min(zeros.length, remaining);
      digest.update(zeros, 0, chunk);
      remaining -= chunk;
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
