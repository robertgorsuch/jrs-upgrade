package com.jaspersoft.jrsupgrade.ops.doctor;

/**
 * Flags for {@code doctor} (spec §5.7). Invariant: {@code allowUnsupported} downgrades only the
 * compat check from FAIL to WARN and is always written to the audit table; it never changes any
 * other check.
 */
public record DoctorOptions(boolean allowUnsupported) {
  public static final DoctorOptions DEFAULT = new DoctorOptions(false);
}
