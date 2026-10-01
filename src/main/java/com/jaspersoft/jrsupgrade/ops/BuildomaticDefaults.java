package com.jaspersoft.jrsupgrade.ops;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.jrs.vendor.BuildomaticLocator;
import com.jaspersoft.jrsupgrade.jrs.vendor.BuildomaticResolution;
import com.jaspersoft.jrsupgrade.ops.init.DefaultMasterProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The repository database settings of the installed buildomatic's {@code default_master.properties}
 * as the defaults of {@code config.yaml} (#73). Invariants: only {@code database.type}, {@code
 * database.url} and {@code database.username} are read, and a value the configuration already has
 * (from the file, the environment or {@code --set}) is never replaced; no password is ever read
 * ({@link DefaultMasterProperties} refuses password keys); nothing is read when the configuration
 * names no local installation or already has all three values, so a jrs-upgrade that reaches the
 * server over REST, or one configured by hand, probes no directory and no share; an unreadable file
 * leaves the configuration as it was; the result names the file each filled value came from.
 */
public final class BuildomaticDefaults {

  private static final Logger LOG = LoggerFactory.getLogger(BuildomaticDefaults.class);
  private static final String FILE = "default_master.properties";

  /** The configuration with the filled values, and the file each filled key came from. */
  public record Result(Config config, Map<String, Path> filled) {
    public Result {
      Objects.requireNonNull(config, "config");
      filled = Map.copyOf(filled);
    }
  }

  private BuildomaticDefaults() {}

  /** {@code config} with the database settings it leaves out read from buildomatic. */
  public static Result apply(Config config, Platform platform) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(platform, "platform");
    Config.Database db = config.database();
    if (!config.server().namesLocalInstallation()
        || (db.type().isPresent() && db.url().isPresent() && db.username().isPresent())) {
      return new Result(config, Map.of());
    }
    Optional<Master> master = read(config, platform);
    if (master.isEmpty()) {
      return new Result(config, Map.of());
    }
    DefaultMasterProperties m = master.get().properties();
    Path file = master.get().file();
    Map<String, Path> filled = new LinkedHashMap<>();
    Optional<Config.DatabaseType> type =
        fill(db.type(), m.databaseType(), "database.type", file, filled);
    Optional<String> url = fill(db.url(), m.jdbcUrl(), "database.url", file, filled);
    Optional<String> user = fill(db.username(), m.dbUsername(), "database.username", file, filled);
    if (filled.isEmpty()) {
      return new Result(config, Map.of());
    }
    Config.Database merged = new Config.Database(type, url, user, db.passwordRef(), db.driverDir());
    return new Result(
        new Config(
            config.server(),
            config.service(),
            merged,
            config.vendor(),
            config.network(),
            config.backups(),
            config.smoke()),
        filled);
  }

  /**
   * The database settings the configuration gives that differ from buildomatic's, as {@code key ->
   * "config.yaml: X, default_master.properties: Y"}; empty when they agree or there is no file.
   */
  public static Map<String, String> disagreements(Config config, Platform platform) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(platform, "platform");
    if (!config.server().namesLocalInstallation()) {
      return Map.of();
    }
    Optional<Master> master = read(config, platform);
    if (master.isEmpty()) {
      return Map.of();
    }
    DefaultMasterProperties m = master.get().properties();
    Config.Database db = config.database();
    Map<String, String> out = new LinkedHashMap<>();
    compare(
        "database.type",
        db.type().map(Config.DatabaseType::yamlValue),
        m.databaseType().map(Config.DatabaseType::yamlValue),
        out);
    compare("database.url", db.url(), m.jdbcUrl(), out);
    compare("database.username", db.username(), m.dbUsername(), out);
    return out;
  }

  private record Master(DefaultMasterProperties properties, Path file) {}

  private static Optional<Master> read(Config config, Platform platform) {
    BuildomaticResolution resolution;
    try {
      resolution = new BuildomaticLocator(platform).resolve(config);
    } catch (RuntimeException e) {
      LOG.debug("cannot locate buildomatic for {}: {}", FILE, e.getMessage());
      return Optional.empty();
    }
    Optional<Path> file = resolution.located().map(b -> b.dir().resolve(FILE));
    if (file.isEmpty() || !Files.isRegularFile(file.get())) {
      return Optional.empty();
    }
    try {
      return Optional.of(new Master(DefaultMasterProperties.parse(file.get()), file.get()));
    } catch (IOException | RuntimeException e) {
      LOG.debug("cannot read {}: {}", file.get(), e.getMessage());
      return Optional.empty();
    }
  }

  private static <T> Optional<T> fill(
      Optional<T> given, Optional<T> fromFile, String key, Path file, Map<String, Path> filled) {
    if (given.isPresent() || fromFile.isEmpty()) {
      return given;
    }
    filled.put(key, file);
    return fromFile;
  }

  private static void compare(
      String key, Optional<String> given, Optional<String> fromFile, Map<String, String> out) {
    if (given.isPresent() && fromFile.isPresent() && !given.get().equals(fromFile.get())) {
      out.put(key, "config.yaml: " + given.get() + ", " + FILE + ": " + fromFile.get());
    }
  }
}
