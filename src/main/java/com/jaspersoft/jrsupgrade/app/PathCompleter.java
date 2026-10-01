package com.jaspersoft.jrsupgrade.app;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

/**
 * Filesystem path completion for a single-path prompt (#102, ADR-0037). Invariant: {@code complete}
 * never throws; a directory it cannot list (permission, symlink cycle, a typed prefix that is not a
 * directory) yields no candidates rather than failing the prompt. Matching is case-insensitive so a
 * Windows path completes regardless of the case the operator typed, and each candidate carries the
 * platform separator so completion can continue into a matched directory.
 */
final class PathCompleter implements Completer {

  @Override
  public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
    String typed = line.line().substring(0, Math.min(line.cursor(), line.line().length()));
    boolean trailingSeparator = typed.endsWith("/") || typed.endsWith("\\");

    Path directory;
    String basePrefix; // what precedes the file name in `typed`, kept verbatim in every candidate
    String prefix; // the file name fragment to match against directory entries
    if (typed.isEmpty()) {
      directory = Path.of(".");
      basePrefix = "";
      prefix = "";
    } else if (trailingSeparator) {
      directory = Path.of(typed);
      basePrefix = typed;
      prefix = "";
    } else {
      Path typedPath = Path.of(typed);
      Path parent = typedPath.getParent();
      directory = parent == null ? Path.of(".") : parent;
      Path name = typedPath.getFileName();
      prefix = name == null ? "" : name.toString();
      basePrefix = typed.substring(0, typed.length() - prefix.length());
    }

    if (!Files.isDirectory(directory)) {
      return;
    }
    String prefixLower = prefix.toLowerCase(Locale.ROOT);
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
      for (Path entry : entries) {
        String name = entry.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).startsWith(prefixLower)) {
          continue;
        }
        boolean isDirectory = Files.isDirectory(entry);
        String value = basePrefix + name + (isDirectory ? "/" : "");
        candidates.add(
            new Candidate(value, name + (isDirectory ? "/" : ""), null, null, null, null, false));
      }
    } catch (IOException e) {
      // no candidates from a directory that cannot be listed
    }
  }
}
