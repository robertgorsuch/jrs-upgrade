package com.jaspersoft.jrsupgrade.app;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** File helpers for the command tests. */
final class TestFiles {

  private TestFiles() {}

  /**
   * Makes a test's passphrase file owner-only where the file system has permission bits. Since
   * assessment item S2 the CLI refuses a {@code --passphrase-file} other users can read, which a
   * freshly written file under {@code /tmp} on Linux is (0644); on Windows a temp file already
   * carries an owner-only ACL and the call is a no-op.
   */
  static Path ownerOnly(Path file) throws IOException {
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
    return file;
  }
}
