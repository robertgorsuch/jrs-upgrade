package com.jaspersoft.jrsupgrade.jrs.api;

import com.jaspersoft.jrsupgrade.core.engine.Step;
import java.util.List;

/**
 * How an export or import is carried out (spec §7.3, §9.2): over REST while the server runs, or
 * with the vendor {@code js-export}/{@code js-import} tools with the service stopped. Invariant:
 * the steps returned already include any service stop/start the strategy needs, so ops code
 * composes them without knowing which strategy was chosen.
 */
public interface ExportImportStrategy {

  enum Kind {
    REST,
    VENDOR_CLI
  }

  Kind kind();

  List<Step> exportSteps(ExportRequest request);

  List<Step> importSteps(ImportRequest request);

  boolean requiresServiceStop();
}
