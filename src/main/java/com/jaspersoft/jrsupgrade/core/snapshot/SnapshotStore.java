package com.jaspersoft.jrsupgrade.core.snapshot;

import static java.util.Objects.requireNonNull;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.platform.DiskSpace;
import com.jaspersoft.jrsupgrade.core.platform.Durability;
import com.jaspersoft.jrsupgrade.core.platform.FileOps;
import com.jaspersoft.jrsupgrade.core.platform.Trees;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates, verifies, restores and prunes file snapshots under {@code $JRS_UPGRADE_HOME/snapshots}
 * (spec §5.6). Invariants: a snapshot is only visible (listed, findable) once its manifest exists,
 * and the manifest is written last, so a crash mid-copy leaves a directory that is ignored and
 * rebuilt on the next attempt; every payload copy is hashed while streaming and compared with the
 * source hash and with a re-read of the copy before it is recorded; {@link #restore} verifies the
 * whole snapshot before touching a single original, replaces each original atomically, re-applies
 * the captured permissions and confirms the restored hash; {@link #create} is idempotent for a
 * given {@code runId/stepId} (an existing, verified snapshot is returned unchanged so a retried
 * step keeps the pre-change state); {@link #prune} never removes a snapshot of a protected run.
 * Payload copies keep the permissions of the snapshots tree; the originals' permissions live in the
 * manifest. "Written last" is a durability claim as well as an ordering one: every payload and the
 * manifest itself are forced to stable storage before the rename that publishes the manifest, so a
 * power cut cannot leave a valid manifest describing payloads that were never written.
 */
public final class SnapshotStore {

  private static final Logger LOG = LoggerFactory.getLogger(SnapshotStore.class);
  private static final String RESTORE_SUFFIX = ".jrs-upgrade-restore";

  private final JrsUpgradeHome home;
  private final FileOps files;
  private final Clock clock;

  public SnapshotStore(JrsUpgradeHome home, FileOps files) {
    this(home, files, Clock.systemUTC());
  }

  public SnapshotStore(JrsUpgradeHome home, FileOps files, Clock clock) {
    this.home = requireNonNull(home, "home");
    this.files = requireNonNull(files, "files");
    this.clock = requireNonNull(clock, "clock");
  }

  /**
   * Captures {@code paths} (absolute, or relative to {@code baseDir}; all must lie beneath it) into
   * {@code snapshots/runId/stepId}. Returns the existing snapshot when one is already complete.
   */
  public Snapshot create(String runId, String stepId, List<Path> paths, Path baseDir)
      throws IOException {
    validateId(runId, "runId");
    validateId(stepId, "stepId");
    Path base = baseDir.toAbsolutePath().normalize();
    Optional<Snapshot> existing = find(runId, stepId);
    if (existing.isPresent()) {
      verify(existing.get());
      LOG.info("snapshot {}/{} already exists; reusing it", runId, stepId);
      return existing.get();
    }
    Path dir = snapshotDir(runId, stepId);
    if (Files.exists(dir)) {
      LOG.warn("removing incomplete snapshot directory {}", dir);
      Trees.deleteRecursively(dir);
    }
    Set<Path> sources = uniqueSources(paths, base);
    // Review finding 1.16: the copy must fit on the snapshot volume before it starts.
    long bytes = 0;
    for (Path source : sources) {
      bytes += Files.size(source);
    }
    List<String> short_ =
        DiskSpace.problems(
            files, List.of(new DiskSpace.Need("snapshot " + runId + "/" + stepId, dir, bytes)));
    if (!short_.isEmpty()) {
      throw new IOException(
          "not enough free space for the snapshot: "
              + String.join("; ", short_)
              + "; "
              + DiskSpace.remedy(home.root()));
    }
    Path payload = dir.resolve(Snapshot.PAYLOAD_DIR);
    Files.createDirectories(payload);

    List<SnapshotManifest.Entry> entries = new ArrayList<>();
    for (Path source : sources) {
      Path relative = base.relativize(source);
      String manifestPath = manifestPath(relative);
      Path copy = payload.resolve(relative);
      Files.createDirectories(copy.getParent());
      FileOps.Permissions permissions = files.capturePermissions(source);
      String streamed = Durability.copyHashing(source, copy);
      String sourceHash = files.sha256(source);
      if (!streamed.equals(sourceHash)) {
        throw new SnapshotCorruptException(
            runId + "/" + stepId, List.of(manifestPath + ": source changed while being copied"));
      }
      String copyHash = files.sha256(copy);
      if (!copyHash.equals(streamed)) {
        throw new SnapshotCorruptException(
            runId + "/" + stepId, List.of(manifestPath + ": payload copy does not match source"));
      }
      entries.add(
          new SnapshotManifest.Entry(manifestPath, streamed, Files.size(copy), permissions));
    }
    Durability.syncDirectory(payload);
    SnapshotManifest manifest = new SnapshotManifest(runId, stepId, clock.instant(), base, entries);
    Path manifestFile = dir.resolve(Snapshot.MANIFEST_FILE);
    Path staged = dir.resolve(Snapshot.MANIFEST_FILE + ".tmp");
    SnapshotJson.write(manifest, staged);
    Durability.sync(staged);
    Files.move(staged, manifestFile, StandardCopyOption.ATOMIC_MOVE);
    Durability.syncDirectory(dir);
    return new Snapshot(runId, stepId, dir, manifest);
  }

  /** Recomputes every payload hash; throws with the full mismatch list when any differs. */
  public void verify(Snapshot snapshot) throws IOException {
    List<String> mismatches = new ArrayList<>();
    for (SnapshotManifest.Entry entry : snapshot.manifest().entries()) {
      Path copy = snapshot.payloadFile(entry);
      if (!Files.isRegularFile(copy)) {
        mismatches.add(entry.path() + ": payload missing");
        continue;
      }
      long size = Files.size(copy);
      if (size != entry.size()) {
        mismatches.add(entry.path() + ": size " + size + " != " + entry.size());
        continue;
      }
      String hash = files.sha256(copy);
      if (!hash.equals(entry.sha256())) {
        mismatches.add(entry.path() + ": sha256 " + hash + " != " + entry.sha256());
      }
    }
    if (!mismatches.isEmpty()) {
      throw new SnapshotCorruptException(snapshot.runId() + "/" + snapshot.stepId(), mismatches);
    }
  }

  /**
   * Puts every captured file back in place: verifies first, then atomically replaces (or recreates)
   * each original from the payload and re-applies its permissions.
   */
  public void restore(Snapshot snapshot) throws IOException {
    verify(snapshot);
    Path base = snapshot.manifest().baseDir();
    for (SnapshotManifest.Entry entry : snapshot.manifest().entries()) {
      Path target = base.resolve(entry.path()).toAbsolutePath().normalize();
      Path parent = target.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Path staged =
          target.resolveSibling(
              "." + target.getFileName() + "." + System.nanoTime() + RESTORE_SUFFIX);
      try {
        Durability.copy(snapshot.payloadFile(entry), staged);
        files.atomicReplace(staged, target);
      } catch (IOException e) {
        Files.deleteIfExists(staged);
        throw e;
      }
      files.applyPermissions(target, entry.permissions());
      String restored = files.sha256(target);
      if (!restored.equals(entry.sha256())) {
        throw new IOException(
            "restored " + target + " hashes to " + restored + ", expected " + entry.sha256());
      }
    }
  }

  /** Every complete snapshot, oldest first. */
  public List<Snapshot> list() throws IOException {
    List<Snapshot> found = new ArrayList<>();
    Path root = home.snapshots();
    if (!Files.isDirectory(root)) {
      return found;
    }
    for (Path runDir : subdirectories(root)) {
      for (Path stepDir : subdirectories(runDir)) {
        readSnapshot(stepDir).ifPresent(found::add);
      }
    }
    found.sort(
        Comparator.comparing((Snapshot s) -> s.manifest().createdAt())
            .thenComparing(Snapshot::runId)
            .thenComparing(Snapshot::stepId));
    return List.copyOf(found);
  }

  public Optional<Snapshot> find(String runId, String stepId) throws IOException {
    validateId(runId, "runId");
    validateId(stepId, "stepId");
    return readSnapshot(snapshotDir(runId, stepId));
  }

  /** Bytes used on disk by the whole snapshots tree, manifests included. */
  public long totalBytes() throws IOException {
    Path root = home.snapshots();
    if (!Files.isDirectory(root)) {
      return 0;
    }
    long[] total = {0};
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            total[0] += attrs.size();
            return FileVisitResult.CONTINUE;
          }
        });
    return total[0];
  }

  /**
   * Deletes unprotected runs whose snapshots have all passed {@code retention} (age pruning is
   * disabled for a zero or negative duration), then whole unprotected runs, oldest first, until at
   * most {@code maxSnapshots} snapshots remain in total ({@code 0} means no cap). Returns what was
   * deleted.
   */
  public List<Snapshot> prune(Duration retention, int maxSnapshots, Set<String> protectedRunIds)
      throws IOException {
    List<Snapshot> removed = pruneCandidates(retention, maxSnapshots, protectedRunIds);
    for (Snapshot snapshot : removed) {
      LOG.info("pruning snapshot {}/{}", snapshot.runId(), snapshot.stepId());
      Trees.deleteRecursively(snapshot.dir());
      deleteIfEmpty(snapshot.dir().getParent());
    }
    return removed;
  }

  /**
   * What {@link #prune} with the same arguments would delete, oldest first, without touching the
   * disk: every unprotected run whose snapshots have all passed {@code retention}, then whole
   * unprotected runs, oldest first, until at most {@code maxSnapshots} snapshots remain. A run
   * whose id is in {@code protectedRunIds} is never a candidate.
   *
   * <p>A run is the unit, not a snapshot, because the snapshots of one run only mean anything
   * together: an upgrade's rollback point is its configuration and its keystore and its archives,
   * and restoring some of them is worse than restoring none. So a run keeps every snapshot until it
   * can lose them all, and {@code maxSnapshots} may be undershot when the run that has to go holds
   * several. Disk space is a budget; a half-restorable rollback point is a trap.
   */
  public List<Snapshot> pruneCandidates(
      Duration retention, int maxSnapshots, Set<String> protectedRunIds) throws IOException {
    if (maxSnapshots < 0) {
      throw new IllegalArgumentException("maxSnapshots must not be negative");
    }
    Optional<Instant> cutoff =
        retention.isZero() || retention.isNegative()
            ? Optional.empty()
            : Optional.of(clock.instant().minus(retention));
    Map<String, List<Snapshot>> runs = new LinkedHashMap<>();
    for (Snapshot snapshot : list()) {
      runs.computeIfAbsent(snapshot.runId(), id -> new ArrayList<>()).add(snapshot);
    }
    List<Snapshot> removed = new ArrayList<>();
    int surviving = 0;
    List<String> survivingRuns = new ArrayList<>();
    for (Map.Entry<String, List<Snapshot>> run : runs.entrySet()) {
      boolean expired =
          cutoff.isPresent()
              && run.getValue().stream()
                  .allMatch(s -> s.manifest().createdAt().isBefore(cutoff.get()));
      if (expired && !protectedRunIds.contains(run.getKey())) {
        removed.addAll(run.getValue());
      } else {
        surviving += run.getValue().size();
        survivingRuns.add(run.getKey());
      }
    }
    if (maxSnapshots > 0) {
      for (String runId : survivingRuns) {
        if (surviving <= maxSnapshots) {
          break;
        }
        if (protectedRunIds.contains(runId)) {
          continue;
        }
        removed.addAll(runs.get(runId));
        surviving -= runs.get(runId).size();
      }
    }
    removed.sort(
        Comparator.comparing((Snapshot s) -> s.manifest().createdAt())
            .thenComparing(Snapshot::runId)
            .thenComparing(Snapshot::stepId));
    return List.copyOf(removed);
  }

  private Path snapshotDir(String runId, String stepId) {
    return home.snapshots().resolve(runId).resolve(stepId).toAbsolutePath().normalize();
  }

  private static Optional<Snapshot> readSnapshot(Path dir) throws IOException {
    Path manifestFile = dir.resolve(Snapshot.MANIFEST_FILE);
    if (!Files.isRegularFile(manifestFile)) {
      return Optional.empty();
    }
    SnapshotManifest manifest = SnapshotJson.read(manifestFile);
    return Optional.of(
        new Snapshot(
            manifest.runId(), manifest.stepId(), dir.toAbsolutePath().normalize(), manifest));
  }

  private static List<Path> subdirectories(Path dir) throws IOException {
    try (DirectoryStream<Path> children = Files.newDirectoryStream(dir, Files::isDirectory)) {
      return StreamSupport.stream(children.spliterator(), false).sorted().toList();
    }
  }

  private static Set<Path> uniqueSources(List<Path> paths, Path base) throws IOException {
    Set<Path> sources = new LinkedHashSet<>();
    for (Path path : paths) {
      Path source = base.resolve(path).toAbsolutePath().normalize();
      if (!source.startsWith(base)) {
        throw new IllegalArgumentException(source + " is outside " + base);
      }
      if (!Files.isRegularFile(source)) {
        throw new NoSuchFileException(source.toString());
      }
      sources.add(source);
    }
    return sources;
  }

  private static void validateId(String id, String field) {
    if (id == null || id.isBlank() || id.contains("/") || id.contains("\\") || id.contains("..")) {
      throw new IllegalArgumentException(field + " must be a plain directory name: " + id);
    }
  }

  private static String manifestPath(Path relative) {
    StringBuilder joined = new StringBuilder();
    for (Path element : relative) {
      if (joined.length() > 0) {
        joined.append('/');
      }
      joined.append(element);
    }
    return joined.toString();
  }

  private static void deleteIfEmpty(Path dir) throws IOException {
    if (dir == null || !Files.isDirectory(dir)) {
      return;
    }
    try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
      if (children.iterator().hasNext()) {
        return;
      }
    }
    Files.delete(dir);
  }
}
