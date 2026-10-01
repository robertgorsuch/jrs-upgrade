package com.jaspersoft.jrsupgrade.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Single JSON writer for {@code --json} output so every command serialises the same way (spec §14
 * Phase 8 validates this output against the schemas under {@code schema/json/}, see {@link
 * JsonSchemas}). Invariants: output is deterministic; {@code Optional.empty()} becomes {@code null}
 * and dates are ISO-8601 strings; an error or refusal in JSON mode is exactly one {@code {"error":
 * {"class", "message", "exitCode", ["remediation"], ["details"]}}} document on standard output
 * (never free text), so a machine consumer reads one stream; every document written through {@link
 * #print} passes the global redaction filter.
 */
final class JsonOut {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .registerModule(new Jdk8Module())
          .enable(SerializationFeature.INDENT_OUTPUT)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  private JsonOut() {}

  static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cannot serialise " + value.getClass().getSimpleName(), e);
    }
  }

  /** Writes one redacted JSON document to {@code out} and flushes. */
  static void print(PrintWriter out, Object value) {
    out.println(Redactor.global().redact(write(value)));
    out.flush();
  }

  /**
   * The error document every command emits in JSON mode when it refuses or fails: {@code
   * errorClass} is the exception's simple name or the exit code's category from {@link
   * ExitCodes#className}; {@code remediation} and {@code details} are optional.
   */
  static Map<String, Object> error(
      String errorClass,
      String message,
      int exitCode,
      Optional<String> remediation,
      Map<String, Object> details) {
    Objects.requireNonNull(errorClass, "errorClass");
    Objects.requireNonNull(message, "message");
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("class", errorClass);
    error.put("message", message);
    error.put("exitCode", exitCode);
    remediation.ifPresent(r -> error.put("remediation", r));
    if (!details.isEmpty()) {
      error.put("details", details);
    }
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("error", error);
    return root;
  }

  static Map<String, Object> error(String errorClass, String message, int exitCode) {
    return error(errorClass, message, exitCode, Optional.empty(), Map.of());
  }
}
