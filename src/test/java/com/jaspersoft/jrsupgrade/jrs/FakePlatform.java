package com.jaspersoft.jrsupgrade.jrs;

import com.jaspersoft.jrsupgrade.core.platform.FileOps;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.platform.ProcessRunner;
import com.jaspersoft.jrsupgrade.core.platform.ServiceConfig;
import com.jaspersoft.jrsupgrade.core.platform.ServiceController;
import com.jaspersoft.jrsupgrade.core.platform.TomcatLayout;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * A {@link Platform} whose OS family is chosen by the test, with a real streaming SHA-256 and a
 * scripted process runner; everything else is unsupported.
 */
public final class FakePlatform implements Platform {

  /** What {@code files().isOwnerOnly} answers for every file. */
  public boolean ownerOnly = true;

  private final OsFamily os;
  private final ProcessRunner runner;
  private final Optional<ServiceController> services;

  public FakePlatform(OsFamily os) {
    this(os, new FakeRunner());
  }

  public FakePlatform(OsFamily os, ProcessRunner runner) {
    this(os, runner, Optional.empty());
  }

  public FakePlatform(OsFamily os, ProcessRunner runner, ServiceController services) {
    this(os, runner, Optional.of(services));
  }

  private FakePlatform(OsFamily os, ProcessRunner runner, Optional<ServiceController> services) {
    this.os = os;
    this.runner = runner;
    this.services = services;
  }

  @Override
  public OsFamily os() {
    return os;
  }

  @Override
  public Arch arch() {
    return Arch.X86_64;
  }

  @Override
  public ServiceController services(ServiceConfig cfg) {
    return services.orElseThrow(
        () -> new UnsupportedOperationException("no services in FakePlatform"));
  }

  @Override
  public FileOps files() {
    return new FileOps() {
      @Override
      public String sha256(Path file) throws IOException {
        MessageDigest md;
        try {
          md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
          throw new IllegalStateException(e);
        }
        byte[] buf = new byte[8192];
        try (InputStream in = Files.newInputStream(file)) {
          int n;
          while ((n = in.read(buf)) > 0) {
            md.update(buf, 0, n);
          }
        }
        return HexFormat.of().formatHex(md.digest());
      }

      @Override
      public void atomicReplace(Path source, Path target) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void copyPreserving(Path source, Path target) {
        throw new UnsupportedOperationException();
      }

      @Override
      public boolean isLocked(Path file) {
        return false;
      }

      @Override
      public Optional<String> lockHolder(Path file) {
        return Optional.empty();
      }

      @Override
      public Optional<String> lockInspectionLimit() {
        return Optional.empty();
      }

      @Override
      public Permissions capturePermissions(Path path) {
        return new Permissions("test", List.of());
      }

      @Override
      public void applyPermissions(Path path, Permissions permissions) {}

      @Override
      public long freeSpaceBytes(Path anyPathOnVolume) {
        return Long.MAX_VALUE;
      }

      @Override
      public String volumeId(Path anyPathOnVolume) {
        return "fake";
      }

      @Override
      public boolean isWritable(Path dir) {
        return true;
      }

      @Override
      public boolean isOwnerOnly(Path file) {
        return ownerOnly;
      }
    };
  }

  @Override
  public ProcessRunner processes() {
    return runner;
  }

  @Override
  public Path defaultHome() {
    return Path.of(System.getProperty("java.io.tmpdir"), "jrs-upgrade-test");
  }

  @Override
  public Optional<TomcatLayout> detectTomcat(Path installDir) {
    return Optional.empty();
  }

  @Override
  public List<Path> candidateInstallDirs() {
    return List.of();
  }
}
