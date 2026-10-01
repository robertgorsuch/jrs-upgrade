package com.jaspersoft.jrsupgrade.core.platform;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Free-space arithmetic per volume (review finding 1.16). Invariants: needs are grouped by the
 * volume {@link FileOps#volumeId} reports for them (implementations resolve a directory that does
 * not exist yet through its nearest existing ancestor, so staging is measured where it will be
 * created); every volume must hold the sum of its needs plus {@link #MARGIN_BYTES}; nothing here
 * mutates.
 */
public final class DiskSpace {

  /** Kept free on every volume beyond what the operation itself needs. */
  public static final long MARGIN_BYTES = 64L << 20;

  /** {@code bytes} that {@code what} will put under {@code under}. */
  public record Need(String what, Path under, long bytes) {}

  private DiskSpace() {}

  /** One problem per volume that cannot hold its needs, or per path whose volume is unknown. */
  public static List<String> problems(FileOps files, List<Need> needs) {
    List<String> problems = new ArrayList<>();
    Map<String, List<Need>> byVolume = new LinkedHashMap<>();
    Map<String, Path> probes = new LinkedHashMap<>();
    for (Need need : needs) {
      Path probe = need.under();
      try {
        String volume = files.volumeId(probe);
        byVolume.computeIfAbsent(volume, v -> new ArrayList<>()).add(need);
        probes.putIfAbsent(volume, probe);
      } catch (IOException e) {
        problems.add("cannot determine free space under " + need.under() + ": " + e.getMessage());
      }
    }
    for (Map.Entry<String, List<Need>> volume : byVolume.entrySet()) {
      long total = MARGIN_BYTES;
      StringBuilder parts = new StringBuilder();
      for (Need need : volume.getValue()) {
        total += need.bytes();
        parts.append(need.what()).append(' ').append(human(need.bytes())).append(" + ");
      }
      parts.append("margin ").append(human(MARGIN_BYTES));
      try {
        Path where = probes.get(volume.getKey());
        long free = files.freeSpaceBytes(where);
        if (free < total) {
          // field test 3: say where, in units an operator reads, not a volume id and raw bytes
          problems.add(
              "not enough free space on the volume of "
                  + where
                  + ": "
                  + human(free)
                  + " free, "
                  + human(total)
                  + " needed ("
                  + parts
                  + ")");
        }
      } catch (IOException e) {
        problems.add(
            "cannot determine free space on volume " + volume.getKey() + ": " + e.getMessage());
      }
    }
    return List.copyOf(problems);
  }

  /**
   * What to do when the jrs-upgrade home is short of space (field test 3): the same advice wherever
   * the shortage is found, naming the home and the commands that make room or move it (ADR-0041).
   */
  public static String remedy(Path home) {
    return "free space on that volume, remove old snapshots with `jrs-upgrade runs prune`, or move the"
        + " jrs-upgrade home ("
        + home
        + ") to a bigger volume with `jrs-upgrade home set <dir>`";
  }

  /**
   * Whether {@code e}, or a cause of it, is the file system saying it is full: Windows' "There is
   * not enough space on the disk" and POSIX's "No space left on device" ({@code ENOSPC}).
   */
  public static boolean outOfSpace(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      String message = String.valueOf(t.getMessage()).toLowerCase(java.util.Locale.ROOT);
      if (message.contains("not enough space") || message.contains("no space left")) {
        return true;
      }
    }
    return false;
  }

  /** {@code 1.5 GB}, {@code 800.0 MB} or {@code 12 B}, for messages an operator reads. */
  public static String human(long bytes) {
    long gib = 1L << 30;
    long mib = 1L << 20;
    if (bytes >= gib) {
      return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (double) gib);
    }
    if (bytes >= mib) {
      return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (double) mib);
    }
    return bytes + " B";
  }

  /** Bytes in the regular files under {@code root}; a file is its own size, a missing path is 0. */
  public static long treeBytes(Path root) throws IOException {
    if (Files.isRegularFile(root)) {
      return Files.size(root);
    }
    if (!Files.isDirectory(root)) {
      return 0;
    }
    long[] total = {0};
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (attrs.isRegularFile()) {
              total[0] += attrs.size();
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return total[0];
  }
}
