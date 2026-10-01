package com.jaspersoft.jrsupgrade.core.state;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A row of {@code hotfixes_installed} (spec §5.4, §8.4). Invariant: {@code id} is the manifest id
 * and the primary key, so a hotfix can be installed at most once per state store. {@code origin}
 * says who put the files in place (ADR-0030, issue #99): {@link Origin#JRSCTL} rows own their files
 * and a snapshot, so they can be rolled back; {@link Origin#RECORDED} rows were applied outside
 * jrs-upgrade and merely recorded from the package's readme, own no files, and cannot be rolled
 * back.
 */
public record HotfixInstalled(
    String id,
    String version,
    String title,
    String installedRunId,
    Optional<String> snapshotRef,
    HotfixState state,
    Instant installedAt,
    Origin origin) {

  /** Who applied the hotfix's files. */
  public enum Origin {
    /**
     * Applied by {@code jrs-upgrade hotfix apply}: files owned, snapshot taken, rollback possible.
     */
    JRSCTL,
    /**
     * Applied by hand and recorded with {@code jrs-upgrade hotfix record}: an inventory row only.
     */
    RECORDED
  }

  public HotfixInstalled {
    Objects.requireNonNull(origin, "origin");
  }

  /** A row applied by jrs-upgrade itself. */
  public HotfixInstalled(
      String id,
      String version,
      String title,
      String installedRunId,
      Optional<String> snapshotRef,
      HotfixState state,
      Instant installedAt) {
    this(id, version, title, installedRunId, snapshotRef, state, installedAt, Origin.JRSCTL);
  }

  /** True when the files were applied outside jrs-upgrade and there is nothing to put back. */
  public boolean recorded() {
    return origin == Origin.RECORDED;
  }
}
