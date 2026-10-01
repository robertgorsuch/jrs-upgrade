package com.jaspersoft.jrsupgrade.jrs.vendor;

import java.util.Objects;
import java.util.Optional;

/**
 * Where the installed {@code buildomatic/} directory is, or why it could not be settled (ADR-0013).
 * Invariants: a {@link Found} always names an existing directory and says which rule found it; a
 * {@link NotFound} always carries a detail and a remediation an operator can act on; an ambiguous
 * search and an unreachable configured directory are never turned into a guess.
 */
public sealed interface BuildomaticResolution {

  /** Why no directory was settled on. */
  enum Reason {
    /** Neither {@code server.buildomaticDir} nor {@code server.installDir} is set. */
    NOT_CONFIGURED,
    /** {@code server.buildomaticDir} is set but is not a directory this account can see. */
    CONFIGURED_UNREACHABLE,
    /** Nothing matched in any searched place. */
    NOT_FOUND,
    /** More than one installed tree matched, so none is chosen. */
    AMBIGUOUS
  }

  /** The located tree and the rule that found it (e.g. {@code server.buildomaticDir}). */
  record Found(Buildomatic buildomatic, String source) implements BuildomaticResolution {
    public Found {
      Objects.requireNonNull(buildomatic, "buildomatic");
      Objects.requireNonNull(source, "source");
    }
  }

  /** No tree settled on, with the reason and the next action. */
  record NotFound(Reason reason, String detail, String remediation)
      implements BuildomaticResolution {
    public NotFound {
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(detail, "detail");
      Objects.requireNonNull(remediation, "remediation");
    }
  }

  /** The located tree; empty for {@link NotFound}. */
  default Optional<Buildomatic> located() {
    return switch (this) {
      case Found found -> Optional.of(found.buildomatic());
      case NotFound notFound -> Optional.empty();
    };
  }
}
