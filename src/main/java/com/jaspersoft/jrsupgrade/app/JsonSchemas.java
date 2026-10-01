package com.jaspersoft.jrsupgrade.app;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The JSON Schemas (draft 2020-12) that {@code --json} output validates against (spec §14 Phase 8),
 * shipped in the jar under {@code schema/json/} and keyed by command path ({@code "runs list"},
 * {@code "doctor"}). Invariants: every leaf command in the picocli tree has exactly one {@link
 * Shape}; a {@link Document} command prints one JSON document on standard output; a {@link Stream}
 * command (everything that runs a {@code Plan}) prints JSONL, one document per line: the plan
 * ({@code plan.schema.json}, omitted by {@code runs recover}), then one {@link
 * com.jaspersoft.jrsupgrade.core.event.Event} per line ({@code events.schema.json}), then exactly
 * one of {@code {"outcome": ...}} ({@code outcome.schema.json}) or {@code {"error": ...}}; any
 * command may instead print a single {@code error.schema.json} document when it refuses or fails,
 * and with {@code --plan} a stream command prints only the plan. Every schema carries an {@code
 * $id} under {@link #IRI_PREFIX}; {@link #resourcePath} maps such an id back to the classpath
 * resource so {@code $ref}s between schemas (plan inside {@code runs show}, the configuration
 * inside {@code init}) resolve from the jar without network access.
 */
public final class JsonSchemas {

  /** Base of every schema {@code $id}; the file name follows. */
  public static final String IRI_PREFIX = "https://jaspersoft.com/jrs-upgrade/";

  /** Classpath directory of the command schemas. */
  public static final String RESOURCE_DIR = "/schema/json/";

  /** The configuration schema is owned by {@code core} and lives one level up. */
  public static final String CONFIG = "config.schema.json";

  public static final String ERROR = "error.schema.json";
  public static final String PLAN = "plan.schema.json";
  public static final String EVENTS = "events.schema.json";
  public static final String OUTCOME = "outcome.schema.json";

  /** Shared definitions referenced by other schemas, never a command's own shape. */
  private static final Set<String> SHARED = Set.of("report-item.schema.json", "run.schema.json");

  /** How a command's {@code --json} output is laid out. */
  public sealed interface Shape permits Document, Stream {}

  /** One JSON document validating against {@code schema}. */
  public record Document(String schema) implements Shape {}

  /** JSONL: optional plan, events, then outcome or error (see the class Javadoc). */
  public record Stream(boolean planFirst) implements Shape {}

  private static final Map<String, Shape> BY_COMMAND = build();

  private JsonSchemas() {}

  private static Map<String, Shape> build() {
    Map<String, Shape> m = new LinkedHashMap<>();
    m.put("selfcheck", new Document("selfcheck.schema.json"));
    m.put("init", new Document("init.schema.json"));
    m.put("doctor", new Document("doctor.schema.json"));
    m.put("smoke", new Document("smoke.schema.json"));
    m.put("config show", new Document(CONFIG));
    m.put("config set", new Document("config-set.schema.json"));
    m.put("config unset", new Document("config-set.schema.json"));
    m.put("config keys", new Document("config-keys.schema.json"));
    m.put("export", new Stream(true));
    m.put("import", new Stream(true));
    m.put("upgrade", new Stream(true));
    m.put("upgrade rollback", new Stream(true));
    m.put("customizations register", new Document("customization.schema.json"));
    m.put("customizations unregister", new Document("customizations-unregister.schema.json"));
    m.put("customizations list", new Document("customizations-list.schema.json"));
    m.put("customizations diff", new Document("customizations-diff.schema.json"));
    m.put("customizations scan", new Document("customizations-scan.schema.json"));
    m.put("runs list", new Document("runs-list.schema.json"));
    m.put("runs show", new Document("runs-show.schema.json"));
    m.put("runs recover", new Stream(false));
    m.put("runs prune", new Document("runs-prune.schema.json"));
    m.put("runs support-bundle", new Document("runs-support-bundle.schema.json"));
    m.put("docs", new Document("docs.schema.json"));
    m.put("home show", new Document("home-show.schema.json"));
    m.put("home set", new Document("home-set.schema.json"));
    m.put("home reset", new Document("home-set.schema.json"));
    m.put("secrets init", new Document("secrets-init.schema.json"));
    m.put("secrets set", new Document("secrets-set.schema.json"));
    m.put("secrets remove", new Document("secrets-remove.schema.json"));
    m.put("secrets list", new Document("secrets-list.schema.json"));
    return Collections.unmodifiableMap(m);
  }

  /** The shape for a space-separated command path such as {@code "upgrade rollback"}. */
  public static Optional<Shape> forCommand(String commandPath) {
    return Optional.ofNullable(BY_COMMAND.get(commandPath));
  }

  /** Every command path that has a schema, in declaration order. */
  public static Set<String> commands() {
    return BY_COMMAND.keySet();
  }

  /** Every schema file name a consumer may need, including the shared stream and error schemas. */
  public static Set<String> schemas() {
    Set<String> names = new TreeSet<>(SHARED);
    names.add(ERROR);
    names.add(PLAN);
    names.add(EVENTS);
    names.add(OUTCOME);
    for (Shape shape : BY_COMMAND.values()) {
      switch (shape) {
        case Document d -> names.add(d.schema());
        case Stream s -> {}
      }
    }
    return Collections.unmodifiableSet(names);
  }

  /** The {@code $id} of a schema file. */
  public static String iri(String schema) {
    return IRI_PREFIX + schema;
  }

  /**
   * The classpath resource behind a schema file name or {@code $id}; empty when the IRI is not one
   * of ours. {@code config.schema.json} resolves to {@code core}'s {@code /schema/} directory.
   */
  public static Optional<String> resourcePath(String schemaOrIri) {
    String name =
        schemaOrIri.startsWith(IRI_PREFIX)
            ? schemaOrIri.substring(IRI_PREFIX.length())
            : schemaOrIri;
    if (name.isEmpty() || name.contains("/")) {
      return Optional.empty();
    }
    if (name.equals(CONFIG)) {
      return Optional.of("/schema/" + CONFIG);
    }
    return Optional.of(RESOURCE_DIR + name);
  }

  /** Opens a schema by file name or {@code $id}; empty when it is not bundled. */
  public static Optional<InputStream> open(String schemaOrIri) {
    return resourcePath(schemaOrIri).map(JsonSchemas.class::getResourceAsStream);
  }
}
