package com.jaspersoft.jrsupgrade.core.platform;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The Tomcat JVMs running under one directory, as the process scan saw them, whatever {@code
 * service.kind} says (issue #147). Invariants: {@link Scanned#pids()} holds only processes whose
 * command line or {@code catalina.home}/{@code catalina.base} places them under the directory;
 * {@link Scanned#unreadable()} holds processes whose command line this account cannot read (a JVM,
 * or a {@code tomcatN.exe} service wrapper run by another account) and which might be that Tomcat,
 * because they listen on one of its {@code server.xml} ports or those ports are not known, the rule
 * {@code TomcatState} uses (ADR-0014); both lists are in ascending order; a scan that could not run
 * is {@link Unavailable} with the reason, never an empty {@link Scanned}.
 */
public sealed interface RunningTomcats {

  /** The scan ran. */
  record Scanned(List<Long> pids, List<Long> unreadable) implements RunningTomcats {
    public Scanned {
      pids = pids.stream().sorted().toList();
      unreadable = unreadable.stream().sorted().toList();
    }
  }

  /** The scan could not run, or this platform does not scan processes. */
  record Unavailable(String reason) implements RunningTomcats {
    public Unavailable {
      Objects.requireNonNull(reason, "reason");
    }
  }

  /** Classifies one listing from {@code finder}, service wrappers included, against {@code dir}. */
  static RunningTomcats scan(TomcatProcessFinder finder, Path dir) {
    List<TomcatProcessFinder.TomcatProcess> found;
    try {
      found = finder.findWithServiceWrappers();
    } catch (TomcatScanException e) {
      return new Unavailable("the process scan failed: " + e.getMessage());
    }
    Set<Integer> ports = ServerXml.portsUnder(dir);
    List<Long> pids =
        found.stream()
            .filter(p -> !p.opaque() && p.belongsTo(dir))
            .map(TomcatProcessFinder.TomcatProcess::pid)
            .toList();
    List<Long> unreadable =
        found.stream()
            .filter(TomcatProcessFinder.TomcatProcess::opaque)
            .filter(p -> ports.isEmpty() || !Collections.disjoint(p.listeningPorts(), ports))
            .map(TomcatProcessFinder.TomcatProcess::pid)
            .toList();
    return new Scanned(pids, unreadable);
  }
}
