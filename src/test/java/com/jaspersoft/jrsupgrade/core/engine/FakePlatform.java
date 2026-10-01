package com.jaspersoft.jrsupgrade.core.engine;

import com.jaspersoft.jrsupgrade.core.platform.FileOps;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.platform.ProcessRunner;
import com.jaspersoft.jrsupgrade.core.platform.ServiceConfig;
import com.jaspersoft.jrsupgrade.core.platform.ServiceController;
import com.jaspersoft.jrsupgrade.core.platform.TomcatLayout;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Minimal {@link Platform} for engine tests: no real files, processes or services are touched. */
public final class FakePlatform implements Platform {

  private final Path home;

  /**
   * Free space by directory: a path under one of these keys (the deepest wins) reports that much
   * and names the key as its volume; any other path reports unlimited space on volume "fake".
   */
  public final Map<Path, Long> freeSpaceUnder = new LinkedHashMap<>();

  public FakePlatform(Path home) {
    this.home = home;
  }

  private Optional<Path> volume(Path path) {
    Path abs = path.toAbsolutePath().normalize();
    return freeSpaceUnder.keySet().stream()
        .filter(k -> abs.startsWith(k.toAbsolutePath().normalize()))
        .max(Comparator.comparingInt(Path::getNameCount));
  }

  @Override
  public OsFamily os() {
    return OsFamily.LINUX;
  }

  @Override
  public Arch arch() {
    return Arch.X86_64;
  }

  @Override
  public ServiceController services(ServiceConfig cfg) {
    return new ServiceController() {
      @Override
      public State state() {
        return State.STOPPED;
      }

      @Override
      public State stop(Duration timeout) {
        return State.STOPPED;
      }

      @Override
      public State start(Duration timeout) {
        return State.RUNNING;
      }

      @Override
      public String describe() {
        return "fake service";
      }
    };
  }

  @Override
  public FileOps files() {
    return new FileOps() {
      @Override
      public String sha256(Path file) {
        throw new UnsupportedOperationException("fake platform");
      }

      @Override
      public void atomicReplace(Path source, Path target) {
        throw new UnsupportedOperationException("fake platform");
      }

      @Override
      public void copyPreserving(Path source, Path target) {
        throw new UnsupportedOperationException("fake platform");
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
        return new Permissions("fake", List.of());
      }

      @Override
      public void applyPermissions(Path path, Permissions permissions) {}

      @Override
      public long freeSpaceBytes(Path anyPathOnVolume) {
        return volume(anyPathOnVolume).map(freeSpaceUnder::get).orElse(Long.MAX_VALUE);
      }

      @Override
      public String volumeId(Path anyPathOnVolume) {
        return volume(anyPathOnVolume).map(Path::toString).orElse("fake");
      }

      @Override
      public boolean isWritable(Path dir) {
        return true;
      }

      @Override
      public boolean isOwnerOnly(Path file) {
        return true;
      }
    };
  }

  @Override
  public ProcessRunner processes() {
    return new ProcessRunner() {
      @Override
      public Result run(Request request, Consumer<OutputLine> onLine) {
        throw new UnsupportedOperationException("fake platform runs no processes");
      }
    };
  }

  @Override
  public Path defaultHome() {
    return home;
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
