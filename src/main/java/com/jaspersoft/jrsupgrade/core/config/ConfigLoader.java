package com.jaspersoft.jrsupgrade.core.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.platform.ServiceConfig;
import com.jaspersoft.jrsupgrade.core.platform.UserPaths;
import com.jaspersoft.jrsupgrade.core.secrets.SecretRef;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Builds the effective {@link Config} (spec §5.1) with precedence flag &gt; env &gt; file &gt;
 * default. The YAML file (if any), then {@code JRS_UPGRADE_<UPPER_SNAKE_PATH>} variables, then
 * dotted flag keys are merged into one JSON tree which is validated against the bundled {@code
 * schema/config.schema.json} before being mapped to records. Invariants: a missing file is not an
 * error (everything defaults, and {@link #requireServer} is the place that insists on a server);
 * every schema violation is reported at once, as {@code path: message}, in one {@link
 * ConfigException}; only keys the schema knows are read from the environment, so unrelated {@code
 * JRS_UPGRADE_*} variables (HOME, PASSPHRASE) are never mistaken for configuration.
 */
public final class ConfigLoader {

  public static final String ENV_PREFIX = "JRS_UPGRADE_";
  private static final java.util.regex.Pattern COMMA = java.util.regex.Pattern.compile(",");
  static final String SCHEMA_RESOURCE = "/schema/config.schema.json";

  private final JsonSchema schema;
  private final Map<String, SchemaKeys.Type> leafKeys;
  private final YAMLMapper yaml = new YAMLMapper();

  public ConfigLoader() {
    JsonNode schemaNode;
    try (InputStream in = ConfigLoader.class.getResourceAsStream(SCHEMA_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(SCHEMA_RESOURCE + " is missing from the jar");
      }
      schemaNode = new ObjectMapper().readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + SCHEMA_RESOURCE, e);
    }
    this.schema =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode);
    this.leafKeys = SchemaKeys.leafPaths(schemaNode);
  }

  /** Loads {@code home.configFile()} (optional) and overlays {@code env} then {@code flags}. */
  public Config load(JrsUpgradeHome home, Map<String, String> env, Map<String, String> flags) {
    Objects.requireNonNull(home, "home");
    if (Files.isRegularFile(home.yamlConfigFile())
        && Files.isRegularFile(home.propertiesConfigFile())) {
      throw new ConfigException(
          "both "
              + home.yamlConfigFile()
              + " and "
              + home.propertiesConfigFile()
              + " exist, so it is unclear which configuration is meant",
          "keep one of them: move the other out of the jrs-upgrade home");
    }
    return load(home.configFile(), env, flags);
  }

  /** As {@link #load(JrsUpgradeHome, Map, Map)} for an explicit file path. */
  public Config load(Path file, Map<String, String> env, Map<String, String> flags) {
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(env, "env");
    Objects.requireNonNull(flags, "flags");
    ObjectNode tree = readTree(file);
    for (Map.Entry<String, SchemaKeys.Type> key : leafKeys.entrySet()) {
      String value = env.get(envKey(key.getKey()));
      if (value != null) {
        put(tree, key.getKey(), coerce(value, key.getValue()));
      }
    }
    for (Map.Entry<String, String> flag : flags.entrySet()) {
      if (flag.getKey().startsWith("console.")) {
        throw new ConfigException(
            flag.getKey() + " is no longer used (ADR-0038)",
            "remove console.* from the command line: jrs-upgrade has no web console");
      }
      SchemaKeys.Type type = leafKeys.getOrDefault(flag.getKey(), SchemaKeys.Type.STRING);
      put(tree, flag.getKey(), coerce(flag.getValue(), type));
    }
    validate(tree);
    return toConfig(tree, env);
  }

  /** Where a key's effective value comes from, highest precedence first. */
  public enum Origin {
    FLAG,
    ENVIRONMENT,
    FILE,
    DEFAULT
  }

  /** The origin of one key's value and what names it: the variable, {@code --set} or the file. */
  public record Source(Origin origin, String detail) {}

  /**
   * For every known key, in schema order, where its effective value comes from under the precedence
   * {@code --set} over environment over file over default (#70).
   */
  public Map<String, Source> sources(
      Path file, Map<String, String> env, Map<String, String> flags) {
    Objects.requireNonNull(file, "file");
    Objects.requireNonNull(env, "env");
    Objects.requireNonNull(flags, "flags");
    ObjectNode tree = readTree(file);
    Map<String, Source> out = new LinkedHashMap<>();
    for (String key : leafKeys.keySet()) {
      String variable = envKey(key);
      if (flags.containsKey(key)) {
        out.put(key, new Source(Origin.FLAG, "--set " + key));
      } else if (env.containsKey(variable)) {
        out.put(key, new Source(Origin.ENVIRONMENT, variable));
      } else if (!tree.at("/" + key.replace('.', '/')).isMissingNode()) {
        out.put(key, new Source(Origin.FILE, file.toString()));
      } else {
        out.put(key, new Source(Origin.DEFAULT, "default"));
      }
    }
    return out;
  }

  /**
   * The configuration {@code file} alone (no environment, no flags) with {@code key} set to {@code
   * raw}, coerced and validated as {@code --set} is; the file is not written (#70).
   */
  public Config fileWith(Path file, String key, String raw) {
    Objects.requireNonNull(raw, "raw");
    requireKnown(key);
    ObjectNode tree = readTree(Objects.requireNonNull(file, "file"));
    put(tree, key, coerce(raw, leafKeys.get(key)));
    validate(tree);
    return toConfig(tree, Map.of());
  }

  /** As {@link #fileWith} with {@code key} removed, so its default (or nothing) applies. */
  public Config fileWithout(Path file, String key) {
    requireKnown(key);
    ObjectNode tree = readTree(Objects.requireNonNull(file, "file"));
    String[] segments = key.split("\\.", -1);
    JsonNode parent = tree.at("/" + String.join("/", Arrays.copyOf(segments, segments.length - 1)));
    if (parent instanceof ObjectNode obj) {
      obj.remove(segments[segments.length - 1]);
    }
    validate(tree);
    return toConfig(tree, Map.of());
  }

  /**
   * The configuration {@code file} alone with the 1.x {@code console:} block (or {@code console.*}
   * properties lines) removed, for {@code config unset console} to write back (ADR-0038); empty
   * when the file has no console entry, so there is nothing to rewrite. Emits no warning: the
   * caller is removing what the warning is about.
   */
  public Optional<Config> fileWithoutConsole(Path file) {
    List<String> dropped = new ArrayList<>();
    ObjectNode tree = readTree(Objects.requireNonNull(file, "file"), dropped::add);
    if (dropped.isEmpty()) {
      return Optional.empty();
    }
    validate(tree);
    return Optional.of(toConfig(tree, Map.of()));
  }

  private void requireKnown(String key) {
    Objects.requireNonNull(key, "key");
    if (key.startsWith("console.")) {
      throw new ConfigException(
          key + " is no longer used (ADR-0038)",
          "jrs-upgrade has no web console; remove the whole block with: jrs-upgrade config unset console");
    }
    if (!leafKeys.containsKey(key)) {
      throw new ConfigException(
          "unknown configuration key " + key, "list the keys with: jrs-upgrade config keys");
    }
  }

  /**
   * ADR-0038, #154: a 1.x file may still carry {@code console:}; it is reported to {@code found},
   * which refuses it on every read except the one {@code config unset console} makes to remove it.
   */
  private static void dropConsoleBlock(ObjectNode tree, Path file, Consumer<String> found) {
    if (tree.remove("console") != null) {
      found.accept("console: in " + file);
    }
  }

  /** The refusal every ordinary read makes of a 1.x console entry (#154). */
  private static void refuseConsole(String what) {
    throw new ConfigException(
        what
            + " is a jrs-upgrade 1.x web console setting, which jrs-upgrade 2.1 no longer reads (ADR-0038)",
        "remove it with: jrs-upgrade config unset console");
  }

  /**
   * {@code base} with {@code overrides} (dotted key to raw value) applied, coerced and validated
   * exactly as {@code --set} values are; a {@link ConfigException} names the key it refuses.
   */
  public Config withOverrides(Config base, Map<String, String> overrides) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(overrides, "overrides");
    ObjectNode tree = ConfigWriter.toTree(base);
    for (Map.Entry<String, String> o : overrides.entrySet()) {
      SchemaKeys.Type type = leafKeys.getOrDefault(o.getKey(), SchemaKeys.Type.STRING);
      put(tree, o.getKey(), coerce(o.getValue(), type));
    }
    validate(tree);
    return toConfig(tree, Map.of());
  }

  /** Validates an already merged tree and maps it; exposed for {@code doctor}. */
  public Config fromTree(JsonNode tree) {
    if (!(tree instanceof ObjectNode obj)) {
      throw new ConfigException("configuration root is not a mapping", "check config.yaml");
    }
    validate(obj);
    return toConfig(obj, Map.of());
  }

  /** Every leaf key the schema knows, in schema order. */
  public Set<String> knownKeys() {
    return leafKeys.keySet();
  }

  /**
   * The server base URL, or a {@link ConfigException} telling the operator to run {@code
   * jrs-upgrade init} when the configuration has no server.
   */
  public static URI requireServer(Config config) {
    return config
        .server()
        .baseUrl()
        .orElseThrow(
            () ->
                new ConfigException(
                    "server.baseUrl is not configured; no config.yaml was found or it has no"
                        + " server section",
                    "run jrs-upgrade init"));
  }

  /** {@code server.auth.passwordRef} becomes {@code JRS_UPGRADE_SERVER_AUTH_PASSWORD_REF}. */
  public static String envKey(String dottedPath) {
    StringBuilder sb = new StringBuilder(ENV_PREFIX);
    boolean first = true;
    for (String segment : dottedPath.split("\\.", -1)) {
      if (!first) {
        sb.append('_');
      }
      first = false;
      for (int i = 0; i < segment.length(); i++) {
        char c = segment.charAt(i);
        if (Character.isUpperCase(c) && i > 0) {
          sb.append('_');
        }
        if (c == '-') {
          sb.append('_');
        } else {
          sb.append(Character.toUpperCase(c));
        }
      }
    }
    return sb.toString().toUpperCase(Locale.ROOT);
  }

  // ---- tree assembly ----------------------------------------------------------------------------

  /**
   * {@code file} parsed; the single point every file-reading entry point ({@link #load(Path, Map,
   * Map)}, {@link #sources}, {@link #fileWith} and {@link #fileWithout}) goes through, so a 1.x
   * {@code console:} block or {@code console.*} line is refused wherever a file is read (ADR-0038,
   * #154). Only {@link #fileWithoutConsole} reads past it, to remove it.
   */
  private ObjectNode readTree(Path file) {
    return readTree(file, ConfigLoader::refuseConsole);
  }

  /** As {@link #readTree(Path)}, with each console entry reported to {@code dropped}. */
  private ObjectNode readTree(Path file, Consumer<String> dropped) {
    ObjectNode tree = readFile(file, dropped);
    dropConsoleBlock(tree, file, dropped);
    return tree;
  }

  private ObjectNode readFile(Path file, Consumer<String> dropped) {
    if (!Files.isRegularFile(file)) {
      return JsonNodeFactory.instance.objectNode();
    }
    if (file.getFileName().toString().endsWith(".properties")) {
      return readProperties(file, dropped);
    }
    JsonNode root;
    try (InputStream in = Files.newInputStream(file)) {
      root = yaml.readTree(in);
    } catch (IOException e) {
      throw new ConfigException(
          "cannot parse " + file + ": " + e.getMessage(), "fix the YAML syntax in " + file);
    }
    if (root == null || root.isNull() || root.isMissingNode()) {
      return JsonNodeFactory.instance.objectNode();
    }
    if (!(root instanceof ObjectNode obj)) {
      throw new ConfigException(
          file + " does not contain a top-level mapping", "start the file with server: ...");
    }
    return obj;
  }

  /**
   * {@code key=value} lines with the dotted keys {@code --set} uses (#74). Values are taken
   * literally, as buildomatic's properties are read: a backslash is not an escape, so a Windows
   * path needs no doubling; {@code #} and {@code !} start comments; {@code =}, {@code :} or
   * whitespace separates key and value; an unknown or repeated key is refused with its line number.
   */
  private ObjectNode readProperties(Path file, Consumer<String> dropped) {
    ObjectNode tree = JsonNodeFactory.instance.objectNode();
    Set<String> seen = new java.util.HashSet<>();
    List<String> lines;
    try {
      lines = Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new ConfigException(
          "cannot read " + file + ": " + e.getMessage(), "check the file's permissions");
    }
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i).strip();
      if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
        continue;
      }
      int sep = -1;
      for (int k = 0; k < line.length(); k++) {
        char c = line.charAt(k);
        if (c == '=' || c == ':' || Character.isWhitespace(c)) {
          sep = k;
          break;
        }
      }
      String key = (sep < 0 ? line : line.substring(0, sep)).strip();
      String value = sep < 0 ? "" : line.substring(sep + 1).strip();
      if (value.startsWith("=") || value.startsWith(":")) {
        value = value.substring(1).strip();
      }
      String where = file + " line " + (i + 1);
      if (key.startsWith("console.")) {
        dropped.accept(key + " at " + where);
        continue;
      }
      if (!leafKeys.containsKey(key)) {
        throw new ConfigException(
            "unknown configuration key " + key + " at " + where,
            "list the keys with: jrs-upgrade config keys");
      }
      if (!seen.add(key)) {
        throw new ConfigException(
            "configuration key " + key + " is given twice, again at " + where,
            "keep one line for " + key);
      }
      put(tree, key, coerce(value, leafKeys.get(key)));
    }
    return tree;
  }

  private static void put(ObjectNode root, String dottedPath, JsonNode value) {
    String[] segments = dottedPath.split("\\.", -1);
    ObjectNode node = root;
    for (int i = 0; i < segments.length - 1; i++) {
      JsonNode child = node.get(segments[i]);
      if (child == null || child.isNull()) {
        child = node.putObject(segments[i]);
      } else if (!(child instanceof ObjectNode)) {
        throw new ConfigException(
            String.join(".", Arrays.copyOf(segments, i + 1))
                + " is not a mapping, so "
                + dottedPath
                + " cannot be set",
            "fix config.yaml");
      }
      node = (ObjectNode) child;
    }
    node.set(segments[segments.length - 1], value);
  }

  private static JsonNode coerce(String raw, SchemaKeys.Type type) {
    String v = raw.strip();
    return switch (type) {
      case STRING -> TextNode.valueOf(raw);
      case NULLABLE_STRING ->
          v.isEmpty() || v.equals("null") ? NullNode.getInstance() : new TextNode(raw);
      case INTEGER -> {
        try {
          yield IntNode.valueOf(Integer.parseInt(v));
        } catch (NumberFormatException e) {
          yield TextNode.valueOf(raw);
        }
      }
      case BOOLEAN -> {
        String lower = v.toLowerCase(Locale.ROOT);
        yield lower.equals("true") || lower.equals("false")
            ? BooleanNode.valueOf(lower.equals("true"))
            : TextNode.valueOf(raw);
      }
      case LIST -> {
        com.fasterxml.jackson.databind.node.ArrayNode items = JsonNodeFactory.instance.arrayNode();
        COMMA.splitAsStream(v).map(String::strip).filter(s -> !s.isEmpty()).forEach(items::add);
        yield items;
      }
    };
  }

  private void validate(ObjectNode tree) {
    Set<ValidationMessage> messages = schema.validate(tree);
    if (messages.isEmpty()) {
      return;
    }
    List<String> violations = new ArrayList<>();
    for (ValidationMessage m : messages) {
      String location = m.getInstanceLocation().toString();
      String path = location.startsWith("$.") ? location.substring(2) : location;
      if (path.equals("$") || path.isEmpty()) {
        path = "(root)";
      }
      String text = m.getMessage();
      String prefix = location + ": ";
      if (text.startsWith(prefix)) {
        text = text.substring(prefix.length());
      }
      violations.add(path + ": " + text);
    }
    violations.sort(String::compareTo);
    throw ConfigException.violations(violations);
  }

  // ---- tree to records --------------------------------------------------------------------------

  /**
   * Maps a validated tree; {@code env} supplies the home directory a leading {@code ~} in a path
   * value stands for (field test 2, G3), so a path is expanded once, here, for every source.
   */
  private static Config toConfig(ObjectNode root, Map<String, String> env) {
    JsonNode server = root.path("server");
    JsonNode auth = server.path("auth");
    JsonNode service = root.path("service");
    JsonNode database = root.path("database");
    JsonNode vendor = root.path("vendor");
    JsonNode network = root.path("network");
    JsonNode proxy = network.path("proxy");
    JsonNode trust = network.path("trustStore");
    JsonNode backups = root.path("backups");
    JsonNode smoke = root.path("smoke");

    return new Config(
        new Config.Server(
            text(server, "baseUrl").map(v -> uri("server.baseUrl", v)),
            text(server, "webappName")
                .map(v -> yamlEnum("server.webappName", Config.WebappName.class, v)),
            text(server, "installDir").map(v -> path("server.installDir", v, env)),
            text(server, "tomcatDir").map(v -> path("server.tomcatDir", v, env)),
            text(server, "buildomaticDir").map(v -> path("server.buildomaticDir", v, env)),
            text(server, "runAsUser"),
            new Config.Auth(
                text(auth, "mode")
                    .map(v -> yamlEnum("server.auth.mode", Config.AuthMode.class, v))
                    .orElse(Config.AuthMode.DEFAULT),
                text(auth, "username"),
                text(auth, "passwordRef").map(v -> secretRef("server.auth.passwordRef", v)),
                text(auth, "tokenLocation")
                    .map(v -> yamlEnum("server.auth.tokenLocation", Config.TokenLocation.class, v))
                    .orElse(Config.TokenLocation.DEFAULT))),
        new Config.Service(
            text(service, "kind").map(v -> serviceKind("service.kind", v)),
            text(service, "name"),
            text(service, "scriptPath").map(v -> path("service.scriptPath", v, env)),
            integer(service, "stopTimeoutSeconds")
                .orElse(Config.Service.DEFAULT_STOP_TIMEOUT_SECONDS),
            integer(service, "forceStopAfterSeconds")),
        new Config.Database(
            text(database, "type")
                .map(v -> yamlEnum("database.type", Config.DatabaseType.class, v)),
            text(database, "url"),
            text(database, "username"),
            text(database, "passwordRef").map(v -> secretRef("database.passwordRef", v)),
            text(database, "driverDir").map(v -> path("database.driverDir", v, env))),
        new Config.Vendor(text(vendor, "javaHome").map(v -> path("vendor.javaHome", v, env))),
        new Config.Network(
            text(network, "mode")
                .map(v -> yamlEnum("network.mode", Config.NetworkMode.class, v))
                .orElse(Config.NetworkMode.DEFAULT),
            new Config.Proxy(
                text(proxy, "host"),
                integer(proxy, "port"),
                text(proxy, "username"),
                text(proxy, "passwordRef").map(v -> secretRef("network.proxy.passwordRef", v)),
                strings(proxy, "noProxy")),
            new Config.TrustStore(
                text(trust, "path").map(v -> path("network.trustStore.path", v, env)),
                text(trust, "passwordRef")
                    .map(v -> secretRef("network.trustStore.passwordRef", v)))),
        new Config.Backups(
            integer(backups, "retentionDays").orElse(Config.Backups.DEFAULT_RETENTION_DAYS),
            integer(backups, "maxSnapshots").orElse(Config.Backups.DEFAULT_MAX_SNAPSHOTS)),
        new Config.Smoke(text(smoke, "reportUri")));
  }

  private static Optional<String> text(JsonNode parent, String field) {
    JsonNode v = parent.get(field);
    return v == null || v.isNull() || v.isMissingNode()
        ? Optional.empty()
        : Optional.of(v.asText());
  }

  private static List<String> strings(JsonNode parent, String field) {
    JsonNode v = parent.path(field);
    if (!v.isArray()) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (JsonNode item : v) {
      if (!item.isNull() && !item.asText().isBlank()) {
        out.add(item.asText().strip());
      }
    }
    return List.copyOf(out);
  }

  private static Optional<Integer> integer(JsonNode parent, String field) {
    JsonNode v = parent.get(field);
    return v == null || !v.isNumber() ? Optional.empty() : Optional.of(v.intValue());
  }

  private static URI uri(String key, String value) {
    try {
      return new URI(value);
    } catch (URISyntaxException e) {
      throw invalid(key, "is not a valid URI (" + e.getReason() + ")");
    }
  }

  private static Path path(String key, String value, Map<String, String> env) {
    try {
      return Path.of(UserPaths.expand(value, env));
    } catch (InvalidPathException e) {
      throw invalid(key, "is not a valid path (" + e.getReason() + ")");
    }
  }

  private static SecretRef secretRef(String key, String value) {
    try {
      return SecretRef.parse(value);
    } catch (IllegalArgumentException e) {
      throw invalid(key, e.getMessage());
    }
  }

  private static <E extends Enum<E> & Config.YamlValued> E yamlEnum(
      String key, Class<E> type, String value) {
    return Config.YamlValued.fromYaml(type, value).orElseThrow(unknownValue(key, value));
  }

  private static ServiceConfig.Kind serviceKind(String key, String value) {
    return Config.Service.kindFromYaml(value).orElseThrow(unknownValue(key, value));
  }

  private static Supplier<ConfigException> unknownValue(String key, String value) {
    return () -> invalid(key, "has unsupported value '" + value + "'");
  }

  private static ConfigException invalid(String key, String problem) {
    return ConfigException.violations(List.of(key + ": " + problem));
  }
}
