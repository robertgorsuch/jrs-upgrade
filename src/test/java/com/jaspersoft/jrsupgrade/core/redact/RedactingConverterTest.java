package com.jaspersoft.jrsupgrade.core.redact;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.status.StatusUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class RedactingConverterTest {

  private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
  private final Logger logger = context.getLogger(RedactingConverterTest.class);

  @AfterEach
  void clearGlobal() {
    Redactor.global().clear();
  }

  @Test
  void should_redact_formatted_message_when_secret_is_registered_globally() {
    Redactor.global().register("hunter2!");
    LoggingEvent event =
        new LoggingEvent(
            Logger.class.getName(),
            logger,
            Level.INFO,
            "login as {} with {}",
            null,
            new Object[] {"admin", "hunter2!"});

    RedactingConverter converter = new RedactingConverter();
    converter.start();

    assertThat(converter.convert(event)).isEqualTo("login as admin with [redacted]");
  }

  @Test
  void should_redact_stack_trace_when_exception_message_quotes_a_secret() {
    Redactor.global().register("hunter2!");
    LoggingEvent event =
        new LoggingEvent(
            Logger.class.getName(),
            logger,
            Level.ERROR,
            "boom",
            new IllegalStateException("bad password hunter2!"),
            null);

    RedactingThrowableConverter converter = new RedactingThrowableConverter();
    converter.setContext(context);
    converter.start();

    String rendered = converter.convert(event);
    assertThat(rendered).contains("IllegalStateException").doesNotContain("hunter2!");
  }

  @Test
  void should_mask_patterns_when_nothing_is_registered() {
    LoggingEvent event =
        new LoggingEvent(
            Logger.class.getName(), logger, Level.INFO, "Authorization: Basic abc==", null, null);

    assertThat(new RedactingConverter().convert(event)).isEqualTo("Authorization: [redacted]");
  }

  @Test
  void should_load_bundled_logback_xml_without_errors_when_context_starts() {
    StatusUtil status = new StatusUtil(context);

    assertThat(status.getHighestLevel(0)).isLessThan(Status.ERROR);
    assertThat(context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE")).isNotNull();
    assertThat(context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("JSON")).isNotNull();
  }

  /**
   * Review finding 3.7: console lines carry the date and the zone offset so a Windows workstation
   * and a UTC server can be correlated in a support bundle.
   */
  @Test
  void should_stamp_console_lines_with_date_and_zone_when_bundled_config_is_loaded() {
    ConsoleAppender<?> console =
        (ConsoleAppender<?>) context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE");

    String pattern = ((PatternLayoutEncoder) console.getEncoder()).getPattern();

    assertThat(pattern).startsWith("%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX}");
  }
}
