package com.jaspersoft.jrsupgrade.ops.customizations;

import com.jaspersoft.jrsupgrade.core.platform.FileOps;
import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.Change;
import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.ScanEntry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Compares an installed webapp with the vendor's untouched copy for {@code customizations scan}
 * (#72). Invariants: the vendor copy is an unpacked webapp directory, a directory holding one (or
 * its {@code .war}) under the configured webapp name, or the {@code .war} itself, which is read as
 * a stream and never unpacked; files are compared by SHA-256, and an installed file whose size
 * differs from the vendor's is reported without being hashed; logs and caches the server writes at
 * run time, and backup copies an operator left beside an edited file, are ignored; files the
 * installer writes itself are reported as {@link Change#INSTALLER}, whether or not the vendor copy
 * has them, never as customizations; nothing is written anywhere.
 */
final class WebappScanner {

  /** Directories and suffixes the server writes while it runs. */
  private static final List<String> IGNORED_PREFIXES =
      List.of("WEB-INF/logs/", "WEB-INF/cache/", "WEB-INF/work/", "META-INF/maven/");

  private static final List<String> IGNORED_SUFFIXES =
      List.of(".log", ".tmp", ".lck", ".bak", ".orig", ".old", "~");

  /** Backups with a suffix after the marker, such as {@code web.xml.bak-2026-07-30}. */
  private static final List<String> BACKUP_MARKERS = List.of(".bak-", ".bak.", ".bak_", ".orig-");

  /**
   * Files the installer (buildomatic's webapp deployment) fills in with site values: JNDI, JDBC,
   * Hibernate dialect, Quartz and keystore location settings. {@code context.xml}, {@code
   * js.quartz.properties} and {@code classes/keystore.init.properties} were confirmed against a
   * pristine 10.0.0 war and its installation; the other two depend on the repository database.
   */
  static final Set<String> INSTALLER_WRITTEN =
      Set.of(
          "META-INF/context.xml",
          "WEB-INF/classes/keystore.init.properties",
          // the path moved in 8.2 (issue #117); a server has one of the two
          "WEB-INF/classes/hibernate.properties",
          "WEB-INF/hibernate.properties",
          "WEB-INF/js.jdbc.properties",
          "WEB-INF/js.quartz.properties");

  private record Fingerprint(long size, String sha256) {}

  private WebappScanner() {}

  /** Where the vendor's copy of the {@code webappName} webapp is, as a directory or a war. */
  static Path vendorWebapp(Path vendor, String webappName) {
    Path given = vendor.toAbsolutePath().normalize();
    if (Files.isRegularFile(given) && given.getFileName().toString().endsWith(".war")) {
      return given;
    }
    if (Files.isDirectory(given.resolve("WEB-INF"))) {
      return given;
    }
    if (Files.isDirectory(given.resolve(webappName).resolve("WEB-INF"))) {
      return given.resolve(webappName);
    }
    if (Files.isRegularFile(given.resolve(webappName + ".war"))) {
      return given.resolve(webappName + ".war");
    }
    throw new CustomizationException(
        "no " + webappName + " webapp in " + given,
        "point --vendor at the unpacked distribution of the version the server runs, at its "
            + webappName
            + " directory, or at "
            + webappName
            + ".war");
  }

  static List<ScanEntry> compare(Path installed, Path vendor, FileOps files, Set<Path> registered)
      throws IOException {
    Map<String, Fingerprint> theirs =
        Files.isDirectory(vendor) ? directory(vendor, files) : war(vendor);
    List<ScanEntry> out = new ArrayList<>();
    Map<String, Path> ours = new TreeMap<>();
    try (Stream<Path> walk = Files.walk(installed)) {
      walk.filter(Files::isRegularFile).forEach(p -> ours.put(relative(installed, p), p));
    }
    for (Map.Entry<String, Path> e : ours.entrySet()) {
      String rel = e.getKey();
      if (ignored(rel)) {
        continue;
      }
      Path file = e.getValue();
      boolean isRegistered = registered.contains(file.toAbsolutePath().normalize());
      Fingerprint vendorPrint = theirs.get(rel);
      if (vendorPrint == null) {
        Change change = INSTALLER_WRITTEN.contains(rel) ? Change.INSTALLER : Change.ADDED;
        out.add(new ScanEntry(rel, change, Optional.of(file), Optional.empty(), isRegistered));
        continue;
      }
      if (Files.size(file) == vendorPrint.size()
          && files.sha256(file).equals(vendorPrint.sha256())) {
        continue;
      }
      Change change = INSTALLER_WRITTEN.contains(rel) ? Change.INSTALLER : Change.CHANGED;
      out.add(
          new ScanEntry(
              rel, change, Optional.of(file), Optional.of(vendorPrint.sha256()), isRegistered));
    }
    for (Map.Entry<String, Fingerprint> e : theirs.entrySet()) {
      if (!ours.containsKey(e.getKey()) && !ignored(e.getKey())) {
        out.add(
            new ScanEntry(
                e.getKey(),
                Change.REMOVED,
                Optional.empty(),
                Optional.of(e.getValue().sha256()),
                false));
      }
    }
    out.sort(
        (a, b) ->
            a.change() != b.change()
                ? a.change().compareTo(b.change())
                : a.relativePath().compareTo(b.relativePath()));
    return out;
  }

  static boolean ignored(String rel) {
    String lower = rel.toLowerCase(Locale.ROOT);
    return IGNORED_PREFIXES.stream().anyMatch(rel::startsWith)
        || IGNORED_SUFFIXES.stream().anyMatch(lower::endsWith)
        || BACKUP_MARKERS.stream().anyMatch(lower::contains);
  }

  private static String relative(Path root, Path file) {
    return root.relativize(file).toString().replace('\\', '/');
  }

  private static Map<String, Fingerprint> directory(Path dir, FileOps files) throws IOException {
    Map<String, Fingerprint> out = new TreeMap<>();
    List<Path> all;
    try (Stream<Path> walk = Files.walk(dir)) {
      all = walk.filter(Files::isRegularFile).toList();
    }
    for (Path p : all) {
      out.put(relative(dir, p), new Fingerprint(Files.size(p), files.sha256(p)));
    }
    return out;
  }

  private static Map<String, Fingerprint> war(Path war) throws IOException {
    Map<String, Fingerprint> out = new TreeMap<>();
    byte[] buffer = new byte[65536];
    try (InputStream in = Files.newInputStream(war);
        ZipInputStream zip = new ZipInputStream(in)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        MessageDigest md = sha256();
        long size = 0;
        DigestInputStream digest = new DigestInputStream(zip, md);
        int read;
        while ((read = digest.read(buffer)) != -1) {
          size += read;
        }
        out.put(entry.getName(), new Fingerprint(size, HexFormat.of().formatHex(md.digest())));
      }
    }
    return out;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is mandatory in every JRE", e);
    }
  }
}
