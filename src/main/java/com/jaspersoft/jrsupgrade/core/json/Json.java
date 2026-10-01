package com.jaspersoft.jrsupgrade.core.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jaspersoft.jrsupgrade.core.engine.StepFailure;
import com.jaspersoft.jrsupgrade.core.event.Event;

/**
 * The one shared Jackson configuration for jrs-upgrade (spec §5.9, §6.2). Invariants: dates are
 * ISO-8601 strings, {@code Optional} serialises as the value or {@code null}, unknown properties
 * are ignored on read so older clients keep working, and the sealed {@code Event} and {@code
 * StepFailure} hierarchies carry a {@code type} discriminator equal to the record's simple name so
 * every variant round-trips. Mappers are immutable after construction and safe to share between
 * threads.
 */
public final class Json {

  private static final ObjectMapper COMPACT = build(false);
  private static final ObjectMapper PRETTY = build(true);

  private Json() {}

  /** Compact output; the mapper used for {@code --json} lines and state-store columns. */
  public static ObjectMapper mapper() {
    return COMPACT;
  }

  /** Same configuration with {@code INDENT_OUTPUT} enabled, for files humans read. */
  public static ObjectMapper pretty() {
    return PRETTY;
  }

  public static String write(Object value) {
    try {
      return COMPACT.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("cannot serialise " + value.getClass().getName(), e);
    }
  }

  public static String writePretty(Object value) {
    try {
      return PRETTY.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("cannot serialise " + value.getClass().getName(), e);
    }
  }

  public static <T> T read(String json, Class<T> type) {
    try {
      return COMPACT.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("cannot deserialise " + type.getSimpleName(), e);
    }
  }

  private static ObjectMapper build(boolean pretty) {
    JsonMapper.Builder b =
        JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .addModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .addMixIn(Event.class, EventMixin.class)
            .addMixIn(StepFailure.class, StepFailureMixin.class);
    if (pretty) {
      b.enable(SerializationFeature.INDENT_OUTPUT);
    }
    return b.build();
  }
}
