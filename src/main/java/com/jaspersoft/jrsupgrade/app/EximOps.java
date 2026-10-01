package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.ops.Services;
import com.jaspersoft.jrsupgrade.ops.exim.DefaultExportImportOperations;
import com.jaspersoft.jrsupgrade.ops.exim.ExportImportOperations;
import java.util.function.Function;

/**
 * Where the CLI obtains its {@link ExportImportOperations}. Invariant: production code always
 * builds {@link DefaultExportImportOperations} over the bootstrapped {@link Services}; the factory
 * is replaced only by tests, and always restored to {@link #DEFAULT_FACTORY} afterwards.
 */
final class EximOps {

  static final Function<Services, ExportImportOperations> DEFAULT_FACTORY =
      DefaultExportImportOperations::new;

  static volatile Function<Services, ExportImportOperations> factory = DEFAULT_FACTORY;

  private EximOps() {}

  static ExportImportOperations open(Services services) {
    return factory.apply(services);
  }
}
