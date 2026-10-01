package com.jaspersoft.jrsupgrade.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.jaspersoft.jrsupgrade.core.json.Json;
import java.util.ArrayList;
import java.util.List;

/** Reads the concatenated JSON documents a {@code --json} command prints, in order. */
final class JsonDocs {

  private JsonDocs() {}

  static List<JsonNode> documents(String text) throws Exception {
    List<JsonNode> docs = new ArrayList<>();
    try (MappingIterator<JsonNode> it = Json.mapper().readerFor(JsonNode.class).readValues(text)) {
      while (it.hasNext()) {
        docs.add(it.next());
      }
    }
    return docs;
  }
}
