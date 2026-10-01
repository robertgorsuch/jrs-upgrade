package com.jaspersoft.jrsupgrade.ops.customizations;

import com.jaspersoft.jrsupgrade.core.compat.UpgradeRules;
import com.jaspersoft.jrsupgrade.core.platform.Trees;
import com.jaspersoft.jrsupgrade.core.snapshot.Snapshot;
import com.jaspersoft.jrsupgrade.core.snapshot.SnapshotManifest;
import com.jaspersoft.jrsupgrade.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsupgrade.core.state.AuditActor;
import com.jaspersoft.jrsupgrade.core.state.Customization;
import com.jaspersoft.jrsupgrade.core.state.SnapshotRecord;
import com.jaspersoft.jrsupgrade.core.state.StateStore;
import com.jaspersoft.jrsupgrade.ops.JrsVersion;
import com.jaspersoft.jrsupgrade.ops.Services;
import com.jaspersoft.jrsupgrade.ops.hotfix.HotfixException;
import com.jaspersoft.jrsupgrade.ops.hotfix.HotfixPaths;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Default {@link CustomizationOperations} over the state store and the snapshot store. Invariants:
 * the snapshot of a customization lives under {@code snapshots/cust-<hash>/file} where {@code hash}
 * derives from the normalised path, so the same file always maps to the same snapshot; the {@code
 * customizations} row carries {@code original_sha256} and the snapshot ref; a matching {@code
 * snapshots} row with {@code referenced_by = 'customization'} keeps retention pruning away from it;
 * paths outside {@code installDir}/{@code tomcatDir} are refused before anything is written.
 */
public final class DefaultCustomizationOperations implements CustomizationOperations {

  public static final String RUN_PREFIX = "cust-";
  public static final String STEP = "file";
  public static final String REFERENCED_BY = "customization";
  static final String AUDIT_REGISTERED = "customizations.registered";
  static final String AUDIT_UNREGISTERED = "customizations.unregistered";
  private static final int HASH_CHARS = 16;

  private final Services services;
  private final SnapshotStore snapshots;

  public DefaultCustomizationOperations(Services services) {
    this(
        services,
        new SnapshotStore(services.home(), services.platform().files(), services.clock()));
  }

  public DefaultCustomizationOperations(Services services, SnapshotStore snapshots) {
    this.services = Objects.requireNonNull(services, "services");
    this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
  }

  @Override
  public Customization register(Path path, Optional<Path> pristineCopy) {
    Path file = accepted(path);
    StateStore store = services.stateStore().get();
    Optional<Customization> existing = find(store, file);
    if (existing.isPresent()) {
      throw new CustomizationException(
          file + " is already registered (original " + existing.get().originalSha256() + ")",
          "run jrs-upgrade customizations unregister " + file + " first to re-register it");
    }
    if (pristineCopy.isPresent() && !Files.isRegularFile(pristineCopy.get())) {
      throw new CustomizationException(
          "pristine copy " + pristineCopy.get() + " is not a regular file",
          "point --original at the vendor's unmodified version of the file");
    }
    try {
      String sha =
          pristineCopy.isPresent()
              ? services.platform().files().sha256(pristineCopy.get())
              : services.platform().files().sha256(file);
      return record(file, sha, pristineCopy.map(p -> " (from " + p + ")").orElse(""));
    } catch (IOException e) {
      throw new CustomizationException(
          "cannot read " + pristineCopy.orElse(file) + ": " + e.getMessage(),
          "check that the file is readable",
          e);
    }
  }

  @Override
  public Scan scan(Path vendor) {
    Objects.requireNonNull(vendor, "vendor");
    HotfixPaths paths;
    try {
      paths = HotfixPaths.from(services.config(), services.platform());
    } catch (HotfixException e) {
      throw new CustomizationException(e.getMessage(), e.remediation(), e);
    }
    String webappName =
        services
            .config()
            .server()
            .webappName()
            .map(com.jaspersoft.jrsupgrade.core.config.Config.WebappName::yamlValue)
            .orElse("jasperserver-pro");
    Path installed = paths.tomcatDir().resolve("webapps").resolve(webappName);
    if (!Files.isDirectory(installed)) {
      throw new CustomizationException(
          "the installed webapp " + installed + " is not a directory",
          "check server.tomcatDir and server.webappName");
    }
    Path vendorWebapp = WebappScanner.vendorWebapp(vendor, webappName);
    java.util.Set<Path> registered = new java.util.HashSet<>();
    for (Customization c : list()) {
      registered.add(normalise(c.path()));
    }
    try {
      return new Scan(
          installed,
          vendorWebapp,
          WebappScanner.compare(installed, vendorWebapp, services.platform().files(), registered));
    } catch (IOException e) {
      throw new CustomizationException(
          "cannot compare " + installed + " with " + vendorWebapp + ": " + e.getMessage(),
          "check that both are readable",
          e);
    }
  }

  @Override
  public Findings assess(Scan scan, Path target, Optional<String> targetVersion) {
    Objects.requireNonNull(scan, "scan");
    Objects.requireNonNull(target, "target");
    Path targetWebapp = WebappScanner.vendorWebapp(target, webappName());
    String source =
        version(scan.vendorWebapp())
            .or(() -> JrsVersion.ofWebapp(scan.installedWebapp()))
            .orElseThrow(
                () ->
                    new CustomizationException(
                        "cannot tell the running version from "
                            + scan.vendorWebapp()
                            + " or "
                            + scan.installedWebapp(),
                        "point --vendor at the unpacked distribution of the running version"));
    String to =
        targetVersion
            .or(() -> JrsVersion.ofDistributionDir(target))
            .or(() -> version(targetWebapp))
            .orElseThrow(
                () ->
                    new CustomizationException(
                        "cannot tell the target version from " + target, "pass --to <version>"));
    UpgradeRules rules = services.matrix().rules();
    try {
      PackageIndex index = PackageIndex.read(targetWebapp);
      List<Path> jars = new ArrayList<>();
      Map<String, List<Path>> code = new java.util.LinkedHashMap<>();
      for (ScanEntry e : scan.entries()) {
        if ((e.change() != Change.ADDED && e.change() != Change.CHANGED)
            || e.installed().isEmpty()) {
          continue;
        }
        if (PackageIndex.isLibJar(e.relativePath())) {
          jars.add(e.installed().get());
          code.put(e.installed().get().getFileName().toString(), List.of(e.installed().get()));
        } else if (e.relativePath().startsWith(CLASSES) && e.relativePath().endsWith(".class")) {
          code.computeIfAbsent(CLASSES_NAME, k -> new ArrayList<>()).add(e.installed().get());
        }
      }
      VendorClassCheck.Result classes = VendorClassCheck.check(code, index, to);
      return new Findings(
          targetWebapp,
          source,
          to,
          JarRetirement.judge(jars, index, rules.jarRules(source, to)),
          classes.classes(),
          classes.jakarta());
    } catch (IOException e) {
      throw new CustomizationException(
          "cannot read the target " + targetWebapp + ": " + e.getMessage(),
          "check that the target distribution is complete and readable",
          e);
    }
  }

  static final String CLASSES = "WEB-INF/classes/";
  static final String CLASSES_NAME = "WEB-INF/classes";

  /** The version a webapp directory's jars or a WAR's name or directory states. */
  private static Optional<String> version(Path webapp) {
    return Files.isDirectory(webapp)
        ? JrsVersion.ofWebapp(webapp).or(() -> JrsVersion.ofDistributionDir(webapp.getParent()))
        : JrsVersion.ofArtifactName(webapp)
            .or(() -> JrsVersion.ofDistributionDir(webapp.getParent()));
  }

  private String webappName() {
    return services
        .config()
        .server()
        .webappName()
        .map(com.jaspersoft.jrsupgrade.core.config.Config.WebappName::yamlValue)
        .orElse("jasperserver-pro");
  }

  @Override
  public List<TomcatEntry> scanTomcat() {
    HotfixPaths paths;
    try {
      paths = HotfixPaths.from(services.config(), services.platform());
    } catch (HotfixException e) {
      throw new CustomizationException(e.getMessage(), e.remediation(), e);
    }
    java.util.Set<Path> registered = new java.util.HashSet<>();
    for (Customization c : list()) {
      registered.add(normalise(c.path()));
    }
    try {
      return TomcatScanner.scan(paths.tomcatDir().toAbsolutePath().normalize(), registered);
    } catch (IOException e) {
      throw new CustomizationException(
          "cannot list " + paths.tomcatDir() + ": " + e.getMessage(),
          "check that server.tomcatDir is readable",
          e);
    }
  }

  @Override
  public Optional<String> registrationAdvice(Path path) {
    Objects.requireNonNull(path, "path");
    HotfixPaths paths;
    try {
      paths = HotfixPaths.from(services.config(), services.platform());
    } catch (HotfixException e) {
      return Optional.empty();
    }
    String webappName =
        services
            .config()
            .server()
            .webappName()
            .map(com.jaspersoft.jrsupgrade.core.config.Config.WebappName::yamlValue)
            .orElse("jasperserver-pro");
    Path scripts =
        normalise(paths.tomcatDir().resolve("webapps").resolve(webappName).resolve("scripts"));
    return normalise(path).startsWith(scripts) ? Optional.of(SCRIPTS_ADVICE) : Optional.empty();
  }

  @Override
  public List<Customization> registerScan(Scan scan) {
    Objects.requireNonNull(scan, "scan");
    List<Customization> out = new java.util.ArrayList<>();
    for (ScanEntry e : scan.entries()) {
      if (e.registered() || e.installed().isEmpty()) {
        continue;
      }
      Path file = accepted(e.installed().get());
      if (find(services.stateStore().get(), file).isPresent()) {
        continue;
      }
      try {
        switch (e.change()) {
          case CHANGED ->
              out.add(
                  record(
                      file,
                      e.vendorSha256().orElseThrow(),
                      " (vendor copy in " + scan.vendorWebapp() + ")"));
          case ADDED ->
              out.add(
                  record(file, services.platform().files().sha256(file), " (added by the site)"));
          case INSTALLER, REMOVED -> {
            // not a customization to carry across upgrades
          }
        }
      } catch (IOException ex) {
        throw new CustomizationException(
            "cannot read " + file + ": " + ex.getMessage(), "check that the file is readable", ex);
      }
    }
    return out;
  }

  /** Snapshots {@code file} and records {@code originalSha256} for it; {@code note} is audited. */
  private Customization record(Path file, String originalSha256, String note) {
    StateStore store = services.stateStore().get();
    String sha = originalSha256;
    String runId = runIdFor(file);
    try {
      Snapshot snapshot = snapshots.create(runId, STEP, List.of(file), file.getParent());
      String ref = runId + "/" + STEP;
      Customization customization =
          new Customization(file, sha, Optional.of(ref), services.clock().instant());
      store.registerCustomization(customization);
      store.recordSnapshot(
          new SnapshotRecord(
              ref,
              runId,
              STEP,
              snapshot.dir(),
              services.platform().files().sha256(snapshot.manifestFile()),
              Optional.of(REFERENCED_BY)));
      store.audit(actor(), AUDIT_REGISTERED, file + " original sha256 " + sha + note);
      return customization;
    } catch (IOException e) {
      throw new CustomizationException(
          "cannot snapshot " + file + ": " + e.getMessage(),
          "check that the file is readable and " + services.home().snapshots() + " is writable",
          e);
    }
  }

  @Override
  public boolean unregister(Path path) {
    Path file = normalise(path);
    StateStore store = services.stateStore().get();
    Optional<Customization> existing = find(store, file);
    if (existing.isEmpty()) {
      return false;
    }
    store.unregisterCustomization(existing.get().path());
    String runId = runIdFor(file);
    try {
      Trees.deleteRecursively(services.home().snapshots().resolve(runId));
    } catch (IOException e) {
      throw new CustomizationException(
          "row removed but the snapshot under "
              + services.home().snapshots().resolve(runId)
              + " could not be deleted: "
              + e.getMessage(),
          "delete that directory by hand",
          e);
    }
    store.deleteSnapshotsOf(runId);
    store.audit(actor(), AUDIT_UNREGISTERED, file.toString());
    return true;
  }

  @Override
  public List<Customization> list() {
    return services.stateStore().get().customizations();
  }

  @Override
  public Diff diff(Path path) {
    Path file = normalise(path);
    StateStore store = services.stateStore().get();
    Customization c =
        find(store, file)
            .orElseThrow(
                () ->
                    new CustomizationException(
                        file + " is not registered",
                        "run jrs-upgrade customizations register " + file + " first"));
    try {
      Snapshot snapshot =
          snapshots
              .find(runIdFor(file), STEP)
              .orElseThrow(
                  () ->
                      new CustomizationException(
                          "snapshot of "
                              + file
                              + " is missing under "
                              + services.home().snapshots(),
                          "unregister and register the file again"));
      snapshots.verify(snapshot);
      SnapshotManifest.Entry entry = snapshot.manifest().entries().get(0);
      Path registered = snapshot.payloadFile(entry);
      if (!Files.isRegularFile(file)) {
        return new Diff(
            file,
            c.originalSha256(),
            entry.sha256(),
            "absent",
            false,
            List.of("--- " + registered, "+++ " + file + " (absent)"));
      }
      String current = services.platform().files().sha256(file);
      if (current.equals(entry.sha256())) {
        return new Diff(file, c.originalSha256(), entry.sha256(), current, true, List.of());
      }
      return new Diff(
          file,
          c.originalSha256(),
          entry.sha256(),
          current,
          false,
          diffLines(registered, file, "registered:" + file, "current:" + file));
    } catch (IOException e) {
      throw new CustomizationException(
          "cannot compare " + file + ": " + e.getMessage(), "check that the file is readable", e);
    }
  }

  /** Unified diff of two files, or a one-line notice when either is binary. */
  public static List<String> diffLines(Path a, Path b, String aName, String bName)
      throws IOException {
    if (binary(a) || binary(b)) {
      return List.of("Binary files " + aName + " and " + bName + " differ");
    }
    return UnifiedDiff.of(
        Files.readAllLines(a, StandardCharsets.UTF_8),
        Files.readAllLines(b, StandardCharsets.UTF_8),
        aName,
        bName);
  }

  static boolean binary(Path file) throws IOException {
    byte[] buffer = new byte[8192];
    try (InputStream in = Files.newInputStream(file)) {
      int read = in.read(buffer);
      for (int i = 0; i < read; i++) {
        if (buffer[i] == 0) {
          return true;
        }
      }
    }
    return false;
  }

  /** {@code cust-<16 hex chars>} derived from the normalised path (case-folded on Windows). */
  public static String runIdFor(Path file) {
    String key = normalise(file).toString();
    if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
      key = key.toLowerCase(Locale.ROOT);
    }
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      String hex = HexFormat.of().formatHex(md.digest(key.getBytes(StandardCharsets.UTF_8)));
      return RUN_PREFIX + hex.substring(0, HASH_CHARS);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private Path accepted(Path path) {
    Path file = normalise(path);
    if (!Files.isRegularFile(file)) {
      throw new CustomizationException(
          file + " is not a regular file", "register an existing file of the installation");
    }
    HotfixPaths paths;
    try {
      paths = HotfixPaths.from(services.config(), services.platform());
    } catch (HotfixException e) {
      throw new CustomizationException(e.getMessage(), e.remediation(), e);
    }
    if (!file.startsWith(paths.installDir()) && !file.startsWith(paths.tomcatDir())) {
      throw new CustomizationException(
          file
              + " is outside the installation ("
              + paths.installDir()
              + ", "
              + paths.tomcatDir()
              + ")",
          "only files under server.installDir or server.tomcatDir can be registered");
    }
    return file;
  }

  private static Optional<Customization> find(StateStore store, Path file) {
    for (Customization c : store.customizations()) {
      if (normalise(c.path()).equals(file)) {
        return Optional.of(c);
      }
    }
    return Optional.empty();
  }

  private static Path normalise(Path path) {
    return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
  }

  private String actor() {
    return AuditActor.current();
  }
}
