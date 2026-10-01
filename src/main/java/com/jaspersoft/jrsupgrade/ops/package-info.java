/**
 * Operations. Mutating operations (hotfix apply/rollback, export, import, upgrade) produce a {@code
 * Plan}; read-only operations (init, doctor, smoke) produce a report. Populated from Phase 2.
 */
package com.jaspersoft.jrsupgrade.ops;
