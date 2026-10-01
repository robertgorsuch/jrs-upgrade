package com.jaspersoft.jrsupgrade.jrs.vendor;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A vendor JDBC driver directory ({@code buildomatic/conf_source/db/<type>/jdbc}, spec §19
 * assumption 6). Invariant: {@code jars} lists only {@code *.jar} files that existed when the
 * directory was inspected, sorted by name.
 */
public record JdbcDriverDir(String dbType, Path dir, List<Path> jars) {

  public JdbcDriverDir {
    Objects.requireNonNull(dbType, "dbType");
    Objects.requireNonNull(dir, "dir");
    jars = List.copyOf(jars);
  }
}
