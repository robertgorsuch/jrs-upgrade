package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.engine.CancellationToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

/**
 * The Windows backup format. Invariant: a zip carries names, contents and modification times and
 * nothing else, so an entry that is neither a file nor a directory is refused when archiving rather
 * than dropped: a backup that cannot reproduce the tree is not a backup (spec §10.2 step 6).
 * Windows ACLs are not in the archive; the restore inherits them from the parent directory, which
 * is what the upgrade's own permission handling expects.
 */
final class ZipFormat implements ArchiveFormat {

  @Override
  public String extension() {
    return ".zip";
  }

  @Override
  public long write(Path root, OutputStream raw, CancellationToken cancel) throws IOException {
    long[] count = {0};
    try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(raw)) {
      Archives.walk(
          root,
          cancel,
          (file, rel, attrs) -> {
            if (!attrs.isDirectory() && !attrs.isRegularFile()) {
              throw new IOException(
                  "cannot archive "
                      + file
                      + ": a zip archive cannot carry a link or device node, so restoring"
                      + " this backup would not reproduce the tree");
            }
            ZipArchiveEntry entry = new ZipArchiveEntry(rel + (attrs.isDirectory() ? "/" : ""));
            entry.setTime(attrs.lastModifiedTime().toMillis());
            zip.putArchiveEntry(entry);
            if (!attrs.isDirectory()) {
              Archives.copy(file, zip);
            }
            zip.closeArchiveEntry();
            count[0]++;
          });
    }
    return count[0];
  }

  @Override
  public long read(InputStream raw, Path root, CancellationToken cancel) throws IOException {
    long count = 0;
    try (ZipArchiveInputStream zip = new ZipArchiveInputStream(raw)) {
      ZipArchiveEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        cancel.checkpoint();
        Path target = Archives.resolve(root, entry);
        if (entry.isDirectory()) {
          Files.createDirectories(target);
        } else {
          Files.createDirectories(target.getParent());
          Archives.write(zip, target);
        }
        count++;
      }
    }
    return count;
  }

  @Override
  public List<String> names(InputStream raw) throws IOException {
    List<String> names = new ArrayList<>();
    try (ZipArchiveInputStream zip = new ZipArchiveInputStream(raw)) {
      ZipArchiveEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        names.add(entry.getName());
      }
    }
    return List.copyOf(names);
  }
}
