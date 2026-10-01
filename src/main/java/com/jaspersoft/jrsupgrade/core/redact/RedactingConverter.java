package com.jaspersoft.jrsupgrade.core.redact;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback conversion word {@code %redact}: the formatted log message passed through {@link
 * Redactor#global()} (spec §5.8). Invariant: it is the only message converter {@code logback.xml}
 * uses, so no appender can print an unredacted message; it never throws, because a logging failure
 * must not become a run failure.
 */
public final class RedactingConverter extends ClassicConverter {

  @Override
  public String convert(ILoggingEvent event) {
    String message = event.getFormattedMessage();
    return message == null ? "" : Redactor.global().redact(message);
  }
}
