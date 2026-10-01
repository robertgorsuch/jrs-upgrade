package com.jaspersoft.jrsupgrade.jrs.vendor;

import com.jaspersoft.jrsupgrade.core.config.Config;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A located {@code buildomatic/} directory (spec §7.4). Invariants: {@code scripts} holds only
 * scripts that exist, keyed by their base name ({@code js-export}, {@code js-import}, {@code
 * js-ant}) with the platform's extension already applied; {@code masterProperties} is the parsed
 * {@code default_master.properties} with every key containing {@code pass} (case-insensitive)
 * removed, so no database or keystore password ever reaches a caller; nothing here modifies the
 * vendor tree.
 */
public record Buildomatic(
    Path dir,
    Map<String, Path> scripts,
    Optional<Path> masterPropertiesFile,
    Map<String, String> masterProperties) {

  public static final String EXPORT_SCRIPT = "js-export";
  public static final String IMPORT_SCRIPT = "js-import";
  public static final String ANT_SCRIPT = "js-ant";
  public static final List<String> SCRIPT_NAMES = List.of(EXPORT_SCRIPT, IMPORT_SCRIPT, ANT_SCRIPT);
  public static final String MASTER_PROPERTIES = "default_master.properties";
  public static final String CACHE_PROPERTIES = "cache.properties";
  public static final String DEFAULT_CACHE_PROVIDER = "infinispan";
  private static final Pattern PROFILE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

  public Buildomatic {
    Objects.requireNonNull(dir, "dir");
    scripts = Map.copyOf(scripts);
    Objects.requireNonNull(masterPropertiesFile, "masterPropertiesFile");
    masterProperties = Map.copyOf(masterProperties);
  }

  /** Path of {@code js-export}, {@code js-import} or {@code js-ant}; empty if missing. */
  public Optional<Path> scriptFor(String name) {
    return Optional.ofNullable(scripts.get(name));
  }

  /** Expected scripts that were not found; empty means the layout is complete. */
  public List<String> missingScripts() {
    List<String> missing = new ArrayList<>();
    for (String n : SCRIPT_NAMES) {
      if (!scripts.containsKey(n)) {
        missing.add(n);
      }
    }
    return List.copyOf(missing);
  }

  /** {@code conf_source/db/<type>/jdbc} and the jars inside it. */
  public Optional<JdbcDriverDir> driverDir(String dbType) {
    Objects.requireNonNull(dbType, "dbType");
    String type = dbType.strip().toLowerCase(Locale.ROOT);
    Path d = dir.resolve("conf_source").resolve("db").resolve(type).resolve("jdbc");
    if (!Files.isDirectory(d)) {
      return Optional.empty();
    }
    List<Path> jars;
    try (Stream<Path> s = Files.list(d)) {
      jars =
          s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
              .sorted()
              .toList();
    } catch (IOException e) {
      return Optional.empty();
    }
    return Optional.of(new JdbcDriverDir(type, d, jars));
  }

  public Optional<JdbcDriverDir> driverDir(Config.DatabaseType type) {
    return driverDir(type.yamlValue());
  }

  /**
   * The Spring cache profile the vendor export and import tools must activate: {@code
   * js.cache.provider} from {@code build_conf/default/cache.properties}, else from {@code
   * conf_source/iePro/cache.properties}, else {@link #DEFAULT_CACHE_PROVIDER}, which is what 10.0.0
   * ships. A value that is not a plain profile name is ignored, so nothing but a single {@code -D}
   * option ever reaches {@code JAVA_OPTS}.
   */
  public String cacheProvider() {
    List<Path> candidates =
        List.of(
            dir.resolve("build_conf").resolve("default").resolve(CACHE_PROPERTIES),
            dir.resolve("conf_source").resolve("iePro").resolve(CACHE_PROPERTIES));
    for (Path candidate : candidates) {
      if (!Files.isRegularFile(candidate)) {
        continue;
      }
      Properties properties = new Properties();
      try (InputStream in = Files.newInputStream(candidate)) {
        properties.load(in);
      } catch (IOException | IllegalArgumentException e) {
        continue;
      }
      String value = properties.getProperty(VendorFlags.CACHE_PROVIDER_PROPERTY, "").strip();
      if (PROFILE_NAME.matcher(value).matches()) {
        return value;
      }
    }
    return DEFAULT_CACHE_PROVIDER;
  }
}
