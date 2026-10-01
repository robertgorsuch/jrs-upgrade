package com.jaspersoft.jrsupgrade.core.platform;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link FileOps} for Linux. Invariants: permissions are the POSIX mode bits plus owner and group,
 * serialised as {@code posix:rwxr-x---} and {@code group:<name>} entries; a file counts as locked
 * only when {@code /proc/<pid>/fd} shows another process holding it open (POSIX renames never fail
 * for open files, but a jar held by a running Tomcat must still not be replaced); {@link
 * #isOwnerOnly} is true when no group or other bit is set.
 */
public final class LinuxFileOps extends DefaultFileOps {

  private static final Logger LOG = LoggerFactory.getLogger(LinuxFileOps.class);
  private static final String POSIX_PREFIX = "posix:";
  private static final String GROUP_PREFIX = "group:";
  private static final Path PROC = Path.of("/proc");

  @Override
  public boolean isLocked(Path file) {
    return lockHolder(file).isPresent();
  }

  @Override
  public Optional<String> lockHolder(Path file) {
    Path real;
    try {
      real = file.toRealPath();
    } catch (IOException e) {
      return Optional.empty();
    }
    if (!Files.isDirectory(PROC)) {
      return Optional.empty();
    }
    long self = ProcessHandle.current().pid();
    try (DirectoryStream<Path> processes = Files.newDirectoryStream(PROC, "[0-9]*")) {
      for (Path proc : processes) {
        long pid;
        try {
          pid = Long.parseLong(proc.getFileName().toString());
        } catch (NumberFormatException e) {
          continue;
        }
        if (pid == self) {
          continue;
        }
        if (holdsOpen(proc, real)) {
          return Optional.of("pid " + pid + commandSuffix(pid));
        }
      }
    } catch (IOException e) {
      LOG.debug("cannot scan /proc", e);
    }
    return Optional.empty();
  }

  /**
   * Review 3.3: {@code /proc/<pid>/fd} belongs to the process owner, so a jrs-upgrade that is not
   * root cannot see a jar held open by a Tomcat running as another account. Rather than report such
   * a file as free, the scan says how many processes it could not look inside.
   */
  @Override
  public Optional<String> lockInspectionLimit() {
    if (!Files.isDirectory(PROC)) {
      return Optional.of(PROC + " is not mounted; open handles cannot be inspected");
    }
    long self = ProcessHandle.current().pid();
    int total = 0;
    int unreadable = 0;
    try (DirectoryStream<Path> processes = Files.newDirectoryStream(PROC, "[0-9]*")) {
      for (Path proc : processes) {
        if (proc.getFileName().toString().equals(Long.toString(self))) {
          continue;
        }
        total++;
        if (!Files.isReadable(proc.resolve("fd"))) {
          unreadable++;
        }
      }
    } catch (IOException e) {
      LOG.debug("cannot scan /proc", e);
      return Optional.of("cannot scan " + PROC + ": " + e.getMessage());
    }
    return unreadable == 0
        ? Optional.empty()
        : Optional.of(
            "cannot inspect open handles of "
                + unreadable
                + " of "
                + total
                + " processes as "
                + System.getProperty("user.name", "this account")
                + "; a file held by another account is reported as free");
  }

  private static boolean holdsOpen(Path proc, Path real) {
    try (DirectoryStream<Path> fds = Files.newDirectoryStream(proc.resolve("fd"))) {
      for (Path fd : fds) {
        try {
          if (Files.readSymbolicLink(fd).equals(real)) {
            return true;
          }
        } catch (IOException | UnsupportedOperationException e) {
          // fd vanished or is not a symlink; keep scanning
        }
      }
    } catch (IOException | SecurityException e) {
      // other users' processes are not readable; that is expected
    }
    return false;
  }

  private static String commandSuffix(long pid) {
    return ProcessHandle.of(pid)
        .flatMap(h -> h.info().command())
        .map(c -> " (" + Path.of(c).getFileName() + ")")
        .orElse("");
  }

  @Override
  public FileOps.Permissions capturePermissions(Path path) throws IOException {
    PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
    if (view == null) {
      return super.capturePermissions(path);
    }
    PosixFileAttributes attrs = view.readAttributes();
    List<String> entries = new ArrayList<>(2);
    entries.add(POSIX_PREFIX + PosixFilePermissions.toString(attrs.permissions()));
    entries.add(GROUP_PREFIX + attrs.group().getName());
    return new FileOps.Permissions(attrs.owner().getName(), List.copyOf(entries));
  }

  @Override
  public void applyPermissions(Path path, FileOps.Permissions permissions) throws IOException {
    PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
    if (view == null) {
      super.applyPermissions(path, permissions);
      return;
    }
    for (String entry : permissions.entries()) {
      if (entry.startsWith(POSIX_PREFIX)) {
        view.setPermissions(
            PosixFilePermissions.fromString(entry.substring(POSIX_PREFIX.length())));
      } else if (entry.startsWith(GROUP_PREFIX)) {
        applyGroup(path, view, entry.substring(GROUP_PREFIX.length()));
      } else {
        LOG.debug("ignoring foreign permission entry {} on {}", entry, path);
      }
    }
    applyOwner(path, permissions.owner());
  }

  private static void applyGroup(Path path, PosixFileAttributeView view, String group) {
    try {
      if (view.readAttributes().group().getName().equals(group)) {
        return;
      }
      GroupPrincipal principal =
          path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByGroupName(group);
      view.setGroup(principal);
    } catch (IOException e) {
      LOG.warn("could not restore group {} on {}: {}", group, path, e.toString());
    }
  }

  @Override
  public boolean isOwnerOnly(Path file) throws IOException {
    Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
    for (PosixFilePermission p : perms) {
      switch (p) {
        case GROUP_READ, GROUP_WRITE, GROUP_EXECUTE, OTHERS_READ, OTHERS_WRITE, OTHERS_EXECUTE -> {
          return false;
        }
        case OWNER_READ, OWNER_WRITE, OWNER_EXECUTE -> {
          // owner bits are fine
        }
      }
    }
    return true;
  }
}
