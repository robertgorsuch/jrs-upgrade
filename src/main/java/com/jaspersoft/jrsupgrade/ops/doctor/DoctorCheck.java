package com.jaspersoft.jrsupgrade.ops.doctor;

import com.jaspersoft.jrsupgrade.ops.ReportItem;
import com.jaspersoft.jrsupgrade.ops.Services;

/**
 * One {@code doctor} check (spec §12.1). Invariants: a check is read-only, never throws for an
 * expected failure (it returns a FAIL or WARN item with a remediation instead), and never puts a
 * secret value into the item; an unexpected exception is turned into a FAIL by the operation.
 */
@FunctionalInterface
public interface DoctorCheck {
  ReportItem check(Services services);
}
