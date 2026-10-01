package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.core.platform.FileOps;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.platform.ProcessRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes small secret files (private keys, file: secrets) that only their owner may read (spec
 * §5.2). Invariants: the file is created empty and exclusively, so an existing key is never
 * overwritten; it is restricted <em>before</em> the content is written, so no byte of the secret
 * ever exists under the inherited permissions (review 4.2); on Windows the inherited access control
 * entries are dropped as well, because a replaced DACL that is not protected can have them
 * propagated back; the permission set is applied through the platform's {@link FileOps} in the same
 * serialised form it captures and restores, which is what {@link FileOps#isOwnerOnly} later checks
 * when the file is used as a {@code file:} secret reference; the result is verified and a file that
 * is still readable by anyone else is deleted rather than left holding a secret; the content is
 * never logged.
 */
public final class OwnerOnlyFiles {

  private static final Logger LOG = LoggerFactory.getLogger(OwnerOnlyFiles.class);
  private static final Duration ICACLS_TIMEOUT = Duration.ofSeconds(20);

  private static final String WINDOWS_FULL_CONTROL =
      Arrays.stream(AclEntryPermission.values()).map(Enum::name).collect(Collectors.joining(","));

  private OwnerOnlyFiles() {}

  public static void write(Platform platform, Path file, String content) throws IOException {
    Path abs = file.toAbsolutePath().normalize();
    Path parent = abs.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    // review 4.2: create empty, lock down, then write. The old order wrote the secret under the
    // umask or the inherited ACL and narrowed the file afterwards, leaving a readable window.
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Files.createFile(
          abs, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } else {
      Files.createFile(abs);
    }
    boolean written = false;
    try {
      restrictToOwner(platform, abs);
      Files.writeString(
          abs,
          content,
          StandardCharsets.UTF_8,
          StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING);
      verifyOwnerOnly(platform, abs);
      written = true;
    } finally {
      if (!written) {
        Files.deleteIfExists(abs);
      }
    }
  }

  /** Refuses a file others can still read, rather than leaving a secret in it (review 4.2). */
  private static void verifyOwnerOnly(Platform platform, Path file) throws IOException {
    if (!platform.files().isOwnerOnly(file)) {
      throw new IOException(
          file
              + " is still readable by accounts other than its owner; refusing to keep a secret"
              + " there");
    }
  }

  public static void restrictToOwner(Platform platform, Path file) throws IOException {
    FileOps files = platform.files();
    String owner = files.capturePermissions(file).owner();
    List<String> entries =
        switch (platform.os()) {
          case WINDOWS ->
              owner.isEmpty()
                  ? List.of()
                  : List.of("ALLOW|" + owner + "|" + WINDOWS_FULL_CONTROL + "|");
          case LINUX -> List.of("posix:rw-------");
        };
    if (entries.isEmpty()) {
      throw new IOException("cannot determine the owner of " + file + " to restrict it");
    }
    files.applyPermissions(file, new FileOps.Permissions(owner, entries));
    if (platform.os() == Platform.OsFamily.WINDOWS) {
      dropInheritedEntries(platform, file, owner + ":(F)");
    }
  }

  /**
   * Restricts a directory jrs-upgrade has just created (issue #50): on Windows one entry gives the
   * owner full control and is inherited by every file and directory created below it, and the DACL
   * is protected so nothing of the parent's access list flows in; on Linux the mode is {@code
   * rwx------}.
   */
  public static void restrictDirectoryToOwner(Platform platform, Path dir) throws IOException {
    FileOps files = platform.files();
    String owner = files.capturePermissions(dir).owner();
    List<String> entries =
        switch (platform.os()) {
          case WINDOWS ->
              owner.isEmpty()
                  ? List.of()
                  : List.of(
                      "ALLOW|"
                          + owner
                          + "|"
                          + WINDOWS_FULL_CONTROL
                          + "|FILE_INHERIT,DIRECTORY_INHERIT");
          case LINUX -> List.of("posix:rwx------");
        };
    if (entries.isEmpty()) {
      throw new IOException("cannot determine the owner of " + dir + " to restrict it");
    }
    files.applyPermissions(dir, new FileOps.Permissions(owner, entries));
    if (platform.os() == Platform.OsFamily.WINDOWS) {
      dropInheritedEntries(platform, dir, owner + ":(OI)(CI)(F)");
    }
  }

  /**
   * Drops inherited access control entries and marks the DACL protected (review 4.2). Replacing the
   * DACL through the file-attribute view leaves the security descriptor unprotected, so the
   * parent's inheritable entries can be propagated back onto a file holding a secret. Windows has
   * no API for this in the JDK, and neither BouncyCastle nor JNA is approved (spec §13.3), so the
   * built-in {@code icacls} does it, with its arguments as a list and no shell. The same call
   * grants the owner full control explicitly ({@code ownerGrant}): inside a directory jrs-upgrade
   * restricted, the file inherits exactly the owner entry the ACL view just set, Windows folds the
   * two together, and removing the inherited entries alone left an empty access list that shut the
   * owner out too.
   */
  private static void dropInheritedEntries(Platform platform, Path file, String ownerGrant) {
    try {
      ProcessRunner.Result result =
          platform
              .processes()
              .run(
                  new ProcessRunner.Request(
                      List.of("icacls", file.toString(), "/inheritance:r", "/grant:r", ownerGrant),
                      Optional.empty(),
                      Map.of(),
                      ICACLS_TIMEOUT),
                  line -> {});
      if (!result.ok()) {
        LOG.debug("icacls /inheritance:r on {} exited {}", file, result.exitCode());
      }
    } catch (RuntimeException e) {
      LOG.debug("cannot run icacls on {}: {}", file, e.getMessage());
    }
  }
}
