package com.jaspersoft.jrsupgrade.ops;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * The server's own feature switches in {@code WEB-INF/js.config.properties}, read for the two the
 * vendor guides give an operational reason to know about (issue #113): {@link #HEARTBEAT}, the
 * usage-telemetry upload that matters on an isolated host and that a cumulative hotfix can bring or
 * reset, and {@link #AUDIT}, which decides whether event exports can be very large and whether a
 * newdb upgrade has events to lose (administrator guide 10.1 pp.250, 416). Invariants: read-only
 * and streaming; a missing or unreadable file is reported as absent, never as a value; a value is a
 * flag only when it reads {@code true} or {@code false} after trimming, case-insensitively.
 */
public record JsConfig(Path file, Map<String, String> values) {

  public static final String RELATIVE = "WEB-INF/js.config.properties";
  public static final String HEARTBEAT = "heartbeat.enabled";
  public static final String AUDIT = "feature.audit_monitoring.enabled";

  public JsConfig {
    values = Map.copyOf(values);
  }

  /** The file under the deployed webapp, when it exists and can be read. */
  public static Optional<JsConfig> read(Path webappDir) {
    Path file = webappDir.resolve("WEB-INF").resolve("js.config.properties");
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    Properties props = new Properties();
    try (Reader in = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
      props.load(in);
    } catch (IOException | IllegalArgumentException e) {
      return Optional.empty();
    }
    Map<String, String> values = new LinkedHashMap<>();
    for (String key : props.stringPropertyNames()) {
      values.put(key, props.getProperty(key));
    }
    return Optional.of(new JsConfig(file, values));
  }

  /** The switch, when the file sets it to {@code true} or {@code false}. */
  public Optional<Boolean> flag(String key) {
    String raw = values.get(key);
    if (raw == null) {
      return Optional.empty();
    }
    String v = raw.strip().toLowerCase(Locale.ROOT);
    return switch (v) {
      case "true" -> Optional.of(true);
      case "false" -> Optional.of(false);
      default -> Optional.empty();
    };
  }
}
