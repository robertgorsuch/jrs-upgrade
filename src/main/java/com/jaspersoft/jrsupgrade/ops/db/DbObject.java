package com.jaspersoft.jrsupgrade.ops.db;

import java.util.Locale;
import java.util.Objects;

/**
 * A table or sequence in the schema a {@link JdbcConnector.Session} is connected to, as the
 * driver's metadata names it. Invariant: {@code name} is never blank; {@link #key()} is the
 * case-insensitive form names are compared by, because databases fold unquoted names differently.
 */
public record DbObject(Kind kind, String name) {

  /** What the object is. */
  public enum Kind {
    TABLE,
    SEQUENCE
  }

  public DbObject {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(name, "name");
    if (name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
  }

  public String key() {
    return name.toLowerCase(Locale.ROOT);
  }

  @Override
  public String toString() {
    return kind.name().toLowerCase(Locale.ROOT) + " " + name;
  }
}
