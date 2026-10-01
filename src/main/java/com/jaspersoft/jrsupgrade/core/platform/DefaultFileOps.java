package com.jaspersoft.jrsupgrade.core.platform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Platform-neutral {@link FileOps}: streaming SHA-256, atomic replace with a same-directory
 * fallback when the source lives on another volume, attribute-preserving copy, and probe-based
 * writability. Invariants: no method reads a whole file into memory (the hash buffer is 64 KB);
 * {@link #atomicReplace} captures the target's permissions before the rename and re-applies them
 * afterwards, so the replaced file keeps the ownership and access rules of the file it replaced;
 * when there is no target to copy from, the new file is created inside the destination directory so
 * that it inherits that directory's rules rather than the staging directory's; the temporary file
 * used by the cross-volume fallback is always created in the target's directory so the final step
 * is still a same-volume rename. Lock detection and permission capture are OS-specific and refined
 * by {@link WindowsFileOps} and {@link LinuxFileOps}; this base treats every file as unlocked and
 * records only the owner. Every rename goes through {@link Durability#move}, which retries a
 * transient denial and lets {@link AtomicMoveNotSupportedException} through at once so the
 * cross-volume fallback below can take over. Every file {@link #atomicReplace} lands is forced to
 * stable storage through {@link Durability} before the rename that publishes it, and the
 * destination directory is forced afterwards, so a power cut can never leave a valid name over
 * unwritten bytes; {@link #copyPreserving} carries the source's attributes across and so cannot
 * force a copy those attributes may have just made read-only.
 */
public class DefaultFileOps implements FileOps {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultFileOps.class);
  static final int BUFFER_SIZE = 64 * 1024;
  static final String TEMP_SUFFIX = ".jrs-upgrade-tmp";

  @Override
  public String sha256(Path file) throws IOException {
    MessageDigest digest = sha256Digest();
    byte[] buffer = new byte[BUFFER_SIZE];
    try (InputStream in = Files.newInputStream(file)) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  @Override
  public void atomicReplace(Path source, Path target) throws IOException {
    if (!Files.exists(target)) {
      landNewFile(source, target);
      return;
    }
    Optional<Permissions> kept = Optional.of(capturePermissions(target));
    Durability.sync(source);
    try {
      Durability.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException crossVolume) {
      LOG.debug("atomic move refused for {} -> {}; staging beside target", source, target);
      Path staged = tempSibling(target);
      try {
        Durability.copy(source, staged);
        Durability.move(
            staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        Files.deleteIfExists(staged);
        e.addSuppressed(crossVolume);
        throw e;
      }
      Files.delete(source);
    }
    Durability.syncDirectory(target.toAbsolutePath().getParent());
    if (kept.isPresent()) {
      applyPermissions(target, kept.get());
    }
  }

  /**
   * Lands a file that has no existing target to copy access rules from, by creating it inside the
   * destination directory and renaming it into place there. Creating the file in that directory is
   * what makes the platform apply the directory's rules to it: inheritable ACEs on Windows, the
   * umask and any setgid bit on POSIX. Renaming it in from somewhere else instead would carry the
   * staging directory's rules across, which on Windows leaves a file the service account cannot
   * open at all. {@code source} is consumed either way.
   */
  private void landNewFile(Path source, Path target) throws IOException {
    Path staged = tempSibling(target);
    try {
      Durability.copy(source, staged);
      Durability.move(
          staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      Files.deleteIfExists(staged);
      throw e;
    }
    Durability.syncDirectory(target.toAbsolutePath().getParent());
    Files.delete(source);
  }

  @Override
  public void copyPreserving(Path source, Path target) throws IOException {
    Permissions permissions = capturePermissions(source);
    Files.copy(
        source, target, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
    applyPermissions(target, permissions);
  }

  @Override
  public boolean isLocked(Path file) {
    return false;
  }

  @Override
  public Optional<String> lockHolder(Path file) {
    return Optional.empty();
  }

  @Override
  public Optional<String> lockInspectionLimit() {
    return Optional.empty();
  }

  @Override
  public Permissions capturePermissions(Path path) throws IOException {
    return new Permissions(ownerName(path), List.of());
  }

  @Override
  public void applyPermissions(Path path, Permissions permissions) throws IOException {
    applyOwner(path, permissions.owner());
  }

  @Override
  public long freeSpaceBytes(Path anyPathOnVolume) throws IOException {
    return fileStore(anyPathOnVolume).getUsableSpace();
  }

  @Override
  public String volumeId(Path anyPathOnVolume) throws IOException {
    return fileStore(anyPathOnVolume).toString();
  }

  private static FileStore fileStore(Path anyPathOnVolume) throws IOException {
    Path probe = anyPathOnVolume.toAbsolutePath();
    while (probe != null && !Files.exists(probe)) {
      probe = probe.getParent();
    }
    if (probe == null) {
      throw new NoSuchFileException(anyPathOnVolume.toString());
    }
    return Files.getFileStore(probe);
  }

  @Override
  public boolean isWritable(Path dir) {
    if (!Files.isDirectory(dir)) {
      return false;
    }
    try {
      Path probe = Files.createTempFile(dir, ".jrs-upgrade-probe", ".tmp");
      Files.delete(probe);
      return true;
    } catch (IOException | SecurityException e) {
      LOG.debug("{} is not writable", dir, e);
      return false;
    }
  }

  @Override
  public boolean isOwnerOnly(Path file) throws IOException {
    return false;
  }

  /**
   * Two probes, neither of which changes anything (#157). A file written over {@code file} is
   * created by this process, so if a new file in the same directory already gets {@code file}'s
   * owner there is nothing to restore; the probe file is removed again, like {@link #isWritable}'s.
   * Otherwise {@code file} is given the owner it already has, which needs exactly the right a later
   * restore of that owner needs: on Windows an elevated token for {@code BUILTIN\Administrators},
   * on Linux root for another user.
   */
  @Override
  public boolean canRestoreOwner(Path file) {
    UserPrincipal owner;
    try {
      owner = Files.getOwner(file);
    } catch (UnsupportedOperationException e) {
      return true;
    } catch (IOException e) {
      LOG.debug("cannot read the owner of {}", file, e);
      return true;
    }
    Path dir = file.toAbsolutePath().getParent();
    if (dir != null) {
      try {
        Path probe = Files.createTempFile(dir, ".jrs-upgrade-owner", ".tmp");
        try {
          if (Files.getOwner(probe).equals(owner)) {
            return true;
          }
        } finally {
          Files.deleteIfExists(probe);
        }
      } catch (IOException | SecurityException e) {
        LOG.debug("cannot probe the default owner in {}", dir, e);
      }
    }
    try {
      Files.setOwner(file, owner);
      return true;
    } catch (IOException | SecurityException e) {
      LOG.debug("cannot assign owner {} on {}", owner.getName(), file, e);
      return false;
    }
  }

  /** Owner name, or empty string when the file system cannot report one. */
  protected static String ownerName(Path path) throws IOException {
    try {
      return Files.getOwner(path).getName();
    } catch (UnsupportedOperationException e) {
      return "";
    }
  }

  /**
   * Sets the owner when it differs from the current one. Ownership changes usually need elevated
   * privileges, so a failure is logged and swallowed: the access rules were applied, only the
   * recorded owner could not be restored.
   */
  protected static void applyOwner(Path path, String owner) throws IOException {
    if (owner.isEmpty()) {
      return;
    }
    try {
      UserPrincipal current = Files.getOwner(path);
      if (current.getName().equalsIgnoreCase(owner)) {
        return;
      }
      UserPrincipal wanted =
          path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(owner);
      Files.setOwner(path, wanted);
    } catch (UnsupportedOperationException e) {
      LOG.debug("owner not supported on {}", path);
    } catch (IOException e) {
      LOG.warn("could not restore owner {} on {}: {}", owner, path, e.toString());
    }
  }

  static Path tempSibling(Path target) {
    Path absolute = target.toAbsolutePath();
    String name = "." + absolute.getFileName() + "." + System.nanoTime() + TEMP_SUFFIX;
    return absolute.resolveSibling(name);
  }

  static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is mandatory in every JRE", e);
    }
  }
}
