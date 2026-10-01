package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.ops.db.DbObject;
import com.jaspersoft.jrsupgrade.ops.db.JdbcSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Issue #3: {@code js-upgrade-newdb} drops and recreates the repository database, and customer
 * tables that live in it (NGRA's {@code report_log}, {@code schedule_log} and {@code
 * report_log_seq}) vanish with it. This tells them apart from the vendor's own. Invariants: the
 * vendor's names are read from the DDL of the installed buildomatic, the version the database was
 * created by, under {@code install_resources/sql/<db>/} ({@code *.ddl} and {@code *.sql}, upgrade
 * scripts included, so a table a later script adds is the vendor's too); names compare
 * case-insensitively and without schema or quotes; with no DDL to read, nothing is called foreign,
 * since guessing would name every vendor table; read-only.
 */
final class ForeignObjects {

  private static final Pattern CREATE =
      Pattern.compile(
          "(?i)\\bcreate\\s+(?:global\\s+temporary\\s+)?(table|sequence)\\s+"
              + "(?:if\\s+not\\s+exists\\s+)?([\\w.\"`\\[\\]]+)");

  private ForeignObjects() {}

  /** The vendor DDL directory for {@code type} under the installed buildomatic. */
  static Path ddlDir(Path installedBuildomatic, Config.DatabaseType type) {
    return installedBuildomatic
        .resolve("install_resources")
        .resolve("sql")
        .resolve(JdbcSettings.buildomaticDir(type));
  }

  /**
   * The table and sequence names the vendor's DDL creates, lower case and unqualified; empty when
   * the directory holds no DDL to read.
   */
  static Optional<Set<String>> vendorNames(Path ddlDir) throws IOException {
    if (!Files.isDirectory(ddlDir)) {
      return Optional.empty();
    }
    Set<String> names = new TreeSet<>();
    boolean read = false;
    try (Stream<Path> walk = Files.walk(ddlDir)) {
      for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".ddl") && !name.endsWith(".sql")) {
          continue;
        }
        read = true;
        String text = Files.readString(file, StandardCharsets.ISO_8859_1);
        Matcher m = CREATE.matcher(text);
        while (m.find()) {
          names.add(bare(m.group(2)));
        }
      }
    }
    return read ? Optional.of(names) : Optional.empty();
  }

  /** The objects of {@code schema} the vendor's DDL does not create, in the order given. */
  static List<DbObject> foreign(List<DbObject> schema, Set<String> vendor) {
    return schema.stream().filter(o -> !vendor.contains(bare(o.name()))).toList();
  }

  /** "table report_log, sequence report_log_seq". */
  static String describe(List<DbObject> objects) {
    return String.join(", ", objects.stream().map(DbObject::toString).toList());
  }

  /** {@code "public"."Report_Log"} -> {@code report_log}. */
  static String bare(String name) {
    String unquoted = name.replaceAll("[\"`\\[\\]]", "");
    int dot = unquoted.lastIndexOf('.');
    return (dot < 0 ? unquoted : unquoted.substring(dot + 1)).toLowerCase(Locale.ROOT);
  }
}
