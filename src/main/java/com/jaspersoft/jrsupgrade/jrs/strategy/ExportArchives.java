package com.jaspersoft.jrsupgrade.jrs.strategy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * What an export archive must hold to be one: a JasperReports Server export always carries {@code
 * index.xml} at its root, and an export whose URIs matched nothing holds only a {@code resources/}
 * entry (field test 2, E3: such an archive used to be reported as a success). Invariants: the
 * archive is read as a stream and never held in memory; a file that yields no ZIP entries at all is
 * not judged here (the step's size check reports it), only an archive with entries and no {@code
 * index.xml}.
 */
public final class ExportArchives {

  static final String INDEX = "index.xml";

  private ExportArchives() {}

  /** Why {@code archive} is not a usable export, or empty when it holds {@code index.xml}. */
  public static Optional<String> problem(Path archive) {
    int entries = 0;
    String first = "";
    try (InputStream in = Files.newInputStream(archive);
        ZipInputStream zip = new ZipInputStream(in)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.getName().equals(INDEX)) {
          return Optional.empty();
        }
        if (entries == 0) {
          first = entry.getName();
        }
        entries++;
      }
    } catch (IOException e) {
      return Optional.empty();
    }
    if (entries == 0) {
      return Optional.empty();
    }
    return Optional.of(
        archive
            + " holds no "
            + INDEX
            + " ("
            + entries
            + " entr"
            + (entries == 1 ? "y" : "ies")
            + ", first "
            + first
            + "): no resource matched the export");
  }
}
