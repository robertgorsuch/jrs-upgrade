package com.jaspersoft.jrsupgrade.core.compat;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.semver4j.Semver;

/**
 * What changes between release lines that a customization has to follow, as data in the matrix
 * (ADR-0003): named verdicts for third-party jars (issue #4), files and mechanisms that moved
 * (issue #6) and constructs inside override files the target no longer accepts (issue #8).
 * Invariants: every rule applies to an upgrade whose source version satisfies {@code from} and
 * whose target satisfies {@code to} (an absent range matches every version, an unparsable version
 * matches no range); every rule names its {@code source}, the document it was taken from; paths are
 * globs relative to the webapp with {@code /} separators, where {@code *} stays within one
 * directory and {@code **} crosses them; patterns in construct rules are Java regular expressions
 * matched against the whole value; the lists are immutable.
 */
public record UpgradeRules(
    List<JarRule> jarRules, List<Relocation> relocations, List<ConstructRule> constructs) {

  public static final UpgradeRules NONE = new UpgradeRules(List.of(), List.of(), List.of());

  public UpgradeRules {
    jarRules = List.copyOf(jarRules);
    relocations = List.copyOf(relocations);
    constructs = List.copyOf(constructs);
  }

  /** The source and target ranges a rule applies between. */
  public record Crossing(Optional<String> from, Optional<String> to) {
    public Crossing {
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
    }

    public boolean applies(String source, String target) {
      return within(source, from) && within(target, to);
    }

    private static boolean within(String version, Optional<String> range) {
      if (range.isEmpty()) {
        return true;
      }
      Semver v = version == null ? null : Semver.coerce(version.strip());
      return v != null && v.satisfies(range.get());
    }
  }

  /** What a {@link JarRule} says to do with a matching jar. */
  public enum JarVerdict {
    DROP,
    KEEP,
    REPLACE
  }

  /** A named verdict for jars whose file name matches {@code match} (issue #4). */
  public record JarRule(
      String id,
      Crossing when,
      String match,
      JarVerdict verdict,
      Optional<String> replacement,
      String note,
      String source) {
    public JarRule {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(when, "when");
      Objects.requireNonNull(match, "match");
      Objects.requireNonNull(verdict, "verdict");
      Objects.requireNonNull(replacement, "replacement");
      Objects.requireNonNull(note, "note");
      Objects.requireNonNull(source, "source");
    }

    public boolean matches(String jarFileName) {
      return glob(match, jarFileName);
    }
  }

  /** How a {@link Relocation} moved. */
  public enum RelocationKind {
    /** The file is gone from the target. */
    REMOVED,
    /** The file lives elsewhere in the target, at {@code newPath}. */
    MOVED,
    /** The setting is made another way: {@code newKey} or the file at {@code newPath}. */
    MECHANISM
  }

  /** A file or setting that moved between release lines (issue #6). */
  public record Relocation(
      String id,
      Crossing when,
      RelocationKind kind,
      String path,
      Optional<String> newPath,
      Optional<String> newKey,
      String hint,
      String source) {
    public Relocation {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(when, "when");
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(newPath, "newPath");
      Objects.requireNonNull(newKey, "newKey");
      Objects.requireNonNull(hint, "hint");
      Objects.requireNonNull(source, "source");
    }

    public boolean matches(String relativePath) {
      return glob(path, relativePath);
    }

    /** "removed in the target: <hint> (source)". */
    public String describe() {
      String where =
          switch (kind) {
            case REMOVED -> "removed in the target";
            case MOVED -> "moved to " + newPath.orElse("(see hint)");
            case MECHANISM ->
                "now set through "
                    + newKey.or(() -> newPath).orElse("another mechanism (see hint)");
          };
      return where + ": " + hint + " (" + source + ")";
    }
  }

  /** An element and the attributes it must carry, for {@link ConstructRule#within}. */
  public record ElementMatch(String element, Map<String, String> attributes) {
    public ElementMatch {
      Objects.requireNonNull(element, "element");
      attributes = Map.copyOf(attributes);
    }

    public boolean matches(String localName, Map<String, String> actual) {
      if (!Pattern.matches(element, localName)) {
        return false;
      }
      for (Map.Entry<String, String> e : attributes.entrySet()) {
        String value = actual.get(e.getKey());
        if (value == null
            || !Pattern.compile(e.getValue(), Pattern.DOTALL).matcher(value).matches()) {
          return false;
        }
      }
      return true;
    }
  }

  /**
   * A construct inside a file matching one of {@code files} that the target handles differently
   * (issue #8): an XML element (local name, attributes, optionally its text and an ancestor), or a
   * properties key (optionally its value).
   */
  public record ConstructRule(
      String id,
      Crossing when,
      List<String> files,
      Optional<ElementMatch> element,
      Optional<String> text,
      Optional<ElementMatch> within,
      Optional<String> key,
      Optional<String> value,
      String hint,
      String source) {
    public ConstructRule {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(when, "when");
      files = List.copyOf(files);
      Objects.requireNonNull(element, "element");
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(within, "within");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(value, "value");
      Objects.requireNonNull(hint, "hint");
      Objects.requireNonNull(source, "source");
      if (element.isPresent() == key.isPresent()) {
        throw new IllegalArgumentException(
            "construct rule " + id + " needs exactly one of element and key");
      }
    }

    public boolean appliesTo(String relativePath) {
      return files.stream().anyMatch(g -> glob(g, relativePath));
    }

    public boolean xml() {
      return element.isPresent();
    }
  }

  public List<JarRule> jarRules(String source, String target) {
    return jarRules.stream().filter(r -> r.when().applies(source, target)).toList();
  }

  public List<Relocation> relocations(String source, String target) {
    return relocations.stream().filter(r -> r.when().applies(source, target)).toList();
  }

  public List<ConstructRule> constructs(String source, String target) {
    return constructs.stream().filter(r -> r.when().applies(source, target)).toList();
  }

  /** {@code *} within one directory, {@code **} across them, {@code ?} one character. */
  public static boolean glob(String glob, String path) {
    StringBuilder re = new StringBuilder();
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
        re.append(".*");
        i++;
      } else if (c == '*') {
        re.append("[^/]*");
      } else if (c == '?') {
        re.append("[^/]");
      } else {
        re.append(Pattern.quote(String.valueOf(c)));
      }
    }
    return Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE)
        .matcher(path.replace('\\', '/'))
        .matches();
  }
}
