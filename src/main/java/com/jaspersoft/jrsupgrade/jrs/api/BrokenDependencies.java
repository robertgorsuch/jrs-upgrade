package com.jaspersoft.jrsupgrade.jrs.api;

import java.util.Locale;

/**
 * What an import does with a resource whose dependency is missing from the catalog and from the
 * server (REST reference 10.1 p.116, {@code brokenDependencies}; js-import {@code
 * --broken-dependencies}). Invariants: {@link #FAIL} is the server default and is never sent, so
 * older servers see the request they always saw; {@link #wire()} is the REST spelling and {@link
 * #vendor()} the js-import spelling, which differ only for {@link #FAIL} ({@code cancel}).
 */
public enum BrokenDependencies {
  /** Stop before importing anything; the server parks the task in phase {@code pending}. */
  FAIL,
  /** Import everything except the resources with a missing dependency. */
  SKIP,
  /** Import the resources anyway and leave the dependency broken. */
  INCLUDE;

  /** The {@code brokenDependencies} query value of {@code POST /rest_v2/import}. */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The value js-import's {@code --broken-dependencies} takes. */
  public String vendor() {
    return this == FAIL ? "cancel" : wire();
  }

  /** Parses either spelling, case-insensitively; {@code cancel} is {@link #FAIL}. */
  public static BrokenDependencies parse(String text) {
    String t = text.strip().toLowerCase(Locale.ROOT);
    return switch (t) {
      case "fail", "cancel" -> FAIL;
      case "skip" -> SKIP;
      case "include" -> INCLUDE;
      default ->
          throw new IllegalArgumentException(
              "broken-dependencies must be fail, skip or include, not '" + text + "'");
    };
  }
}
