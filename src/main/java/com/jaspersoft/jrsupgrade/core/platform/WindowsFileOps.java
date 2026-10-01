package com.jaspersoft.jrsupgrade.core.platform;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link FileOps} for Windows. Invariants: permissions are the file's owner plus every DACL entry
 * serialised as {@code TYPE|principal|perm,perm|flag,flag}; a file is locked when it cannot be
 * opened for writing or cannot be renamed within its own directory, which is exactly what a jar
 * held open by Tomcat (opened through {@code java.io} without {@code FILE_SHARE_DELETE}) looks
 * like; {@link #isOwnerOnly} accepts ALLOW entries only for the owner, {@code NT AUTHORITY\SYSTEM}
 * and {@code BUILTIN\Administrators}. Lock holders are only reported when a running Tomcat's
 * command line references an ancestor of the file; Windows offers no cheap general lookup.
 *
 * <p>Windows has no way to ask whether a file may be renamed without renaming it: the only open
 * flag that requests DELETE access, {@code DELETE_ON_CLOSE}, also takes it, and a hard link is
 * checked against its own directory entry rather than the one the holder opened. So the probe
 * renames, and everything around that rename exists to make the window survivable. Nothing is
 * created before the rename: a rename that is refused, which is the case on a live server, answers
 * the question with nothing moved and nothing left beside the jar (assessment item C3: a guard link
 * made beforehand could not be removed while the jar was held on Windows builds that delete without
 * POSIX semantics, and stayed in {@code WEB-INF/lib}). A rename that succeeds is undone at once:
 * the file's own name is linked back to the probe name, which works even if something has already
 * opened the probe, and the probe name is then dropped; if that fails the probe is renamed back
 * with retries; and a restore that fails every way throws rather than reporting "not locked" over a
 * missing jar. Whatever a crash leaves behind is named after the file it came from, and {@link
 * #isLocked} puts it back before it does anything else.
 */
public final class WindowsFileOps extends DefaultFileOps {

  private static final Logger LOG = LoggerFactory.getLogger(WindowsFileOps.class);
  private static final String LOCK_PROBE_SUFFIX = ".jrs-upgrade-lockprobe";
  private static final String LOCK_GUARD_SUFFIX = ".jrs-upgrade-lockguard";
  private static final int RENAME_BACK_ATTEMPTS = 20;
  private static final long RENAME_BACK_DELAY_MS = 50;
  private static final Pattern PIPE = Pattern.compile("\\|");
  private static final Pattern COMMA = Pattern.compile(",");
  private static final Set<String> SYSTEM_PRINCIPALS =
      Set.of("nt authority\\system", "builtin\\administrators", "system", "administrators");

  private final TomcatProcessFinder processes;

  public WindowsFileOps() {
    this(new WindowsTomcatProcesses(new DefaultProcessRunner()));
  }

  WindowsFileOps(TomcatProcessFinder processes) {
    this.processes = processes;
  }

  @Override
  public boolean isLocked(Path file) {
    healLeftoverProbe(file);
    if (!Files.isRegularFile(file)) {
      return false;
    }
    try (FileChannel ignored = FileChannel.open(file, StandardOpenOption.WRITE)) {
      // opened for write: nobody denies write sharing
    } catch (IOException e) {
      LOG.debug("{} refuses write access: {}", file, e.toString());
      return true;
    }
    return deniesRename(file);
  }

  /**
   * True when the file cannot be renamed inside its own directory. Nothing is created before the
   * rename, so a refusal leaves the directory exactly as it was; the only path that leaves the
   * file's own name unused is a crash between the rename and the restore, and {@link
   * #healLeftoverProbe} closes that on the next call.
   *
   * @throws IllegalStateException when the file was moved aside and could not be put back, which
   *     must never be mistaken for "not locked"
   */
  private boolean deniesRename(Path file) {
    Path absolute = file.toAbsolutePath();
    Path probe = absolute.resolveSibling(absolute.getFileName() + LOCK_PROBE_SUFFIX);
    try {
      Files.move(absolute, probe);
    } catch (IOException e) {
      LOG.debug("{} refuses rename: {}", file, e.toString());
      return true;
    }
    restore(absolute, probe);
    return false;
  }

  /**
   * Puts the file back under its own name. First choice: a hard link from the file's name to the
   * probe, which succeeds even while something (an on-access scanner, typically) has already opened
   * the probe, followed by dropping the probe name; second choice: renaming the probe back, with
   * retries. Whichever copy is left over is removed, and a leftover the holder will not release is
   * logged, never a reason to fail the check.
   */
  private static void restore(Path file, Path probe) {
    IOException last;
    try {
      Files.createLink(file, probe);
      discard(probe);
      return;
    } catch (IOException | UnsupportedOperationException e) {
      LOG.debug("cannot link {} back to {}: {}", file, probe, e.toString());
      last = e instanceof IOException io ? io : new IOException(e);
    }
    for (int attempt = 0; attempt < RENAME_BACK_ATTEMPTS; attempt++) {
      try {
        Files.move(probe, file);
        return;
      } catch (IOException e) {
        last = e;
        if (!pause()) {
          break;
        }
      }
    }
    throw new IllegalStateException(
        "lock probe moved " + file + " to " + probe + " and could not put it back", last);
  }

  private static void discard(Path leftover) {
    try {
      Files.deleteIfExists(leftover);
    } catch (IOException e) {
      LOG.debug("cannot remove lock probe leftover {}: {}", leftover, e.toString());
    }
  }

  private static boolean pause() {
    try {
      Thread.sleep(RENAME_BACK_DELAY_MS);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Undoes a probe that a crash interrupted. The file's bytes are always still there under the
   * probe name (or the guard name an earlier jrs-upgrade release used), so the repair is a rename
   * and nothing is lost; the other name, if it survived too, is the same data and goes. This runs
   * before every lock check, so no caller ever sees the gap.
   */
  private static void healLeftoverProbe(Path file) {
    if (Files.exists(file)) {
      return;
    }
    Path absolute = file.toAbsolutePath();
    List<Path> leftovers =
        Stream.of(LOCK_PROBE_SUFFIX, LOCK_GUARD_SUFFIX)
            .map(suffix -> absolute.resolveSibling(absolute.getFileName() + suffix))
            .toList();
    for (Path leftover : leftovers) {
      if (!Files.isRegularFile(leftover)) {
        continue;
      }
      try {
        Files.move(leftover, absolute);
        LOG.warn("restored {} from {} left by an interrupted lock probe", file, leftover);
        leftovers.forEach(WindowsFileOps::discard);
        return;
      } catch (IOException e) {
        LOG.error("cannot restore {} from {}", file, leftover, e);
      }
    }
  }

  @Override
  public Optional<String> lockHolder(Path file) {
    Path absolute = file.toAbsolutePath().normalize();
    List<TomcatProcessFinder.TomcatProcess> tomcats;
    try {
      tomcats = processes.find();
    } catch (TomcatScanException e) {
      LOG.debug("cannot name the holder of {}: {}", file, e.getMessage());
      return Optional.empty();
    }
    for (TomcatProcessFinder.TomcatProcess tomcat : tomcats) {
      boolean holds =
          tomcat.catalinaHome().map(absolute::startsWith).orElse(false)
              || tomcat.catalinaBase().map(absolute::startsWith).orElse(false);
      if (holds) {
        return Optional.of("pid " + tomcat.pid() + " (Tomcat)");
      }
    }
    return Optional.empty();
  }

  @Override
  public FileOps.Permissions capturePermissions(Path path) throws IOException {
    AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
    if (view == null) {
      return super.capturePermissions(path);
    }
    List<String> entries = new ArrayList<>();
    for (AclEntry entry : view.getAcl()) {
      entries.add(serialise(entry));
    }
    return new FileOps.Permissions(view.getOwner().getName(), List.copyOf(entries));
  }

  @Override
  public void applyPermissions(Path path, FileOps.Permissions permissions) throws IOException {
    AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
    if (view == null) {
      super.applyPermissions(path, permissions);
      return;
    }
    UserPrincipalLookupService lookup = path.getFileSystem().getUserPrincipalLookupService();
    List<AclEntry> acl = new ArrayList<>();
    for (String serialised : permissions.entries()) {
      Optional<AclEntry> entry = deserialise(serialised, lookup);
      if (entry.isPresent()) {
        acl.add(entry.get());
      } else {
        LOG.warn("dropping unresolvable ACL entry {} while restoring {}", serialised, path);
      }
    }
    if (!acl.isEmpty()) {
      view.setAcl(acl);
    }
    applyOwner(path, permissions.owner());
  }

  @Override
  public boolean isOwnerOnly(Path file) throws IOException {
    AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
    if (view == null) {
      return false;
    }
    String owner = view.getOwner().getName().toLowerCase(Locale.ROOT);
    for (AclEntry entry : view.getAcl()) {
      if (entry.type() != AclEntryType.ALLOW) {
        continue;
      }
      String principal = entry.principal().getName().toLowerCase(Locale.ROOT);
      if (!principal.equals(owner) && !SYSTEM_PRINCIPALS.contains(principal)) {
        return false;
      }
    }
    return true;
  }

  static String serialise(AclEntry entry) {
    return entry.type().name()
        + "|"
        + entry.principal().getName()
        + "|"
        + entry.permissions().stream().map(Enum::name).collect(Collectors.joining(","))
        + "|"
        + entry.flags().stream().map(Enum::name).collect(Collectors.joining(","));
  }

  static Optional<AclEntry> deserialise(String serialised, UserPrincipalLookupService lookup) {
    String[] parts = PIPE.split(serialised, -1);
    if (parts.length != 4) {
      return Optional.empty();
    }
    Optional<UserPrincipal> principal = lookupPrincipal(lookup, parts[1]);
    if (principal.isEmpty()) {
      return Optional.empty();
    }
    AclEntry.Builder builder =
        AclEntry.newBuilder()
            .setType(AclEntryType.valueOf(parts[0]))
            .setPrincipal(principal.get())
            .setPermissions(names(parts[2], AclEntryPermission.class))
            .setFlags(names(parts[3], AclEntryFlag.class));
    return Optional.of(builder.build());
  }

  private static <E extends Enum<E>> Set<E> names(String joined, Class<E> type) {
    Set<E> set = EnumSet.noneOf(type);
    if (joined.isEmpty()) {
      return set;
    }
    for (String name : COMMA.split(joined, -1)) {
      set.add(Enum.valueOf(type, name));
    }
    return set;
  }

  private static Optional<UserPrincipal> lookupPrincipal(
      UserPrincipalLookupService lookup, String name) {
    try {
      return Optional.of(lookup.lookupPrincipalByName(name));
    } catch (IOException e) {
      try {
        return Optional.of(lookup.lookupPrincipalByGroupName(name));
      } catch (IOException e2) {
        LOG.debug("principal {} not found", name, e2);
        return Optional.empty();
      }
    }
  }
}
