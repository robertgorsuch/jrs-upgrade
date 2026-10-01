package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.core.platform.FileOps;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** A {@link FileOps} that forwards everything, so a test can override one method. */
abstract class DelegatingFileOps implements FileOps {

  private final FileOps delegate;

  DelegatingFileOps(FileOps delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public String sha256(Path file) throws IOException {
    return delegate.sha256(file);
  }

  @Override
  public void atomicReplace(Path source, Path target) throws IOException {
    delegate.atomicReplace(source, target);
  }

  @Override
  public void copyPreserving(Path source, Path target) throws IOException {
    delegate.copyPreserving(source, target);
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
  public void applyPermissions(Path path, Permissions permissions) throws IOException {
    delegate.applyPermissions(path, permissions);
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
