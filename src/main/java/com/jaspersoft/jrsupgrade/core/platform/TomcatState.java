package com.jaspersoft.jrsupgrade.core.platform;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Derives a {@link ServiceController.State} from running Tomcat processes. Invariants: {@code
 * RUNNING} when a readable process belongs to the install directory (any Tomcat when none is
 * given); otherwise {@code UNKNOWN} when the scan could not run, or when an {@link
 * TomcatProcessFinder.TomcatProcess#opaque() opaque} JVM listens on one of the watched Tomcat's
 * configured ports (or those ports are not known), because it may be that Tomcat run by another
 * account; {@code STOPPED} only when the scan ran and nothing could be it; never {@code STARTING}
 * or {@code STOPPING}, because a process listing cannot distinguish a starting JVM from a serving
 * one. {@code UNKNOWN} rather than {@code STOPPED} on a blind scan is what makes the service steps
 * refuse instead of skipping a stop (issue #38, ADR-0014).
 */
final class TomcatState {

  private static final Logger LOG = LoggerFactory.getLogger(TomcatState.class);

  private TomcatState() {}

  /**
   * As {@link #of(TomcatProcessFinder, Optional, Set)} with no known ports: any opaque JVM counts.
   */
  static ServiceController.State of(TomcatProcessFinder finder, Optional<Path> installDir) {
    return of(finder, installDir, Set.of());
  }

  static ServiceController.State of(
      TomcatProcessFinder finder, Optional<Path> installDir, Set<Integer> watchedPorts) {
    List<TomcatProcessFinder.TomcatProcess> found;
    try {
      found = finder.find();
    } catch (TomcatScanException e) {
      LOG.debug("process scan failed: {}", e.getMessage());
      return ServiceController.State.UNKNOWN;
    }
    boolean running =
        found.stream()
            .filter(p -> !p.opaque())
            .anyMatch(p -> installDir.map(p::belongsTo).orElse(true));
    if (running) {
      return ServiceController.State.RUNNING;
    }
    boolean ambiguous =
        found.stream()
            .filter(TomcatProcessFinder.TomcatProcess::opaque)
            .anyMatch(
                p ->
                    watchedPorts.isEmpty()
                        || !Collections.disjoint(p.listeningPorts(), watchedPorts));
    return ambiguous ? ServiceController.State.UNKNOWN : ServiceController.State.STOPPED;
  }
}
