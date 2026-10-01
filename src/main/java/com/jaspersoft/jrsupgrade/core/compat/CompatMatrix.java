package com.jaspersoft.jrsupgrade.core.compat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.semver4j.Semver;

/**
 * The bundled {@code compat/matrix.yaml} (spec §5.7): which JRS versions, editions, app servers,
 * databases, buildomatic JDKs, Tomcat versions, upgrade paths and capabilities jrs-upgrade
 * supports. Invariants: the matrix is immutable once loaded; version lookups coerce {@code 8.2} to
 * {@code 8.2.0} and match semver ranges, and a version outside every range is reported as absent
 * (or, for {@link #javaRequiredFor}, as {@link UnsupportedVersionException}) rather than guessed;
 * edition, app server, database and mode comparisons are case-insensitive; an entry that lists no
 * Tomcat ranges and a path that lists no modes restrict nothing. The file is unsigned and says so.
 */
public final class CompatMatrix {

  public static final String RESOURCE = "/compat/matrix.yaml";
  private static final String ALL_EDITIONS = "all";
  private static final Set<String> ALL_MODES = Set.of("samedb", "newdb");

  /** One version range of the matrix. */
  public record Entry(
      String range,
      String label,
      Set<String> editions,
      Set<String> appServers,
      Set<Integer> javaForBuildomatic,
      List<String> tomcat,
      Set<String> databases,
      Set<String> commonCapabilities,
      Map<String, Set<String>> editionCapabilities) {

    public Entry {
      Objects.requireNonNull(range, "range");
      Objects.requireNonNull(label, "label");
      editions = Set.copyOf(editions);
      appServers = Set.copyOf(appServers);
      javaForBuildomatic = Collections.unmodifiableSet(new TreeSet<>(javaForBuildomatic));
      tomcat = List.copyOf(tomcat);
      databases = Set.copyOf(databases);
      commonCapabilities = Set.copyOf(commonCapabilities);
      editionCapabilities = Map.copyOf(editionCapabilities);
    }

    /** Capabilities expected for an edition: the common ones plus the edition-specific ones. */
    public Set<String> capabilitiesFor(String edition) {
      Set<String> out = new TreeSet<>(commonCapabilities);
      out.addAll(editionCapabilities.getOrDefault(upper(edition), Set.of()));
      return Set.copyOf(out);
    }
  }

  /**
   * A supported upgrade: any version in {@code from} to any strictly newer version in {@code to},
   * in any of {@code modes} ({@code samedb}, {@code newdb}; empty allows both).
   */
  public record UpgradePath(String from, String to, Set<String> modes) {
    public UpgradePath {
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
      modes = Set.copyOf(modes);
    }

    public UpgradePath(String from, String to) {
      this(from, to, Set.of());
    }

    /** True when the path offers {@code mode}, or restricts nothing. */
    public boolean offers(String mode) {
      return modes.isEmpty() || modes.contains(lower(mode));
    }
  }

  private final int matrixVersion;
  private final boolean signed;
  private final List<Entry> entries;
  private final List<UpgradePath> upgradePaths;

  public CompatMatrix(
      int matrixVersion, boolean signed, List<Entry> entries, List<UpgradePath> upgradePaths) {
    this.matrixVersion = matrixVersion;
    this.signed = signed;
    this.entries = List.copyOf(entries);
    this.upgradePaths = List.copyOf(upgradePaths);
  }

  /** Loads the bundled matrix; a missing or malformed resource is a packaging error. */
  public static CompatMatrix load() {
    try (InputStream in = CompatMatrix.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(RESOURCE + " is missing from the jar");
      }
      return load(in);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + RESOURCE, e);
    }
  }

  /** Parses a matrix document from a stream (tests). */
  public static CompatMatrix load(InputStream in) throws IOException {
    YAMLMapper mapper =
        YAMLMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // matrix version 1 wrote one Java major; a scalar still reads as a one-element list
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .build();
    MatrixFile file = mapper.readValue(in, MatrixFile.class);
    List<Entry> entries =
        file.entries().stream()
            .map(
                e -> {
                  Map<String, List<String>> caps =
                      e.expectedCapabilities() == null ? Map.of() : e.expectedCapabilities();
                  Set<String> common =
                      new LinkedHashSet<>(caps.getOrDefault(ALL_EDITIONS, List.of()));
                  Map<String, Set<String>> perEdition = new java.util.LinkedHashMap<>();
                  for (Map.Entry<String, List<String>> c : caps.entrySet()) {
                    if (!c.getKey().equals(ALL_EDITIONS)) {
                      perEdition.put(upper(c.getKey()), Set.copyOf(c.getValue()));
                    }
                  }
                  return new Entry(
                      e.range(),
                      e.label() == null ? e.range() : e.label(),
                      uppers(e.editions()),
                      lowers(e.appServers()),
                      e.javaForBuildomatic() == null
                          ? Set.of()
                          : Set.copyOf(e.javaForBuildomatic()),
                      e.tomcat() == null ? List.of() : e.tomcat(),
                      lowers(e.databases()),
                      common,
                      perEdition);
                })
            .toList();
    List<UpgradePath> paths =
        file.upgradePaths().stream()
            .map(p -> new UpgradePath(p.from(), p.to(), lowers(p.modes())))
            .toList();
    return new CompatMatrix(file.matrixVersion(), file.signed(), entries, paths);
  }

  public int matrixVersion() {
    return matrixVersion;
  }

  public boolean signed() {
    return signed;
  }

  public List<Entry> entries() {
    return entries;
  }

  public List<UpgradePath> upgradePaths() {
    return upgradePaths;
  }

  /** The entry whose range contains {@code version}, or empty when unsupported or unparsable. */
  public Optional<Entry> find(String version) {
    return parse(version)
        .flatMap(v -> entries.stream().filter(e -> v.satisfies(e.range())).findFirst());
  }

  /** True when every one of version, edition, app server and database is listed together. */
  public boolean supports(String version, String edition, String appServer, String database) {
    return find(version)
        .filter(e -> e.editions().contains(upper(edition)))
        .filter(e -> e.appServers().contains(lower(appServer)))
        .filter(e -> e.databases().contains(lower(database)))
        .isPresent();
  }

  /** The Java feature versions buildomatic may run on for {@code version}; never empty. */
  public Set<Integer> javaRequiredFor(String version) {
    return find(version)
        .map(Entry::javaForBuildomatic)
        .orElseThrow(() -> new UnsupportedVersionException(version));
  }

  /** "Java 17" or "Java 8, 11 or 17", for messages. */
  public static String describeJava(Set<Integer> majors) {
    List<String> sorted = new TreeSet<>(majors).stream().map(String::valueOf).toList();
    if (sorted.size() <= 1) {
      return "Java " + String.join("", sorted);
    }
    return "Java "
        + String.join(", ", sorted.subList(0, sorted.size() - 1))
        + " or "
        + sorted.get(sorted.size() - 1);
  }

  /** True when some listed path covers {@code from -> to} in any mode and {@code to} is newer. */
  public boolean upgradePathSupported(String from, String to) {
    return paths(from, to).findAny().isPresent();
  }

  /** As above, for one mode ({@code samedb} or {@code newdb}, case-insensitive). */
  public boolean upgradePathSupported(String from, String to, String mode) {
    return paths(from, to).anyMatch(p -> p.offers(mode));
  }

  /**
   * The modes some listed path offers for {@code from -> to}; both when a path restricts nothing.
   */
  public Set<String> upgradeModes(String from, String to) {
    Set<String> out = new TreeSet<>();
    paths(from, to).forEach(p -> out.addAll(p.modes().isEmpty() ? ALL_MODES : p.modes()));
    return Collections.unmodifiableSet(out);
  }

  private java.util.stream.Stream<UpgradePath> paths(String from, String to) {
    Optional<Semver> f = parse(from);
    Optional<Semver> t = parse(to);
    if (f.isEmpty() || t.isEmpty() || !t.get().isGreaterThan(f.get())) {
      return java.util.stream.Stream.empty();
    }
    return upgradePaths.stream()
        .filter(p -> f.get().satisfies(p.from()) && t.get().satisfies(p.to()));
  }

  /**
   * True when the entry for {@code jrsVersion} lists a Tomcat range holding {@code tomcatVersion},
   * or lists none; false for an unknown server version or an unparsable Tomcat version.
   */
  public boolean tomcatSupported(String jrsVersion, String tomcatVersion) {
    Optional<Entry> entry = find(jrsVersion);
    Optional<Semver> tomcat = parse(tomcatVersion);
    if (entry.isEmpty() || tomcat.isEmpty()) {
      return false;
    }
    List<String> ranges = entry.get().tomcat();
    return ranges.isEmpty() || ranges.stream().anyMatch(r -> tomcat.get().satisfies(r));
  }

  /** Capabilities the adapter should find on {@code version}/{@code edition}; empty if unknown. */
  public Set<String> expectedCapabilities(String version, String edition) {
    return find(version).map(e -> e.capabilitiesFor(edition)).orElse(Set.of());
  }

  private static Optional<Semver> parse(String version) {
    if (version == null || version.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(Semver.coerce(version.strip()));
  }

  private static String upper(String s) {
    return s == null ? "" : s.strip().toUpperCase(Locale.ROOT);
  }

  private static String lower(String s) {
    return s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
  }

  private static Set<String> uppers(List<String> values) {
    Set<String> out = new LinkedHashSet<>();
    for (String v : values == null ? List.<String>of() : values) {
      out.add(upper(v));
    }
    return out;
  }

  private static Set<String> lowers(List<String> values) {
    Set<String> out = new LinkedHashSet<>();
    for (String v : values == null ? List.<String>of() : values) {
      out.add(lower(v));
    }
    return out;
  }

  /** YAML shape of the file; Jackson binds these records directly. */
  record MatrixFile(
      int matrixVersion, boolean signed, List<EntryYaml> entries, List<PathYaml> upgradePaths) {
    MatrixFile {
      entries = entries == null ? List.of() : entries;
      upgradePaths = upgradePaths == null ? List.of() : upgradePaths;
    }
  }

  record EntryYaml(
      String range,
      String label,
      List<String> editions,
      List<String> appServers,
      List<Integer> javaForBuildomatic,
      List<String> tomcat,
      List<String> databases,
      Map<String, List<String>> expectedCapabilities) {}

  record PathYaml(String from, String to, List<String> modes) {}
}
