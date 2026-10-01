package com.jaspersoft.jrsupgrade.core.engine;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Child process for {@code RunLockTest}: takes the run lock under the given home, probes its own
 * lock file the way doctor and the console do, prints {@code ready}, and holds the lock until its
 * standard input closes. The parent then checks from outside that the OS lock is still held.
 */
public final class RunLockChildHolder {

  private RunLockChildHolder() {}

  public static void main(String[] args) throws Exception {
    JrsUpgradeHome home = new JrsUpgradeHome(Path.of(args[0]));
    try (RunLock lock = new RunLock(home, "r-child", Instant.now())) {
      RunLock.readHolder(home.runLock());
      RunLock.heldBy(home.runLock());
      System.out.println("ready " + lock.runId());
      System.out.flush();
      while (System.in.read() != -1) {
        // hold the lock until the parent closes our stdin
      }
    }
  }
}
