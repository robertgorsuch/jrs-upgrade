package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.jrs.vendor.MasterProperties;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.semver4j.Semver;

/**
 * Two rules of the vendor upgrade guides about {@code default_master.properties} that the compat
 * matrix cannot express (review §1.3). Compact and Split installations never cross in one upgrade:
 * "if the installType=split property is not configured, the upgrade will be compact" (upgrade guide
 * 10.1 p.14), and Compact to Split is its own procedure, {@code js-migrate-to-split-*} (pp.64-65).
 * Oracle needs {@code dbVersion} from 10.1 on (upgrade guide 10.1 pp.43, 56; installation guide
 * 10.1 p.263). Both rules are judged on what {@code write-master-properties} will stage: the
 * installed file's keys (minus passwords) written after the target package's own file, so an
 * installed key wins and a target-only key survives. Invariants: {@code installed} and {@code
 * target} are never mutated; a password key is never part of a finding; the sentences name the side
 * that has to change and are empty exactly when the staged file would honour the rule.
 */
final class MasterInvariants {

  static final String INSTALL_TYPE = "installType";
  static final String COMPACT = "compact";
  static final String SPLIT = "split";
  static final String AUDIT_PREFIX = "audit.";
  static final String DB_TYPE = "dbType";
  static final String ORACLE = "oracle";
  static final String DB_VERSION = "dbVersion";

  /** First release whose guides list {@code dbVersion} among the Oracle settings. */
  static final Semver DB_VERSION_SINCE = new Semver("10.1.0");

  private MasterInvariants() {}

  /** The installation type {@code master} declares: {@code compact} unless it says otherwise. */
  static String installType(Map<String, String> master) {
    String value = master.get(INSTALL_TYPE);
    if (value == null || value.isBlank()) {
      return COMPACT;
    }
    return value.strip().toLowerCase(Locale.ROOT);
  }

  /**
   * Empty when the staged file keeps the installed {@code installType} and adds no {@code audit.*}
   * key the installation does not have; otherwise the sentence naming what the target package's
   * {@code default_master.properties} would change.
   */
  static Optional<String> installTypeProblem(
      Map<String, String> installed, Map<String, String> target) {
    String current = installType(installed);
    String staged = installed.containsKey(INSTALL_TYPE) ? current : installType(target);
    if (!staged.equals(current)) {
      return Optional.of(
          "the installed server is a "
              + current
              + " installation but the target default_master.properties says installType="
              + staged
              + "; the vendor upgrade never converts one into the other (js-migrate-to-split-*"
              + " is a separate procedure)");
    }
    TreeSet<String> added = new TreeSet<>();
    for (String key : target.keySet()) {
      if (key.startsWith(AUDIT_PREFIX)
          && !installed.containsKey(key)
          && !MasterProperties.isPasswordKey(key)) {
        added.add(key);
      }
    }
    if (!added.isEmpty()) {
      return Optional.of(
          "the target default_master.properties sets "
              + String.join(", ", added)
              + " which the installed one does not; the audit settings must be identical on both"
              + " sides of an upgrade");
    }
    return Optional.empty();
  }

  /**
   * Empty unless the staged file names Oracle, the target release is 10.1 or later and no {@code
   * dbVersion} is set on either side.
   */
  static Optional<String> dbVersionProblem(
      Map<String, String> installed, Map<String, String> target, String toVersion) {
    String dbType = effective(installed, target, DB_TYPE).orElse("");
    if (!dbType.strip().equalsIgnoreCase(ORACLE)) {
      return Optional.empty();
    }
    Semver to = Semver.coerce(toVersion.strip());
    if (to == null || to.isLowerThan(DB_VERSION_SINCE)) {
      return Optional.empty();
    }
    if (effective(installed, target, DB_VERSION).filter(v -> !v.isBlank()).isPresent()) {
      return Optional.empty();
    }
    List<String> examples = List.of("12", "19c", "21c", "23ai");
    return Optional.of(
        "dbType=oracle and JasperReports Server "
            + toVersion
            + " needs dbVersion in default_master.properties (for example "
            + String.join(", ", examples)
            + ") before the vendor upgrade runs");
  }

  /** The value the staged file would carry for {@code key}: installed first, then target. */
  private static Optional<String> effective(
      Map<String, String> installed, Map<String, String> target, String key) {
    List<Map<String, String>> sides = List.of(installed, target);
    for (Map<String, String> side : sides) {
      String value = side.get(key);
      if (value != null) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }
}
