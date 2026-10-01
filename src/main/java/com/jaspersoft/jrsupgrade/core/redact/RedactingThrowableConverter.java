package com.jaspersoft.jrsupgrade.core.redact;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback conversion word {@code %redactEx}: the rendered stack trace passed through {@link
 * Redactor#global()} (spec §5.8), so an exception message that quotes a secret cannot leak through
 * an appender. Invariant: output is identical to {@code %ex} except for masked secrets.
 */
public final class RedactingThrowableConverter extends ThrowableProxyConverter {

  @Override
  public String convert(ILoggingEvent event) {
    String rendered = super.convert(event);
    return rendered == null ? "" : Redactor.global().redact(rendered);
  }
}
