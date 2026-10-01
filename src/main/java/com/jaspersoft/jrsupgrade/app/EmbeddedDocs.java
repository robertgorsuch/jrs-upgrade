package com.jaspersoft.jrsupgrade.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The operator documentation embedded in the jar under {@code /docs/} at build time (spec §14 Phase
 * 8 "offline docs embedded", §17): {@code README.md}, {@code operator-guide.md}, {@code
 * security.md}, copied from the repository by the app module's build. Invariants: the set of
 * document names is fixed here (no classpath scanning), every name is a stable lowercase identifier
 * that {@code jrs-upgrade docs <name>} accepts, a document is streamed line by line to the caller's
 * writer (never held as one byte array) and a document that the build did not embed is reported as
 * absent rather than as an empty text.
 */
final class EmbeddedDocs {

  /** One embedded document: its command-line name, first-heading title and size in bytes. */
  record Doc(String name, String title, long bytes) {}

  private static final Map<String, String> RESOURCES =
      Map.of(
          "readme", "/docs/README.md",
          "operator-guide", "/docs/operator-guide.md",
          "recovery-runbook", "/docs/recovery-runbook.md",
          "security", "/docs/security.md");

  /** Display order of the listing; the operator guide first because it is the one to read. */
  static final List<String> NAMES =
      List.of("operator-guide", "recovery-runbook", "security", "readme");

  private EmbeddedDocs() {}

  /** Every embedded document that the build actually copied, in {@link #NAMES} order. */
  static List<Doc> list() {
    List<Doc> docs = new ArrayList<>();
    for (String name : NAMES) {
      describe(name).ifPresent(docs::add);
    }
    return List.copyOf(docs);
  }

  /** Metadata of one document, empty when the name is unknown or the build did not embed it. */
  static Optional<Doc> describe(String name) {
    String resource = RESOURCES.get(name);
    if (resource == null) {
      return Optional.empty();
    }
    try (InputStream in = EmbeddedDocs.class.getResourceAsStream(resource)) {
      if (in == null) {
        return Optional.empty();
      }
      long bytes = in.transferTo(OutputStream.nullOutputStream());
      return Optional.of(new Doc(name, title(resource), bytes));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read embedded document " + resource, e);
    }
  }

  /** The document's lines, empty when the name is unknown or the document is not embedded. */
  static Optional<List<String>> lines(String name) {
    String resource = RESOURCES.get(name);
    if (resource == null) {
      return Optional.empty();
    }
    try (InputStream in = EmbeddedDocs.class.getResourceAsStream(resource)) {
      if (in == null) {
        return Optional.empty();
      }
      List<String> lines = new ArrayList<>();
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null) {
        lines.add(line);
      }
      return Optional.of(List.copyOf(lines));
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read embedded document " + resource, e);
    }
  }

  /** Streams the document to {@code out}; false when the name is unknown or not embedded. */
  static boolean print(String name, PrintWriter out) {
    String resource = RESOURCES.get(name);
    if (resource == null) {
      return false;
    }
    try (InputStream in = EmbeddedDocs.class.getResourceAsStream(resource)) {
      if (in == null) {
        return false;
      }
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null) {
        out.println(line);
      }
      out.flush();
      return true;
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read embedded document " + resource, e);
    }
  }

  private static String title(String resource) throws IOException {
    try (InputStream in = EmbeddedDocs.class.getResourceAsStream(resource)) {
      if (in == null) {
        return "";
      }
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.startsWith("# ")) {
          return line.substring(2).trim();
        }
      }
      return "";
    }
  }
}
