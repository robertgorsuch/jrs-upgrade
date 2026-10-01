package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.state.HotfixInstalled;
import com.jaspersoft.jrsupgrade.core.state.HotfixState;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.semver4j.Semver;

/**
 * Issue #9: hotfix bundles and their jars are sometimes labelled with a version the product never
 * reached (NGRA ran 8.2.0 with jars and a cumulative bundle labelled 8.2.6), and nobody can then
 * say what is running. This compares the labels with the version {@code serverInfo} reports and
 * says which one is the running version. Invariants: read-only; a label is reported only when it
 * differs from the server's version; the server's version is always named as the running one,
 * because it is the one the vendor scripts and the compatibility matrix judge; an unreadable {@code
 * WEB-INF/lib} yields no lines rather than a failure.
 */
final class HotfixLabels {

  /**
   * {@code jasperserver[-anything]-X.Y.Z[-suffix].jar}: the vendor's own jars and the hotfix jars
   * that replace them; third-party jars (jasperreports, spring) carry versions of their own.
   */
  private static final Pattern JAR =
      Pattern.compile("^jasperserver[\\w.-]*?-(\\d+\\.\\d+\\.\\d+)(?:[-.][\\w.-]*)?\\.jar$");

  /** Semantic version order, unparsable versions last in text order. */
  static final Comparator<String> VERSION_ORDER =
      (a, b) -> {
        Semver x = Semver.coerce(a);
        Semver y = Semver.coerce(b);
        if (x != null && y != null && x.compareTo(y) != 0) {
          return x.compareTo(y);
        }
        return a.compareTo(b);
      };

  private HotfixLabels() {}

  /** The version a vendor jar's file name states, if it is one. */
  static Optional<String> versionOfJar(String fileName) {
    Matcher m = JAR.matcher(fileName.toLowerCase(Locale.ROOT));
    return m.matches() ? Optional.of(m.group(1)) : Optional.empty();
  }

  /**
   * One line per label that differs from {@code serverVersion}: jar names in {@code webappDir}'s
   * {@code WEB-INF/lib}, then the recorded hotfixes still installed.
   */
  static List<String> mismatches(
      Path webappDir, String serverVersion, List<HotfixInstalled> recorded) {
    List<String> out = new ArrayList<>();
    Map<String, TreeSet<String>> jarsByLabel = new TreeMap<>(VERSION_ORDER);
    Path lib = webappDir.resolve("WEB-INF").resolve("lib");
    if (Files.isDirectory(lib)) {
      try (DirectoryStream<Path> jars = Files.newDirectoryStream(lib, "*.jar")) {
        for (Path jar : jars) {
          String name = jar.getFileName().toString();
          versionOfJar(name)
              .filter(v -> !same(v, serverVersion))
              .ifPresent(v -> jarsByLabel.computeIfAbsent(v, k -> new TreeSet<>()).add(name));
        }
      } catch (IOException e) {
        // an unreadable lib directory is the doctor's to report; no label can be compared
      }
    }
    for (Map.Entry<String, TreeSet<String>> e : jarsByLabel.entrySet()) {
      List<String> names = new ArrayList<>(e.getValue());
      String shown =
          names.size() <= 3
              ? String.join(", ", names)
              : String.join(", ", names.subList(0, 3)) + " and " + (names.size() - 3) + " more";
      out.add(
          lib
              + " holds "
              + names.size()
              + " vendor jar(s) labelled "
              + e.getKey()
              + " ("
              + shown
              + ") while the server reports "
              + serverVersion
              + ": "
              + running(serverVersion, e.getKey()));
    }
    for (HotfixInstalled h : recorded) {
      if (h.state() == HotfixState.INSTALLED && !same(h.version(), serverVersion)) {
        out.add(
            "hotfix "
                + h.id()
                + " ("
                + h.title()
                + ") is labelled "
                + h.version()
                + " while the server reports "
                + serverVersion
                + ": "
                + running(serverVersion, h.version()));
      }
    }
    return out;
  }

  private static String running(String serverVersion, String label) {
    return "the running version is "
        + serverVersion
        + " (serverInfo); "
        + label
        + " is a hotfix label, and the upgrade path is judged from "
        + serverVersion;
  }

  private static boolean same(String a, String b) {
    Semver x = Semver.coerce(a);
    Semver y = Semver.coerce(b);
    return (x != null && y != null) ? x.isEqualTo(y) : a.strip().equals(b.strip());
  }
}
