package com.jaspersoft.jrsupgrade.ops.customizations;

import com.jaspersoft.jrsupgrade.core.state.Customization;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry of operator-customised files (spec §10.3): registering snapshots the file and records
 * its hash as "original", so that an upgrade can decide between automatic re-application and a
 * reported conflict (spec §10.2 step 13). Invariants: only files under {@code server.installDir} or
 * {@code server.tomcatDir} are accepted; a registered customization's snapshot is
 * retention-protected until it is unregistered; every mutation is audited; nothing here touches the
 * server.
 */
public interface CustomizationOperations {

  /** The webapp directory whose files are an overlay rather than files (issue #117). */
  String SCRIPTS_PREFIX = "scripts/";

  /**
   * Issue #117 (vendor review §5.2, the JavaScript customisation deck): why a per-file comparison
   * of {@code scripts/} cannot work and what to do instead.
   */
  String SCRIPTS_ADVICE =
      "scripts/ is an overlay, not a set of files an upgrade can compare: since 8.0 the front end"
          + " is the jasperserver-ui webpack project, a customised scripts tree is rebuilt and"
          + " copied whole under hashed bundle names, so a per-file comparison always reports a"
          + " conflict. Carry the change in your jasperserver-ui project, rebuild it, and copy the"
          + " result over the new webapp (operator guide, JavaScript customisations)";

  /** Result of {@link #diff(Path)}: the registered copy against the file on disk. */
  record Diff(
      Path path,
      String originalSha256,
      String registeredSha256,
      String currentSha256,
      boolean identical,
      List<String> lines) {
    public Diff {
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(originalSha256, "originalSha256");
      Objects.requireNonNull(registeredSha256, "registeredSha256");
      Objects.requireNonNull(currentSha256, "currentSha256");
      lines = List.copyOf(lines);
    }
  }

  /** Registers with the file's current hash as the "original" (spec §10.3). */
  default Customization register(Path path) {
    return register(path, java.util.Optional.empty());
  }

  /**
   * Registers {@code path}; when {@code pristineCopy} names a file (for example the vendor's
   * unmodified version from the distribution) its hash is recorded as the "original" so the
   * upgrade's 3-way comparison can tell "unchanged by the vendor" from "customized".
   */
  Customization register(Path path, java.util.Optional<Path> pristineCopy);

  /** How a file of the installed webapp differs from the vendor's copy (#72). */
  enum Change {
    /** Present in both and different: an operator's change. */
    CHANGED,
    /** Only in the installation. */
    ADDED,
    /** Different, but a file the installer fills in with site values; not a customization. */
    INSTALLER,
    /** Only in the vendor's copy. */
    REMOVED
  }

  /** One file of {@link #scan}; {@code installed} is empty for a removed file. */
  record ScanEntry(
      String relativePath,
      Change change,
      Optional<Path> installed,
      Optional<String> vendorSha256,
      boolean registered) {
    public ScanEntry {
      Objects.requireNonNull(relativePath, "relativePath");
      Objects.requireNonNull(change, "change");
      Objects.requireNonNull(installed, "installed");
      Objects.requireNonNull(vendorSha256, "vendorSha256");
    }
  }

  /** What kind of Tomcat-side file a {@link TomcatEntry} is (issue #117). */
  enum TomcatKind {
    /** {@code bin/setenv.sh} or {@code bin/setenv.bat}: JAVA_OPTS, heap, {@code --add-opens}. */
    SETENV,
    /** {@code conf/server.xml}: connectors, ports, TLS. */
    SERVER_XML,
    /** {@code conf/Catalina/localhost/*.xml}: a context fragment, such as a data source. */
    CONTEXT_FRAGMENT,
    /** A {@code lib/*.jar} Tomcat does not ship: a JDBC driver or another site addition. */
    LIBRARY
  }

  /**
   * One Tomcat-side file the upgrade guides say to carry over; {@code relativePath} is under the
   * Tomcat.
   */
  record TomcatEntry(String relativePath, TomcatKind kind, boolean registered) {
    public TomcatEntry {
      Objects.requireNonNull(relativePath, "relativePath");
      Objects.requireNonNull(kind, "kind");
    }
  }

  /**
   * The Tomcat-side files an upgrade does not carry over by itself (issue #117): {@code
   * bin/setenv.*}, {@code conf/server.xml}, {@code conf/Catalina/localhost/*.xml} and the {@code
   * lib} jars Tomcat does not ship. There is no pristine Tomcat to compare with, so this lists what
   * to carry over, not what changed; read-only.
   */
  List<TomcatEntry> scanTomcat();

  /**
   * A warning to show when {@code path} is registered, for a file that cannot be reconciled per
   * file: one under the webapp's {@code scripts/} overlay (issue #117); empty otherwise.
   */
  Optional<String> registrationAdvice(Path path);

  /** The installed webapp against the vendor's copy. */
  record Scan(Path installedWebapp, Path vendorWebapp, List<ScanEntry> entries) {
    public Scan {
      Objects.requireNonNull(installedWebapp, "installedWebapp");
      Objects.requireNonNull(vendorWebapp, "vendorWebapp");
      entries = List.copyOf(entries);
    }
  }

  /**
   * Compares the installed webapp with the vendor's untouched copy ({@code vendor}: an unpacked
   * distribution, its webapp directory or its war) and lists what differs; read-only (#72).
   */
  Scan scan(Path vendor);

  /** What {@link #assess} says to do with a jar the site added or patched (issue #4). */
  enum JarVerdict {
    /** The target ships the same artifact, as new or newer. */
    DROP,
    /** The target ships nothing like it: keep it as the site's own dependency. */
    KEEP,
    /** A named rule of the matrix replaces it with something else. */
    REPLACE,
    /** The target ships the same artifact, older: two versions would be on the class path. */
    REVIEW,
    /** No coordinates to judge by. */
    UNRESOLVED
  }

  /** One jar of {@link Findings}: its file name, what it says it is, and the verdict. */
  record JarFinding(
      String jar,
      Optional<String> coordinates,
      JarVerdict verdict,
      Optional<String> targetJar,
      String reason) {
    public JarFinding {
      Objects.requireNonNull(jar, "jar");
      Objects.requireNonNull(coordinates, "coordinates");
      Objects.requireNonNull(verdict, "verdict");
      Objects.requireNonNull(targetJar, "targetJar");
      Objects.requireNonNull(reason, "reason");
    }
  }

  /**
   * What becomes of a scan's changed and added files on the target version (ADR-0003): {@code
   * sourceVersion} is the running version, {@code targetVersion} the target's.
   */
  record Findings(
      Path targetWebapp, String sourceVersion, String targetVersion, List<JarFinding> jars) {
    public Findings {
      Objects.requireNonNull(targetWebapp, "targetWebapp");
      Objects.requireNonNull(sourceVersion, "sourceVersion");
      Objects.requireNonNull(targetVersion, "targetVersion");
      jars = List.copyOf(jars);
    }
  }

  /**
   * Judges {@code scan}'s changed and added files against the target distribution {@code target}
   * (its unpacked directory, webapp directory or WAR) with the matrix's rules; read-only. {@code
   * targetVersion} overrides the version the target states, and is required when it states none.
   */
  Findings assess(Scan scan, Path target, Optional<String> targetVersion);

  /**
   * Registers every {@link Change#CHANGED} and {@link Change#ADDED} file of {@code scan} that is
   * not registered yet: a changed file with the vendor's hash as its original, an added one with
   * its own; returns the new registrations.
   */
  List<Customization> registerScan(Scan scan);

  boolean unregister(Path path);

  List<Customization> list();

  Diff diff(Path path);
}
