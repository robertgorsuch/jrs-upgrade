package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsupgrade.jrs.vendor.MasterProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The vendor's own troubleshooting files for the support bundle (review §3.4, issue #111): the
 * vendor's first instruction is the buildomatic script log, then {@code jasperserver.log} and
 * Tomcat's {@code catalina} log, plus the installer's {@code installation.log} and the {@code
 * default_master.properties} the vendor scripts ran with. Invariants: pure path arithmetic from the
 * configured installation, nothing is read here except directory listings; a file that is not there
 * yields no source rather than an error; every file the bundle writes from a source is tail-capped
 * by bytes and streamed line by line, never held whole; password keys of the master properties are
 * blanked before the redactor sees the line, so a password never depends on being registered as a
 * secret.
 */
public final class VendorLogs {

  /** The tail of each vendor file that goes into the bundle. */
  public static final long TAIL_BYTES = 5L << 20;

  static final String ENTRY_PREFIX = "vendor/";
  static final String BLANKED = "<blanked>";

  /** One file to bundle: the zip entry name, the file, and whether password keys are blanked. */
  public record Source(String entry, Path file, boolean masterProperties) {}

  private VendorLogs() {}

  /**
   * The files that exist for this installation, in the vendor's troubleshooting order. Any location
   * may be absent (a jrs-upgrade that reaches the server over REST only names none).
   */
  public static List<Source> locate(
      Optional<Path> installDir,
      Optional<Path> tomcatDir,
      Optional<Path> webappDir,
      Optional<Path> buildomaticDir) {
    List<Source> sources = new ArrayList<>();
    buildomaticDir
        .flatMap(b -> newest(b.resolve("logs"), BUILDOMATIC_GLOB))
        .ifPresent(
            p ->
                sources.add(new Source(ENTRY_PREFIX + "buildomatic/" + p.getFileName(), p, false)));
    webappDir
        .map(w -> w.resolve("WEB-INF").resolve("logs").resolve("jasperserver.log"))
        .filter(Files::isRegularFile)
        .ifPresent(p -> sources.add(new Source(ENTRY_PREFIX + "jasperserver.log", p, false)));
    tomcatDir
        .flatMap(VendorLogs::catalinaLog)
        .ifPresent(p -> sources.add(new Source(ENTRY_PREFIX + p.getFileName(), p, false)));
    installDir
        .map(i -> i.resolve("installation.log"))
        .filter(Files::isRegularFile)
        .ifPresent(p -> sources.add(new Source(ENTRY_PREFIX + "installation.log", p, false)));
    buildomaticDir
        .map(b -> b.resolve(Buildomatic.MASTER_PROPERTIES))
        .filter(Files::isRegularFile)
        .ifPresent(
            p -> sources.add(new Source(ENTRY_PREFIX + Buildomatic.MASTER_PROPERTIES, p, true)));
    return List.copyOf(sources);
  }

  /** {@code catalina.out} (Linux), else the newest {@code catalina.<date>.log} (Windows). */
  static Optional<Path> catalinaLog(Path tomcatDir) {
    Path logs = tomcatDir.resolve("logs");
    Path out = logs.resolve("catalina.out");
    if (Files.isRegularFile(out)) {
      return Optional.of(out);
    }
    return newest(logs, "catalina.*.log");
  }

  /** The buildomatic script logs: one file per vendor run, named by script, date, time and pid. */
  static final String BUILDOMATIC_GLOB = "js-*.log";

  /** The most recently modified regular file matching the glob, if any. */
  static Optional<Path> newest(Path dir, String glob) {
    return newestBetween(dir, glob, Instant.MIN, Instant.MAX);
  }

  /**
   * The most recently modified regular file matching the glob whose last modification lies between
   * {@code from} and {@code to}, inclusive (#161: a buildomatic log of the run being bundled).
   */
  static Optional<Path> newestBetween(Path dir, String glob, Instant from, Instant to) {
    if (!Files.isDirectory(dir)) {
      return Optional.empty();
    }
    Path best = null;
    FileTime bestTime = null;
    try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, glob)) {
      for (Path f : files) {
        if (!Files.isRegularFile(f)) {
          continue;
        }
        FileTime t = Files.getLastModifiedTime(f);
        Instant at = t.toInstant();
        if (at.isBefore(from) || at.isAfter(to)) {
          continue;
        }
        if (bestTime == null || t.compareTo(bestTime) > 0) {
          best = f;
          bestTime = t;
        }
      }
    } catch (IOException e) {
      return Optional.empty();
    }
    return Optional.ofNullable(best);
  }

  /**
   * Streams the last {@code cap} bytes of the file to {@code line}, whole lines only: when the file
   * is longer, the partial first line is dropped and a note says how much was omitted.
   */
  public static void tail(Path file, long cap, Consumer<String> line) throws IOException {
    long size = Files.size(file);
    long skip = Math.max(0, size - cap);
    try (SeekableByteChannel channel = Files.newByteChannel(file);
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8))) {
      if (skip > 0) {
        channel.position(skip);
        line.accept("... earlier " + skip + " bytes omitted; this is the tail of " + file);
        // the byte position lands mid-line; drop the partial line
        reader.readLine();
      }
      String l;
      while ((l = reader.readLine()) != null) {
        line.accept(l);
      }
    }
  }

  /** A {@code default_master.properties} line with a password key's value blanked. */
  public static String blankPassword(String line) {
    String trimmed = line.stripLeading();
    if (trimmed.startsWith("#") || trimmed.startsWith("!")) {
      return line;
    }
    int sep = indexOfSeparator(trimmed);
    if (sep < 0) {
      return line;
    }
    String key = trimmed.substring(0, sep).strip();
    return MasterProperties.isPasswordKey(key) ? key + "=" + BLANKED : line;
  }

  private static int indexOfSeparator(String line) {
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '=' || c == ':' || c == ' ' || c == '\t') {
        return i;
      }
      if (c == '\\') {
        i++;
      }
    }
    return -1;
  }
}
