package com.jaspersoft.jrsupgrade.ops.upgrade;

/** Phase names of spec §10.2 (A-E) plus the rollback plan's single phase. */
final class Phases {

  static final String PREFLIGHT = "preflight";
  static final String BACKUP = "backup";
  static final String VENDOR_UPGRADE = "vendor-upgrade";
  static final String RECONCILE = "reconcile";
  static final String VERIFY = "verify";
  static final String ROLLBACK = "rollback";

  private Phases() {}
}
