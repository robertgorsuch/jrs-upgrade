package com.jaspersoft.jrsupgrade.core.platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes that survive a power cut, which is what makes a snapshot worth having (spec §6.5): a
 * manifest is only ever published once its payloads are on stable storage, so a snapshot that is
 * visible after a crash can always roll the run back. Invariants: {@link #copy} and {@link
 * #copyHashing} return only after the target's bytes and length have been forced; {@link #sync}
 * forces a file that was written earlier; {@link #syncDirectory} makes a completed rename durable
 * and is a logged no-op where the platform refuses to open a directory as a channel (Windows, whose
 * file systems journal the directory change themselves). Nothing here holds a whole file in memory.
 *
 * <p>{@link #move} is here for the same reason: publishing a file is a rename, and on Windows a
 * rename is denied for a moment after the handle that wrote the file is closed, which forcing the
 * file makes more likely rather than less. Every caller that renames a staged file into place goes
 * through it, so the retry exists once.
 */
public final class Durability {

  private static final Logger LOG = LoggerFactory.getLogger(Durability.class);
  private static final int MOVE_ATTEMPTS = 20;
  private static final long MOVE_RETRY_DELAY_MS = 50;

  private Durability() {}

  /** Copies to a target that must not exist yet, forcing before the handle closes. */
  public static void copy(Path source, Path target) throws IOException {
    write(source, target, Optional.empty());
  }

  /** As {@link #copy}, also returning the hex SHA-256 of the bytes that were written. */
  public static String copyHashing(Path source, Path target) throws IOException {
    MessageDigest digest = DefaultFileOps.sha256Digest();
    write(source, target, Optional.of(digest));
    return HexFormat.of().formatHex(digest.digest());
  }

  /** Forces a file that is already written and closed. */
  public static void sync(Path file) throws IOException {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
  }

  /**
   * Forces the directory entry, so a rename that has already returned cannot be lost. POSIX needs
   * this; Windows cannot open a directory as a channel and does not need to, so a failure here is
   * logged rather than propagated.
   */
  public static void syncDirectory(Path dir) {
    if (dir == null) {
      return;
    }
    try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (IOException e) {
      LOG.debug("{} cannot be synced ({}); relying on the file system's own journal", dir, e);
    }
  }

  /**
   * Renames {@code source} to {@code target}, retrying a rename that fails with a transient {@link
   * IOException}: Windows denies a same-directory rename for a short while after the file is
   * closed, whether that is an antivirus scanner, the indexer, or the file system catching up with
   * a handle that has just gone. {@link AtomicMoveNotSupportedException} is structural rather than
   * transient and is rethrown at once, so a caller can fall back to a cross-volume copy.
   */
  public static void move(Path source, Path target, CopyOption... options) throws IOException {
    for (int attempt = 1; ; attempt++) {
      try {
        Files.move(source, target, options);
        return;
      } catch (AtomicMoveNotSupportedException notSupported) {
        throw notSupported;
      } catch (IOException e) {
        if (attempt >= MOVE_ATTEMPTS || !pause()) {
          throw e;
        }
        LOG.debug("rename {} -> {} failed (attempt {}); retrying: {}", source, target, attempt, e);
      }
    }
  }

  private static boolean pause() {
    try {
      Thread.sleep(MOVE_RETRY_DELAY_MS);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static void write(Path source, Path target, Optional<MessageDigest> digest)
      throws IOException {
    byte[] buffer = new byte[DefaultFileOps.BUFFER_SIZE];
    try (InputStream in = Files.newInputStream(source);
        FileChannel channel =
            FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      OutputStream out = Channels.newOutputStream(channel);
      int read;
      while ((read = in.read(buffer)) != -1) {
        if (digest.isPresent()) {
          digest.get().update(buffer, 0, read);
        }
        out.write(buffer, 0, read);
      }
      out.flush();
      channel.force(true);
    }
  }
}
