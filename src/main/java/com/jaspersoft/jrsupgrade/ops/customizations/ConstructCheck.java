package com.jaspersoft.jrsupgrade.ops.customizations;

import com.jaspersoft.jrsupgrade.core.compat.UpgradeRules;
import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.ConstructFinding;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Issue #8: constructs inside override Spring XML, {@code web.xml} and the CSRFGuard properties
 * that the target handles differently, found with the matrix's construct rules. Invariants:
 * read-only; XML is parsed with SAX, namespace-aware, with no external entity, DTD or XInclude
 * resolved, so a file can name an old DTD without the parser fetching it; elements and attributes
 * match by local name; a rule's {@code within} matches any ancestor; a hit names the file, the line
 * and the construct; a file that does not parse is one finding saying so, never a failure.
 */
final class ConstructCheck {

  static final String UNPARSABLE = "unparsable";

  private ConstructCheck() {}

  static List<ConstructFinding> check(
      String relativePath, byte[] content, List<UpgradeRules.ConstructRule> rules) {
    List<UpgradeRules.ConstructRule> xml = new ArrayList<>();
    List<UpgradeRules.ConstructRule> properties = new ArrayList<>();
    for (UpgradeRules.ConstructRule r : rules) {
      if (r.appliesTo(relativePath)) {
        (r.xml() ? xml : properties).add(r);
      }
    }
    List<ConstructFinding> out = new ArrayList<>();
    if (!xml.isEmpty()) {
      try {
        out.addAll(xml(relativePath, content, xml));
      } catch (IOException | SAXException | ParserConfigurationException e) {
        out.add(
            new ConstructFinding(
                relativePath,
                UNPARSABLE,
                0,
                "(the whole file)",
                "cannot be parsed as XML (" + e.getMessage() + "); check it by hand",
                "jrs-upgrade"));
      }
    }
    if (!properties.isEmpty()) {
      out.addAll(properties(relativePath, content, properties));
    }
    return out;
  }

  private static List<ConstructFinding> properties(
      String relativePath, byte[] content, List<UpgradeRules.ConstructRule> rules) {
    Properties p = new Properties();
    try {
      p.load(new InputStreamReader(new ByteArrayInputStream(content), StandardCharsets.ISO_8859_1));
    } catch (IOException | IllegalArgumentException e) {
      return List.of(
          new ConstructFinding(
              relativePath,
              UNPARSABLE,
              0,
              "(the whole file)",
              "cannot be read as properties (" + e.getMessage() + "); check it by hand",
              "jrs-upgrade"));
    }
    List<ConstructFinding> out = new ArrayList<>();
    for (UpgradeRules.ConstructRule r : rules) {
      Pattern key = Pattern.compile(r.key().orElseThrow());
      for (String k : new TreeSet<>(p.stringPropertyNames())) {
        String v = p.getProperty(k);
        if (key.matcher(k).matches()
            && r.value().map(re -> Pattern.compile(re).matcher(v).matches()).orElse(true)) {
          out.add(
              new ConstructFinding(
                  relativePath, r.id(), lineOf(content, k), k + "=" + v, r.hint(), r.source()));
        }
      }
    }
    return out;
  }

  /** The 1-based line a properties key starts on, or 0 when it cannot be told. */
  private static int lineOf(byte[] content, String key) {
    String[] lines = new String(content, StandardCharsets.ISO_8859_1).split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      String t = lines[i].strip();
      if (t.startsWith(key)
          && (t.length() == key.length() || "=: \t".indexOf(t.charAt(key.length())) >= 0)) {
        return i + 1;
      }
    }
    return 0;
  }

  private static List<ConstructFinding> xml(
      String relativePath, byte[] content, List<UpgradeRules.ConstructRule> rules)
      throws IOException, SAXException, ParserConfigurationException {
    SAXParserFactory factory = SAXParserFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setXIncludeAware(false);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    SAXParser parser = factory.newSAXParser();
    Handler handler = new Handler(relativePath, rules);
    InputSource source = new InputSource(new ByteArrayInputStream(content));
    parser.parse(source, handler);
    return handler.out;
  }

  private record Open(String name, Map<String, String> attributes, int line) {}

  private static final class Handler extends DefaultHandler {
    final String path;
    final List<UpgradeRules.ConstructRule> rules;
    final List<ConstructFinding> out = new ArrayList<>();
    final Deque<Open> stack = new ArrayDeque<>();
    final Deque<StringBuilder> text = new ArrayDeque<>();
    Locator locator;

    Handler(String path, List<UpgradeRules.ConstructRule> rules) {
      this.path = path;
      this.rules = rules;
    }

    @Override
    public void setDocumentLocator(Locator locator) {
      this.locator = locator;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) {
      Map<String, String> attributes = new LinkedHashMap<>();
      for (int i = 0; i < atts.getLength(); i++) {
        String name = atts.getLocalName(i);
        attributes.put(name == null || name.isEmpty() ? atts.getQName(i) : name, atts.getValue(i));
      }
      String name = localName == null || localName.isEmpty() ? qName : localName;
      Open open = new Open(name, attributes, locator == null ? 0 : locator.getLineNumber());
      for (UpgradeRules.ConstructRule r : rules) {
        if (r.text().isEmpty() && matches(r, open)) {
          hit(r, open, "");
        }
      }
      stack.push(open);
      text.push(new StringBuilder());
    }

    @Override
    public void characters(char[] ch, int start, int length) {
      if (!text.isEmpty()) {
        text.peek().append(ch, start, length);
      }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
      Open open = stack.pop();
      String body = text.pop().toString();
      for (UpgradeRules.ConstructRule r : rules) {
        if (r.text().isPresent()
            && matches(r, open)
            && Pattern.compile(r.text().get(), Pattern.DOTALL).matcher(body).matches()) {
          hit(r, open, body.strip());
        }
      }
    }

    private boolean matches(UpgradeRules.ConstructRule r, Open open) {
      if (!r.element().orElseThrow().matches(open.name(), open.attributes())) {
        return false;
      }
      return r.within().isEmpty()
          || stack.stream().anyMatch(a -> r.within().get().matches(a.name(), a.attributes()));
    }

    private void hit(UpgradeRules.ConstructRule r, Open open, String body) {
      StringBuilder construct = new StringBuilder("<").append(open.name());
      r.element()
          .orElseThrow()
          .attributes()
          .keySet()
          .forEach(
              a ->
                  construct
                      .append(' ')
                      .append(a)
                      .append("=\"")
                      .append(open.attributes().getOrDefault(a, ""))
                      .append('"'));
      construct.append('>');
      if (!body.isEmpty()) {
        construct.append(body).append("</").append(open.name()).append('>');
      }
      r.within()
          .ifPresent(
              w ->
                  construct
                      .append(" inside <")
                      .append(w.element())
                      .append(
                          w.attributes().entrySet().stream()
                              .map(e -> " " + e.getKey() + "=\"" + e.getValue() + "\"")
                              .reduce("", String::concat))
                      .append('>'));
      out.add(
          new ConstructFinding(
              path, r.id(), open.line(), construct.toString(), r.hint(), r.source()));
    }
  }
}
