package com.jaspersoft.jrsupgrade.core.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link FileOps} that behaves exactly like the one it wraps until the Nth write, which fails
 * with an {@link IOException} (review 5.4). A copy or a replace half-way through a snapshot, a
 * backup or a swap is the failure the compensation logic exists for, and without this decorator
 * nothing provoked it: the fakes only refused to do anything at all. Invariant: reads are never
 * failed, so a test sees the real state the failure left behind; the counter is shared across every
 * write method, so "the third file" means the third file whatever the caller wrote it with.
 */
public final class FailingFileOps implements FileOps {

  private final FileOps delegate;
  private final int failOnWrite;
  private final String message;
  private final AtomicInteger writes = new AtomicInteger();

  /** Fails the {@code failOnWrite}-th write (1-based); 0 or less never fails. */
  public FailingFileOps(FileOps delegate, int failOnWrite) {
    this(delegate, failOnWrite, "simulated I/O failure");
  }

  public FailingFileOps(FileOps delegate, int failOnWrite, String message) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.failOnWrite = failOnWrite;
    this.message = Objects.requireNonNull(message, "message");
  }

  /** How many writes have been attempted, failed one included. */
  public int writes() {
    return writes.get();
  }

  private void countWrite(Path target) throws IOException {
    if (writes.incrementAndGet() == failOnWrite) {
      throw new IOException(message + " writing " + target);
    }
  }

  @Override
  public void copyPreserving(Path source, Path target) throws IOException {
    countWrite(target);
    delegate.copyPreserving(source, target);
  }

  @Override
  public void atomicReplace(Path source, Path target) throws IOException {
    countWrite(target);
    delegate.atomicReplace(source, target);
  }

  @Override
  public void applyPermissions(Path path, Permissions permissions) throws IOException {
    delegate.applyPermissions(path, permissions);
  }

  @Override
  public String sha256(Path file) throws IOException {
    return delegate.sha256(file);
  }

  @Override
  public boolean isLocked(Path file) {
    return delegate.isLocked(file);
  }

  @Override
  public Optional<String> lockHolder(Path file) {
    return delegate.lockHolder(file);
  }

  @Override
  public Optional<String> lockInspectionLimit() {
    return delegate.lockInspectionLimit();
  }

  @Override
  public Permissions capturePermissions(Path path) throws IOException {
    return delegate.capturePermissions(path);
  }

  @Override
  public long freeSpaceBytes(Path anyPathOnVolume) throws IOException {
    return delegate.freeSpaceBytes(anyPathOnVolume);
  }

  @Override
  public String volumeId(Path anyPathOnVolume) throws IOException {
    return delegate.volumeId(anyPathOnVolume);
  }

  @Override
  public boolean isWritable(Path dir) {
    return delegate.isWritable(dir);
  }

  @Override
  public boolean isOwnerOnly(Path file) throws IOException {
    return delegate.isOwnerOnly(file);
  }
}
