package com.jaspersoft.jrsupgrade.core.platform;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts ports from Tomcat's {@code conf/server.xml} without a full XML parse. Invariants: the
 * file is read line by line; XML comments are skipped so the commented-out sample connectors Tomcat
 * ships never win; for {@link #httpPort} the first plain HTTP/1.1 (or NIO/APR HTTP) connector is
 * preferred, an SSL-enabled one is the fallback, AJP connectors are ignored; {@link #ports} is
 * every port the Tomcat binds by configuration, the {@code Server} shutdown port (unless disabled
 * with a non-positive value) and every connector of any protocol; a malformed or missing file
 * yields empty rather than an exception because detection must never abort {@code init}.
 */
final class ServerXml {

  private static final Pattern ATTRIBUTE = Pattern.compile("(\\w+)\\s*=\\s*\"([^\"]*)\"");
  private static final String CONNECTOR_OPEN = "<Connector";
  private static final String SERVER_OPEN = "<Server";
  private static final String COMMENT_OPEN = "<!--";
  private static final String COMMENT_CLOSE = "-->";

  private boolean inComment;
  private StringBuilder element;
  private Optional<Integer> plainHttp = Optional.empty();
  private Optional<Integer> sslHttp = Optional.empty();
  private final Set<Integer> ports = new TreeSet<>();

  private ServerXml() {}

  /** Every configured port of the Tomcat: the shutdown port and all connector ports. */
  static Set<Integer> ports(Path serverXml) {
    if (!Files.isRegularFile(serverXml)) {
      return Set.of();
    }
    ServerXml parser = new ServerXml();
    try (BufferedReader reader = Files.newBufferedReader(serverXml, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        parser.feed(parser.stripComments(line));
      }
    } catch (IOException | RuntimeException e) {
      return Set.of();
    }
    return Set.copyOf(parser.ports);
  }

  /**
   * {@link #ports} of the Tomcat at {@code dir}: its own {@code conf/server.xml}, else that of the
   * first {@code apache-tomcat*} or {@code tomcat*} child in name order; empty when there is none.
   */
  static Set<Integer> portsUnder(Path dir) {
    Path direct = dir.resolve("conf").resolve("server.xml");
    if (Files.isRegularFile(direct)) {
      return ports(direct);
    }
    List<Path> candidates = new ArrayList<>();
    try (DirectoryStream<Path> children = Files.newDirectoryStream(dir, Files::isDirectory)) {
      for (Path child : children) {
        String name = child.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.startsWith("apache-tomcat") || name.startsWith("tomcat")) {
          candidates.add(child);
        }
      }
    } catch (IOException | RuntimeException e) {
      return Set.of();
    }
    candidates.sort(null);
    for (Path candidate : candidates) {
      Path xml = candidate.resolve("conf").resolve("server.xml");
      if (Files.isRegularFile(xml)) {
        return ports(xml);
      }
    }
    return Set.of();
  }

  static Optional<Integer> httpPort(Path serverXml) {
    if (!Files.isRegularFile(serverXml)) {
      return Optional.empty();
    }
    ServerXml parser = new ServerXml();
    try (BufferedReader reader = Files.newBufferedReader(serverXml, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null && parser.plainHttp.isEmpty()) {
        parser.feed(parser.stripComments(line));
      }
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
    return parser.plainHttp.or(() -> parser.sslHttp);
  }

  private String stripComments(String line) {
    StringBuilder kept = new StringBuilder(line.length());
    int idx = 0;
    while (idx < line.length()) {
      if (inComment) {
        int close = line.indexOf(COMMENT_CLOSE, idx);
        if (close < 0) {
          return kept.toString();
        }
        inComment = false;
        idx = close + COMMENT_CLOSE.length();
      } else {
        int open = line.indexOf(COMMENT_OPEN, idx);
        if (open < 0) {
          kept.append(line, idx, line.length());
          return kept.toString();
        }
        kept.append(line, idx, open);
        inComment = true;
        idx = open + COMMENT_OPEN.length();
      }
    }
    return kept.toString();
  }

  private void feed(String text) {
    int idx = 0;
    while (idx < text.length()) {
      if (element == null) {
        int connector = text.indexOf(CONNECTOR_OPEN, idx);
        int server = serverStart(text, idx);
        int start = connector < 0 ? server : server < 0 ? connector : Math.min(connector, server);
        if (start < 0) {
          return;
        }
        element = new StringBuilder();
        idx = start;
      }
      int end = text.indexOf('>', idx);
      if (end < 0) {
        element.append(text, idx, text.length()).append(' ');
        return;
      }
      element.append(text, idx, end + 1);
      evaluate(element.toString());
      element = null;
      idx = end + 1;
    }
  }

  /** Index of a {@code <Server} element start at or after {@code from}, or -1. */
  private static int serverStart(String text, int from) {
    int idx = text.indexOf(SERVER_OPEN, from);
    while (idx >= 0) {
      int after = idx + SERVER_OPEN.length();
      if (after == text.length()
          || Character.isWhitespace(text.charAt(after))
          || text.charAt(after) == '>') {
        return idx;
      }
      idx = text.indexOf(SERVER_OPEN, after);
    }
    return -1;
  }

  private void evaluate(String elementText) {
    Map<String, String> attributes = new HashMap<>();
    Matcher m = ATTRIBUTE.matcher(elementText);
    while (m.find()) {
      attributes.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
    }
    Optional<Integer> port = parsePort(attributes.get("port"));
    port.ifPresent(ports::add);
    if (elementText.startsWith(SERVER_OPEN)) {
      return;
    }
    String protocol = attributes.getOrDefault("protocol", "HTTP/1.1").toLowerCase(Locale.ROOT);
    boolean http = protocol.contains("http/1.1") || protocol.contains("http11");
    if (!http || port.isEmpty()) {
      return;
    }
    boolean ssl = "true".equalsIgnoreCase(attributes.getOrDefault("sslenabled", "false"));
    if (ssl) {
      if (sslHttp.isEmpty()) {
        sslHttp = port;
      }
    } else if (plainHttp.isEmpty()) {
      plainHttp = port;
    }
  }

  private static Optional<Integer> parsePort(String value) {
    if (value == null) {
      return Optional.empty();
    }
    try {
      int port = Integer.parseInt(value.trim());
      return port > 0 && port <= 65535 ? Optional.of(port) : Optional.empty();
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }
}
