package com.jaspersoft.jrsupgrade.core.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * File-system operations with the guarantees ops code relies on (spec §5.3): atomic replace, lock
 * detection, permission/ACL preservation, streaming hashes. Invariant: nothing here reads a whole
 * file into memory; {@link #sha256} streams.
 */
public interface FileOps {

  /** Hex SHA-256 of the file content, computed while streaming. */
  String sha256(Path file) throws IOException;

  /**
   * Replaces {@code target} with {@code source} atomically (rename within the same volume), keeping
   * the target's owner, permissions and ACLs. When {@code target} does not exist there is nothing
   * to keep, so the file is created inside the destination directory and inherits that directory's
   * access rules rather than the staging directory's. {@code source} is consumed.
   */
  void atomicReplace(Path source, Path target) throws IOException;

  /** Copies preserving permissions/ACLs and timestamps, streaming. */
  void copyPreserving(Path source, Path target) throws IOException;

  /** True when another process holds the file in a way that prevents rename/delete. */
  boolean isLocked(Path file);

  /** Best-effort id of the process holding the lock, for the failure message. */
  Optional<String> lockHolder(Path file);

  /**
   * Why {@link #isLocked} cannot see every open handle on this host, or empty when it can. A {@code
   * false} from {@link #isLocked} means "no holder found", not "no holder", whenever this is
   * present, so callers report a warning instead of treating the file as free (review 3.3).
   */
  Optional<String> lockInspectionLimit();

  /** Captures owner/permissions/ACLs so they can be re-applied after a restore. */
  Permissions capturePermissions(Path path) throws IOException;

  void applyPermissions(Path path, Permissions permissions) throws IOException;

  long freeSpaceBytes(Path anyPathOnVolume) throws IOException;

  /**
   * Identifies the volume (file store) holding {@code anyPathOnVolume} or its nearest existing
   * ancestor; two paths with equal ids draw on the same free space.
   */
  String volumeId(Path anyPathOnVolume) throws IOException;

  boolean isWritable(Path dir);

  /**
   * Whether a file written over {@code file} by this process can be given back {@code file}'s
   * current owner (#157). True when a new file in that directory already gets that owner, or when
   * this account may assign it; false when the owner would be lost. Changes nothing on disk.
   */
  default boolean canRestoreOwner(Path file) {
    return true;
  }

  /** Owner-only check for secret files (0600 on Linux; owner + Administrators only on Windows). */
  boolean isOwnerOnly(Path file) throws IOException;

  /** Opaque, platform-specific serialisable permission set. */
  record Permissions(String owner, List<String> entries) {}
}
