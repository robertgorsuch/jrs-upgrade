package com.jaspersoft.jrsupgrade.core.selfcheck;

import com.jaspersoft.jrsupgrade.core.Version;
import com.jaspersoft.jrsupgrade.core.platform.NativeTempDir;
import com.jaspersoft.jrsupgrade.core.platform.Platforms;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Verifies the tool itself (spec §12.3): runtime version, presence of bundled resources, and, in
 * later phases, the key ring and state schema. Invariant: {@link #run()} never throws; every
 * problem is reported as a {@link Item} with {@link Status#FAIL} so the operator sees all findings
 * at once.
 */
public final class SelfCheck {

  /** Outcome of one check. */
  public enum Status {
    PASS,
    WARN,
    FAIL
  }

  /** One line of the self-check report. */
  public record Item(String name, Status status, String detail) {}

  /** Complete report; {@code ok()} is true when no item failed. */
  public record Report(List<Item> items) {
    public boolean ok() {
      return items.stream().noneMatch(i -> i.status() == Status.FAIL);
    }
  }

  /** Name of the item that reports whether this host is the pair ADR-0002 supports. */
  public static final String PLATFORM = "platform";

  /** Name of the item that reports where the SQLite native library is unpacked and run. */
  public static final String NATIVE_TEMP = "native-temp";

  private static final int REQUIRED_JAVA = 21;

  private final List<Check> checks = new ArrayList<>();

  /** A pluggable check; later phases register key-ring and state-store checks here. */
  @FunctionalInterface
  public interface Check {
    Item run();
  }

  public SelfCheck() {
    checks.add(SelfCheck::runtimeVersion);
    checks.add(SelfCheck::versionResource);
    checks.add(SelfCheck::configSchemaResource);
    checks.add(
        () -> platform(System.getProperty("os.name", ""), System.getProperty("os.arch", "")));
    checks.add(() -> nativeTemp(NativeTempDir.current()));
  }

  public SelfCheck add(Check check) {
    checks.add(check);
    return this;
  }

  public Report run() {
    List<Item> items = new ArrayList<>();
    for (Check c : checks) {
      Item item;
      try {
        item = c.run();
      } catch (RuntimeException e) {
        item = new Item("check", Status.FAIL, "threw " + e.getClass().getSimpleName() + ": " + e);
      }
      items.add(item);
    }
    return new Report(List.copyOf(items));
  }

  /**
   * Reports the host operating system and architecture, failing when they are outside ADR-0002 so a
   * macOS, BSD or ARM64 machine is named rather than silently treated as Linux (review 3.1).
   */
  public static Item platform(String osName, String osArch) {
    Optional<String> refusal = Platforms.unsupportedReason(osName, osArch);
    return refusal
        .map(r -> new Item(PLATFORM, Status.FAIL, r))
        .orElseGet(() -> new Item(PLATFORM, Status.PASS, osName + " " + osArch));
  }

  /**
   * Where the SQLite driver unpacks its native library, failing when that directory is on a {@code
   * noexec} mount, which turns every stateful command into an {@code UnsatisfiedLinkError} (review
   * 3.4).
   */
  public static Item nativeTemp(java.nio.file.Path dir) {
    Optional<String> noexec = NativeTempDir.noexecReason(dir);
    return noexec
        .map(
            r ->
                new Item(
                    NATIVE_TEMP,
                    Status.FAIL,
                    r + "; set -Dorg.sqlite.tmpdir to a directory that allows execution"))
        .orElseGet(() -> new Item(NATIVE_TEMP, Status.PASS, dir.toString()));
  }

  private static Item runtimeVersion() {
    int feature = Runtime.version().feature();
    String detail = "Java " + Runtime.version() + " (" + System.getProperty("java.vendor") + ")";
    return feature >= REQUIRED_JAVA
        ? new Item("runtime", Status.PASS, detail)
        : new Item(
            "runtime", Status.FAIL, detail + "; requires Java " + REQUIRED_JAVA + " or later");
  }

  private static Item versionResource() {
    String v = Version.current().version();
    return v.equals("0.0.0-dev")
        ? new Item("version", Status.WARN, "version resource missing; running from an IDE?")
        : new Item("version", Status.PASS, Version.current().banner());
  }

  private static Item configSchemaResource() {
    boolean present = SelfCheck.class.getResource("/schema/config.schema.json") != null;
    return present
        ? new Item("config-schema", Status.PASS, "schema/config.schema.json bundled")
        : new Item("config-schema", Status.FAIL, "schema/config.schema.json missing from the jar");
  }
}
