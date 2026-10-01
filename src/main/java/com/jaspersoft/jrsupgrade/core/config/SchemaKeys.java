package com.jaspersoft.jrsupgrade.core.config;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Derives the set of leaf configuration keys and their JSON types from the bundled schema, so the
 * environment-variable and flag overlays never drift from {@code config.schema.json}.
 */
final class SchemaKeys {

  /** JSON type of a leaf key as far as the overlays need to know. */
  enum Type {
    STRING,
    INTEGER,
    BOOLEAN,
    NULLABLE_STRING,
    /** An array of strings, given as one comma-separated value (#74). */
    LIST
  }

  private SchemaKeys() {}

  /** Dotted leaf path (e.g. {@code server.auth.passwordRef}) to type, in schema order. */
  static Map<String, Type> leafPaths(JsonNode schema) {
    Map<String, Type> out = new LinkedHashMap<>();
    walk(schema, "", out);
    return Collections.unmodifiableMap(out);
  }

  private static void walk(JsonNode node, String prefix, Map<String, Type> out) {
    JsonNode props = node.get("properties");
    if (props == null || !props.isObject()) {
      out.put(prefix, typeOf(node));
      return;
    }
    for (Map.Entry<String, JsonNode> e : props.properties()) {
      String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
      walk(e.getValue(), path, out);
    }
  }

  private static Type typeOf(JsonNode node) {
    JsonNode type = node.get("type");
    if (type == null) {
      return Type.STRING; // $ref to secretRef, or untyped
    }
    if (type.isArray()) {
      boolean nullable = false;
      String base = "string";
      for (JsonNode t : type) {
        if ("null".equals(t.asText())) {
          nullable = true;
        } else {
          base = t.asText();
        }
      }
      return nullable && base.equals("string") ? Type.NULLABLE_STRING : simple(base);
    }
    return simple(type.asText());
  }

  private static Type simple(String jsonType) {
    return switch (jsonType.toLowerCase(Locale.ROOT)) {
      case "integer", "number" -> Type.INTEGER;
      case "boolean" -> Type.BOOLEAN;
      case "array" -> Type.LIST;
      default -> Type.STRING;
    };
  }
}
