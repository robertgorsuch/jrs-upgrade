package com.jaspersoft.jrsupgrade.core.platform;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Directory-tree removal shared by staging, snapshot, bundle and archive clean-up (review finding
 * 1.19). Invariants: a missing tree is not an error; symbolic links are removed, never followed; a
 * Windows read-only attribute is cleared and the delete retried once, so a vendor file that ships
 * read-only cannot pin a whole tree; any other failure propagates with the offending path.
 */
public final class Trees {

  private Trees() {}

  public static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    Files.walkFileTree(
        dir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException error)
              throws IOException {
            if (error != null) {
              throw error;
            }
            delete(directory);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void delete(Path path) throws IOException {
    try {
      Files.delete(path);
    } catch (AccessDeniedException denied) {
      if (!clearReadOnly(path)) {
        throw denied;
      }
      Files.delete(path);
    }
  }

  /** True when a DOS read-only attribute was set and has been cleared. */
  private static boolean clearReadOnly(Path path) {
    try {
      Object readOnly = Files.getAttribute(path, "dos:readonly", LinkOption.NOFOLLOW_LINKS);
      if (Boolean.TRUE.equals(readOnly)) {
        Files.setAttribute(path, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS);
        return true;
      }
    } catch (UnsupportedOperationException | IllegalArgumentException | IOException e) {
      // no DOS attributes on this file system: nothing to clear
    }
    return false;
  }
}
