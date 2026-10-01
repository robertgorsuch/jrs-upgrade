package com.jaspersoft.jrsupgrade.ops;

import com.jaspersoft.jrsupgrade.jrs.api.ExportImportStrategy;
import java.util.Locale;
import java.util.Optional;

/**
 * The {@code --strategy rest|buildomatic|vendor} flag shared by {@code export} and {@code import}.
 * Invariants: {@code buildomatic} and {@code vendor} name the same strategy, the buildomatic tools
 * {@code js-export} and {@code js-import} (field test 3: the guided menu says buildomatic); a
 * stored or rendered strategy is still spelled {@code vendor}, so arguments stored by earlier
 * builds rebuild unchanged; anything else is a usage error (exit 1) raised before any bootstrap.
 */
public final class StrategyFlag {

  public static final String REST = "rest";
  public static final String VENDOR = "vendor";

  /** The name the guided menu uses for {@link #VENDOR}. */
  public static final String BUILDOMATIC = "buildomatic";

  private StrategyFlag() {}

  /** Empty for a null or blank flag (automatic selection). */
  public static Optional<ExportImportStrategy.Kind> parse(String value) {
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return switch (value.strip().toLowerCase(Locale.ROOT)) {
      case REST -> Optional.of(ExportImportStrategy.Kind.REST);
      case VENDOR, BUILDOMATIC -> Optional.of(ExportImportStrategy.Kind.VENDOR_CLI);
      default ->
          throw new IllegalArgumentException(
              "--strategy must be '"
                  + REST
                  + "' or '"
                  + BUILDOMATIC
                  + "' (also spelled '"
                  + VENDOR
                  + "'), not '"
                  + value
                  + "'");
    };
  }

  /** The flag spelling of a kind, for stored arguments and JSON. */
  public static String render(ExportImportStrategy.Kind kind) {
    return switch (kind) {
      case REST -> REST;
      case VENDOR_CLI -> VENDOR;
    };
  }
}
