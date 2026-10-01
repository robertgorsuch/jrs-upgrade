package com.jaspersoft.jrsupgrade.core.snapshot;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Jackson configuration for {@code manifest.json}. Invariants: {@link Path} values are written as
 * plain platform strings (never {@code file:} URIs) and read back with {@link Path#of}; instants
 * are ISO-8601 text; unknown properties are ignored so a newer jrs-upgrade can still read manifests
 * it did not write; reading and writing stream through the file, never via a whole-file byte array.
 */
final class SnapshotJson {

  private static final ObjectMapper MAPPER = build();

  private SnapshotJson() {}

  private static ObjectMapper build() {
    SimpleModule paths = new SimpleModule("jrs-upgrade-paths");
    paths.addSerializer(Path.class, ToStringSerializer.instance);
    paths.addDeserializer(
        Path.class,
        new JsonDeserializer<>() {
          @Override
          public Path deserialize(JsonParser parser, DeserializationContext context)
              throws IOException {
            return Path.of(parser.getValueAsString());
          }
        });
    return new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .registerModule(new Jdk8Module())
        .registerModule(paths)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(SerializationFeature.INDENT_OUTPUT);
  }

  static void write(SnapshotManifest manifest, Path file) throws IOException {
    try (OutputStream out =
        Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      MAPPER.writeValue(out, manifest);
    }
  }

  static SnapshotManifest read(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      return MAPPER.readValue(in, SnapshotManifest.class);
    }
  }
}
