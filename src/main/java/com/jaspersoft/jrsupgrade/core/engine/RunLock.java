package com.jaspersoft.jrsupgrade.core.engine;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The exclusive run lock on {@code $JRS_UPGRADE_HOME/runs.lock} (spec §5.5). Invariants: the lock
 * is an OS file lock so it is released even when the process dies; while held the file contains
 * {@code runId pid startedAt} so a contender can name the holder; the locked byte range lies far
 * beyond the content so the holder text stays readable by other processes; contention in another
 * process ({@code tryLock() == null}) and in the same JVM both surface as {@link
 * LockHeldException}; {@link #close()} truncates and releases. A process that holds the lock never
 * opens a second handle on the file: on Linux the lock is a POSIX record lock, which the kernel
 * drops the moment any descriptor of the process on that file is closed, so a doctor probe that
 * read the holder text through {@code Files.readString} released the live run's lock (assessment
 * item E1). The locks this process holds are kept in a registry keyed by lock file, and {@link
 * #heldBy} and {@link #readHolder} answer from it before touching the file; every open of the file
 * happens under one monitor so a probe and an acquisition never overlap.
 */
public final class RunLock implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(RunLock.class);

  static final long LOCK_POSITION = 1L << 40;

  /** Locks this process holds, by absolute lock file; see the class invariant. */
  private static final ConcurrentHashMap<Path, RunLock> HELD = new ConcurrentHashMap<>();

  /** Held while the lock file is opened for any reason, so no second handle overlaps a held one. */
  private static final Object FILE = new Object();

  private final Path file;
  private final FileChannel channel;
  private final FileLock lock;
  private final String runId;
  private final Instant startedAt;
  private volatile boolean closed;

  public RunLock(JrsUpgradeHome home, String runId, Instant startedAt) {
    this.file = home.runLock();
    this.runId = runId;
    this.startedAt = startedAt;
    Path key = key(file);
    synchronized (FILE) {
      RunLock held = HELD.get(key);
      if (held != null) {
        throw new LockHeldException(held.runId, held.holder().pid());
      }
      FileChannel ch = null;
      FileLock acquired = null;
      try {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        ch =
            FileChannel.open(
                file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
          acquired = ch.tryLock(LOCK_POSITION, 1, false);
        } catch (OverlappingFileLockException e) {
          acquired = null;
        }
        if (acquired == null) {
          // Held by another process (this one holds nothing on the file, so closing is safe).
          Optional<Holder> holder = readFile(file);
          closeQuietly(ch);
          throw new LockHeldException(
              holder.map(Holder::runId).orElse("unknown"),
              holder.map(Holder::pid).orElse("unknown"));
        }
        String text = runId + " " + ProcessHandle.current().pid() + " " + startedAt;
        ch.truncate(0);
        ByteBuffer buf = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
        while (buf.hasRemaining()) {
          ch.write(buf);
        }
        ch.force(true);
      } catch (IOException e) {
        if (acquired != null) {
          try {
            acquired.release();
          } catch (IOException suppressed) {
            e.addSuppressed(suppressed);
          }
        }
        closeQuietly(ch);
        throw new IllegalStateException("cannot acquire run lock " + file, e);
      }
      this.channel = ch;
      this.lock = acquired;
      HELD.put(key, this);
    }
  }

  public String runId() {
    return runId;
  }

  public Path file() {
    return file;
  }

  /** What this lock's file says while it is held. */
  Holder holder() {
    return new Holder(runId, Long.toString(ProcessHandle.current().pid()), startedAt.toString());
  }

  private static Path key(Path lockFile) {
    return lockFile.toAbsolutePath().normalize();
  }

  /**
   * Who holds the run lock right now, or empty when it is free (review 5.4). The answer comes from
   * trying the lock, not from guessing whether the pid in the file is still alive, so a run a
   * crashed process left behind reads as free and a run another process is executing reads as held.
   * A lock this process holds is answered from the registry without opening the file; otherwise the
   * probe takes the lock only for the instant it needs it.
   */
  public static Optional<Holder> heldBy(Path lockFile) {
    synchronized (FILE) {
      RunLock held = HELD.get(key(lockFile));
      if (held != null) {
        return Optional.of(held.holder());
      }
      if (!Files.exists(lockFile)) {
        return Optional.empty();
      }
      try (FileChannel ch =
          FileChannel.open(lockFile, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
        FileLock probe = ch.tryLock(LOCK_POSITION, 1, false);
        if (probe != null) {
          probe.release();
          return Optional.empty();
        }
      } catch (OverlappingFileLockException heldByThisJvm) {
        // Not in the registry, so a lock of ours is between acquisition and registration or
        // between release and removal; both happen under FILE, so this cannot occur, but the
        // channel API declares it. Answer without touching the file again.
        return Optional.of(new Holder("unknown", Long.toString(ProcessHandle.current().pid()), ""));
      } catch (IOException e) {
        LOG.debug("cannot probe the run lock {}: {}", lockFile, e.getMessage());
        return Optional.empty();
      }
      return readFile(lockFile);
    }
  }

  /**
   * Parses {@code runId pid startedAt} from the lock file; empty when unreadable or blank. A lock
   * this process holds is answered from the registry without opening the file.
   */
  public static Optional<Holder> readHolder(Path lockFile) {
    synchronized (FILE) {
      RunLock held = HELD.get(key(lockFile));
      if (held != null) {
        return Optional.of(held.holder());
      }
      return readFile(lockFile);
    }
  }

  /**
   * Reads the file; only for a lock file this process holds no lock on (see the class invariant).
   */
  private static Optional<Holder> readFile(Path lockFile) {
    try {
      if (!Files.exists(lockFile)) {
        return Optional.empty();
      }
      String text = Files.readString(lockFile, StandardCharsets.UTF_8).strip();
      if (text.isEmpty()) {
        return Optional.empty();
      }
      String[] parts = text.split(" ", -1);
      String id = parts[0];
      String pid = parts.length > 1 ? parts[1] : "unknown";
      String started = parts.length > 2 ? parts[2] : "";
      return Optional.of(new Holder(id, pid, started));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /**
   * Releases the lock. Never throws and is idempotent (review finding 1.18): it runs after the
   * run's outcome is journaled, so a failure to truncate or release is logged, not raised.
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    IOException failure = null;
    synchronized (FILE) {
      try {
        channel.truncate(0);
        channel.force(true);
      } catch (IOException e) {
        failure = e;
      }
      try {
        lock.release();
      } catch (IOException e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      }
      try {
        channel.close();
      } catch (IOException e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      }
      HELD.remove(key(file), this);
    }
    if (failure != null) {
      LOG.warn("run lock {} was not released cleanly: {}", file, failure.getMessage());
    }
  }

  private static void closeQuietly(FileChannel ch) {
    if (ch == null) {
      return;
    }
    try {
      ch.close();
    } catch (IOException e) {
      // best effort: the lock was never acquired, nothing to release
    }
  }

  /** What the lock file says about the current holder. */
  public record Holder(String runId, String pid, String startedAt) {}
}
