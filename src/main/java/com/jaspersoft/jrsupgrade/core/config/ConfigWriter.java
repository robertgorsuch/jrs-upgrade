package com.jaspersoft.jrsupgrade.core.config;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.jaspersoft.jrsupgrade.core.secrets.SecretRef;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Serialises a {@link Config} to {@code config.yaml} with exactly the key names of spec §5.1, so a
 * file written by {@code init} loads back through {@link ConfigLoader} to an equal {@link Config}.
 * Invariants: absent optionals produce no key; present values (including the defaults, which are
 * always present) are written explicitly so the operator sees them; secret references are written
 * as references, never as values; the write streams through java.nio and creates parent
 * directories.
 */
public final class ConfigWriter {

  private static final YAMLMapper YAML =
      YAMLMapper.builder()
          .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
          .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
          .build();

  private ConfigWriter() {}

  public static void write(Config config, Path file) throws IOException {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(file, "file");
    Path parent = file.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    boolean properties = file.getFileName().toString().endsWith(".properties");
    try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      out.write(
          "# jrs-upgrade configuration (docs/spec.md section 5.1). Secrets are references only.\n");
      if (properties) {
        out.write(
            "# Values are literal: a backslash needs no escaping. Lists are comma-separated.\n");
        out.write(renderProperties(config));
      } else {
        YAML.writeValue(out, toTree(config));
      }
    }
  }

  /**
   * The configuration as {@code key=value} lines with dotted keys, in schema order (#74); lists are
   * comma-separated and values are written literally, as the loader reads them.
   */
  public static String renderProperties(Config config) {
    StringBuilder sb = new StringBuilder();
    flatten(toTree(config), "", sb);
    return sb.toString();
  }

  private static void flatten(
      com.fasterxml.jackson.databind.JsonNode node, String prefix, StringBuilder sb) {
    if (node.isObject()) {
      for (java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> e :
          node.properties()) {
        flatten(e.getValue(), prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), sb);
      }
      return;
    }
    String value;
    if (node.isArray()) {
      java.util.List<String> items = new java.util.ArrayList<>();
      node.forEach(n -> items.add(n.asText()));
      value = String.join(",", items);
    } else if (node.isNull()) {
      value = "";
    } else {
      value = node.asText();
    }
    sb.append(prefix).append('=').append(value).append('\n');
  }

  /** The YAML as a string, for {@code --json}/dry-run display. */
  public static String render(Config config) {
    try {
      return YAML.writeValueAsString(toTree(config));
    } catch (IOException e) {
      throw new IllegalStateException("cannot render configuration", e);
    }
  }

  /** The configuration as a JSON tree with spec key names; absent optionals are omitted. */
  public static ObjectNode toTree(Config c) {
    ObjectNode root = JsonNodeFactory.instance.objectNode();

    ObjectNode server = root.putObject("server");
    c.server().baseUrl().ifPresent(v -> server.put("baseUrl", v.toString()));
    c.server().webappName().ifPresent(v -> server.put("webappName", v.yamlValue()));
    path(c.server().installDir(), server, "installDir");
    path(c.server().tomcatDir(), server, "tomcatDir");
    path(c.server().buildomaticDir(), server, "buildomaticDir");
    c.server().runAsUser().ifPresent(v -> server.put("runAsUser", v));
    ObjectNode auth = server.putObject("auth");
    auth.put("mode", c.server().auth().mode().yamlValue());
    c.server().auth().username().ifPresent(v -> auth.put("username", v));
    ref(c.server().auth().passwordRef(), auth);
    if (c.server().auth().tokenLocation() != Config.TokenLocation.DEFAULT) {
      auth.put("tokenLocation", c.server().auth().tokenLocation().yamlValue());
    }

    if (!hasContent(c.server())) {
      // the schema requires server.baseUrl whenever a server block exists, so a defaults-only
      // configuration must not write one
      root.remove("server");
    }

    ObjectNode service = root.putObject("service");
    c.service().kind().ifPresent(v -> service.put("kind", Config.Service.kindToYaml(v)));
    c.service().name().ifPresent(v -> service.put("name", v));
    path(c.service().scriptPath(), service, "scriptPath");
    service.put("stopTimeoutSeconds", c.service().stopTimeoutSeconds());
    c.service()
        .forceStopAfterSeconds()
        .ifPresent(v -> service.put("forceStopAfterSeconds", v.intValue()));

    ObjectNode database = root.putObject("database");
    c.database().type().ifPresent(v -> database.put("type", v.yamlValue()));
    c.database().url().ifPresent(v -> database.put("url", v));
    c.database().username().ifPresent(v -> database.put("username", v));
    ref(c.database().passwordRef(), database);
    path(c.database().driverDir(), database, "driverDir");

    ObjectNode vendor = root.putObject("vendor");
    path(c.vendor().javaHome(), vendor, "javaHome");

    ObjectNode network = root.putObject("network");
    network.put("mode", c.network().mode().yamlValue());
    ObjectNode proxy = network.putObject("proxy");
    c.network().proxy().host().ifPresent(v -> proxy.put("host", v));
    c.network().proxy().port().ifPresent(v -> proxy.put("port", v.intValue()));
    c.network().proxy().username().ifPresent(v -> proxy.put("username", v));
    ref(c.network().proxy().passwordRef(), proxy);
    if (!c.network().proxy().noProxy().isEmpty()) {
      var noProxy = proxy.putArray("noProxy");
      c.network().proxy().noProxy().forEach(noProxy::add);
    }
    ObjectNode trust = network.putObject("trustStore");
    path(c.network().trustStore().path(), trust, "path");
    ref(c.network().trustStore().passwordRef(), trust);

    ObjectNode backups = root.putObject("backups");
    backups.put("retentionDays", c.backups().retentionDays());
    backups.put("maxSnapshots", c.backups().maxSnapshots());

    ObjectNode smoke = root.putObject("smoke");
    c.smoke().reportUri().ifPresent(v -> smoke.put("reportUri", v));

    return root;
  }

  private static boolean hasContent(Config.Server s) {
    return s.baseUrl().isPresent()
        || s.webappName().isPresent()
        || s.installDir().isPresent()
        || s.tomcatDir().isPresent()
        || s.buildomaticDir().isPresent()
        || s.runAsUser().isPresent()
        || s.auth().username().isPresent()
        || s.auth().passwordRef().isPresent()
        || s.auth().mode() != Config.AuthMode.DEFAULT;
  }

  private static void path(Optional<Path> value, ObjectNode node, String field) {
    value.ifPresent(v -> node.put(field, v.toString()));
  }

  private static void ref(Optional<SecretRef> value, ObjectNode node) {
    value.ifPresent(v -> node.put("passwordRef", v.render()));
  }
}
