package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.engine.CancellationToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Linux backup format. Invariant: a tar.gz carries the mode, owner, group and symbolic links a
 * JasperReports installation depends on, so a restore reproduces the tree rather than an
 * approximation of it (spec §10.2 step 6). Links are written as link entries and recreated last,
 * after every regular entry, so a link can never be the path another entry is written through; mode
 * and ownership are re-applied deepest path first, and a failure to give a file away, which only
 * root may do, is reported once and does not fail the restore.
 */
final class TarGzFormat implements ArchiveFormat {

  private static final Logger LOG = LoggerFactory.getLogger(TarGzFormat.class);

  @Override
  public String extension() {
    return ".tar.gz";
  }

  @Override
  public long write(Path root, OutputStream raw, CancellationToken cancel) throws IOException {
    long[] count = {0};
    try (TarArchiveOutputStream tar =
        new TarArchiveOutputStream(new GZIPOutputStream(raw), Archives.BUFFER)) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
      Archives.walk(
          root,
          cancel,
          (file, rel, attrs) -> {
            if (attrs.isSymbolicLink()) {
              Archives.checkLinkTarget(root, file, Files.readSymbolicLink(file).toString());
            }
            TarArchiveEntry entry = tarEntry(file, rel, attrs);
            tar.putArchiveEntry(entry);
            // Content follows only for a real file. TarArchiveEntry.isFile() is true for a
            // symbolic link too, since it only asks whether the name ends in "/", and
            // copying then writes the link target's bytes into a zero-length header.
            if (attrs.isRegularFile()) {
              Archives.copy(file, tar);
            }
            tar.closeArchiveEntry();
            count[0]++;
          });
    }
    return count[0];
  }

  @Override
  public long read(InputStream raw, Path root, CancellationToken cancel) throws IOException {
    long count = 0;
    List<Ownership> metadata = new ArrayList<>();
    List<TarArchiveEntry> links = new ArrayList<>();
    try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(raw))) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        cancel.checkpoint();
        Path target = Archives.resolve(root, entry);
        if (entry.isSymbolicLink()) {
          // Deferred: a link created now could be the path a later entry is written through.
          links.add(entry);
        } else if (entry.isDirectory()) {
          Files.createDirectories(target);
          metadata.add(ownership(target, entry));
        } else {
          Files.createDirectories(target.getParent());
          Archives.write(tar, target);
          metadata.add(ownership(target, entry));
        }
        count++;
      }
    }
    for (TarArchiveEntry link : links) {
      Path at = Archives.resolve(root, link);
      Archives.checkLinkTarget(root, at, link.getLinkName());
      createSymbolicLink(at, link.getLinkName());
    }
    reapply(metadata);
    return count;
  }

  @Override
  public List<String> names(InputStream raw) throws IOException {
    List<String> names = new ArrayList<>();
    try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(raw))) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        names.add(entry.getName());
      }
    }
    return List.copyOf(names);
  }

  /** One archived entry's POSIX metadata, re-applied after the whole tree is written. */
  private record Ownership(Path target, int mode, String user, String group, int uid, int gid) {}

  /**
   * The tar entry for one path, carrying its mode, owner and group; a symbolic link becomes a link
   * entry holding what it points at, never a copy of the target.
   */
  private static TarArchiveEntry tarEntry(Path file, String rel, BasicFileAttributes attrs)
      throws IOException {
    if (attrs.isSymbolicLink()) {
      TarArchiveEntry entry = new TarArchiveEntry(rel, TarConstants.LF_SYMLINK);
      entry.setLinkName(Files.readSymbolicLink(file).toString().replace('\\', '/'));
      entry.setModTime(attrs.lastModifiedTime().toMillis());
      entry.setSize(0);
      return entry;
    }
    if (!attrs.isDirectory() && !attrs.isRegularFile()) {
      throw new IOException(
          "cannot archive " + file + ": it is neither a file, a directory nor a symbolic link");
    }
    TarArchiveEntry entry = new TarArchiveEntry(rel + (attrs.isDirectory() ? "/" : ""));
    entry.setModTime(attrs.lastModifiedTime().toMillis());
    entry.setMode(mode(file, attrs));
    owner(file).ifPresent(o -> applyOwnerTo(entry, o));
    if (!attrs.isDirectory()) {
      entry.setSize(attrs.size());
    }
    return entry;
  }

  private static void applyOwnerTo(TarArchiveEntry entry, Ownership owner) {
    entry.setUserName(owner.user());
    entry.setGroupName(owner.group());
    entry.setUserId(owner.uid());
    entry.setGroupId(owner.gid());
  }

  /** Owner and group of a path, empty on a file system that has no POSIX view (Windows). */
  private static Optional<Ownership> owner(Path file) {
    try {
      PosixFileAttributes posix =
          Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      return Optional.of(
          new Ownership(
              file,
              0,
              posix.owner().getName(),
              posix.group().getName(),
              numeric(file, "unix:uid"),
              numeric(file, "unix:gid")));
    } catch (UnsupportedOperationException | IOException e) {
      return Optional.empty();
    }
  }

  private static int numeric(Path file, String attribute) {
    try {
      Object value = Files.getAttribute(file, attribute, LinkOption.NOFOLLOW_LINKS);
      return value instanceof Integer id ? id : 0;
    } catch (UnsupportedOperationException | IOException | IllegalArgumentException e) {
      return 0;
    }
  }

  private static Ownership ownership(Path target, TarArchiveEntry entry) {
    return new Ownership(
        target,
        entry.getMode(),
        entry.getUserName() == null ? "" : entry.getUserName(),
        entry.getGroupName() == null ? "" : entry.getGroupName(),
        (int) entry.getLongUserId(),
        (int) entry.getLongGroupId());
  }

  private static void createSymbolicLink(Path target, String linkName) throws IOException {
    Files.deleteIfExists(target);
    Files.createDirectories(target.getParent());
    try {
      Files.createSymbolicLink(target, target.getFileSystem().getPath(linkName));
    } catch (UnsupportedOperationException e) {
      throw new IOException("cannot recreate the symbolic link " + target + " -> " + linkName, e);
    }
  }

  /**
   * Re-applies mode, owner and group deepest path first, so a directory whose mode forbids writing
   * is only locked down once its children are there. A failure to change owner is reported once and
   * does not fail the restore: only root may give a file away, and a tree restored under the
   * operator's own account is still a restored tree.
   */
  private static void reapply(List<Ownership> metadata) {
    List<Ownership> ordered = new ArrayList<>(metadata);
    ordered.sort(Comparator.comparingInt((Ownership o) -> o.target().getNameCount()).reversed());
    boolean reported = false;
    for (Ownership o : ordered) {
      applyMode(o.target(), o.mode());
      if (!applyOwner(o) && !reported) {
        LOG.warn(
            "cannot restore the owner of {}; the tree keeps this process's own user and group",
            o.target());
        reported = true;
      }
    }
  }

  /** True when owner and group were set, or when there was nothing recorded to set. */
  private static boolean applyOwner(Ownership o) {
    if (o.user().isEmpty() && o.group().isEmpty()) {
      return true;
    }
    PosixFileAttributeView view =
        Files.getFileAttributeView(o.target(), PosixFileAttributeView.class);
    if (view == null) {
      return true;
    }
    try {
      UserPrincipalLookupService lookup =
          o.target().getFileSystem().getUserPrincipalLookupService();
      if (!o.user().isEmpty()) {
        view.setOwner(lookup.lookupPrincipalByName(o.user()));
      }
      if (!o.group().isEmpty()) {
        view.setGroup(lookup.lookupPrincipalByGroupName(o.group()));
      }
      return true;
    } catch (UnsupportedOperationException | IOException | SecurityException e) {
      LOG.debug("cannot set owner {}:{} on {}: {}", o.user(), o.group(), o.target(), e.toString());
      return false;
    }
  }

  private static int mode(Path file, BasicFileAttributes attrs) {
    int base =
        attrs.isDirectory() ? TarArchiveEntry.DEFAULT_DIR_MODE : TarArchiveEntry.DEFAULT_FILE_MODE;
    try {
      Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
      return posixMode(perms);
    } catch (UnsupportedOperationException | IOException e) {
      return base;
    }
  }

  private static int posixMode(Set<PosixFilePermission> perms) {
    int mode = 0;
    for (PosixFilePermission p : perms) {
      mode |=
          switch (p) {
            case OWNER_READ -> 0400;
            case OWNER_WRITE -> 0200;
            case OWNER_EXECUTE -> 0100;
            case GROUP_READ -> 040;
            case GROUP_WRITE -> 020;
            case GROUP_EXECUTE -> 010;
            case OTHERS_READ -> 04;
            case OTHERS_WRITE -> 02;
            case OTHERS_EXECUTE -> 01;
          };
    }
    return mode;
  }

  private static void applyMode(Path target, int mode) {
    if (mode == 0) {
      return;
    }
    StringBuilder rwx = new StringBuilder();
    int[] shifts = {6, 3, 0};
    for (int shift : shifts) {
      int bits = (mode >> shift) & 07;
      rwx.append((bits & 4) != 0 ? 'r' : '-');
      rwx.append((bits & 2) != 0 ? 'w' : '-');
      rwx.append((bits & 1) != 0 ? 'x' : '-');
    }
    try {
      Files.setPosixFilePermissions(target, PosixFilePermissions.fromString(rwx.toString()));
    } catch (UnsupportedOperationException | IOException e) {
      // not a POSIX file system: permissions are inherited from the parent directory
    }
  }
}
