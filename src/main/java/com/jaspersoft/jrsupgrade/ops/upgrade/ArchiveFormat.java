package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.engine.CancellationToken;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * What a backup archive is made of. Invariant: the format is chosen once, from the operating
 * system, and every difference between tar.gz and zip lives behind this interface instead of in a
 * switch inside each of create, extract and entries (roadmap item 17). A sealed type, so adding a
 * third format is a compile error everywhere it has to be handled rather than a silently missing
 * case. The shared rules, what a safe entry name is and how bytes are copied, stay in {@link
 * Archives}: they are the same whatever the container.
 */
sealed interface ArchiveFormat permits TarGzFormat, ZipFormat {

  /** The suffix an archive of this format carries, {@code .tar.gz} or {@code .zip}. */
  String extension();

  /** Writes every file under {@code root} and answers how many entries it wrote. */
  long write(Path root, OutputStream raw, CancellationToken cancel) throws IOException;

  /** Reads {@code raw} into {@code root} and answers how many entries it read. */
  long read(InputStream raw, Path root, CancellationToken cancel) throws IOException;

  /** The entry names, in archive order. */
  List<String> names(InputStream raw) throws IOException;

  /** The format this operating system's backups use (spec §10.2 step 6). */
  static ArchiveFormat of(Platform.OsFamily os) {
    return switch (os) {
      case LINUX -> new TarGzFormat();
      case WINDOWS -> new ZipFormat();
    };
  }
}
