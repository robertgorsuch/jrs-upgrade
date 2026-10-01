package com.jaspersoft.jrsupgrade.app;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Review finding 4.5: nothing but the JSON document may reach the operator's streams in {@code
 * --json} mode, and in text mode the console shows warnings, not the INFO chatter that the log file
 * keeps. The console appender's threshold is a system property fixed from the arguments before any
 * logger loads.
 */
class LogFileTest {

  private String savedLogFile;

  @BeforeEach
  void remember() {
    savedLogFile = System.getProperty(LogFile.PROPERTY);
  }

  @AfterEach
  void restore() {
    System.clearProperty(LogFile.CONSOLE_LEVEL_PROPERTY);
    // configure() sets the log-file property for the whole JVM. Leaving it set made whichever
    // test ran next in this fork read another test's log path, which is why the support-bundle
    // test failed on Ubuntu and passed on Windows: surefire's order differs by file system.
    if (savedLogFile == null) {
      System.clearProperty(LogFile.PROPERTY);
    } else {
      System.setProperty(LogFile.PROPERTY, savedLogFile);
    }
    reloadLogback();
  }

  @Test
  void should_choose_off_for_json_and_warn_otherwise() {
    assertThat(LogFile.consoleLevel(new String[] {"hotfix", "apply", "x", "--json"}))
        .isEqualTo("OFF");
    assertThat(LogFile.consoleLevel(new String[] {"--json"})).isEqualTo("OFF");
    assertThat(LogFile.consoleLevel(new String[] {"hotfix", "apply", "x"})).isEqualTo("WARN");
    assertThat(LogFile.consoleLevel(new String[0])).isEqualTo("WARN");
  }

  @Test
  void should_set_the_console_level_property_when_configuring() {
    System.clearProperty(LogFile.CONSOLE_LEVEL_PROPERTY);

    LogFile.configure(new String[] {"selfcheck", "--json"}, Map.of());

    assertThat(System.getProperty(LogFile.CONSOLE_LEVEL_PROPERTY)).isEqualTo("OFF");
  }

  @Test
  void should_keep_info_off_stderr_and_let_warnings_through_in_text_mode() {
    System.setProperty(LogFile.CONSOLE_LEVEL_PROPERTY, "WARN");
    reloadLogback();

    String stderr = captureStderr(() -> log("info-line-text", "warn-line-text"));

    assertThat(stderr).doesNotContain("info-line-text").contains("warn-line-text");
  }

  @Test
  void should_keep_stderr_silent_in_json_mode() {
    System.setProperty(LogFile.CONSOLE_LEVEL_PROPERTY, "OFF");
    reloadLogback();

    String stderr = captureStderr(() -> log("info-line-json", "warn-line-json"));

    assertThat(stderr).isEmpty();
  }

  private static void log(String info, String warn) {
    org.slf4j.Logger log = LoggerFactory.getLogger(LogFileTest.class);
    log.info(info);
    log.warn(warn);
  }

  private static String captureStderr(Runnable body) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    PrintStream saved = System.err;
    System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
    try {
      reloadLogback(); // the console appender binds System.err when it starts
      body.run();
    } finally {
      System.setErr(saved);
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }

  static void reloadLogback() {
    LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
    URL config = LogFileTest.class.getResource("/logback.xml");
    ctx.reset();
    try {
      JoranConfigurator configurator = new JoranConfigurator();
      configurator.setContext(ctx);
      configurator.doConfigure(config);
    } catch (ch.qos.logback.core.joran.spi.JoranException e) {
      throw new IllegalStateException(e);
    }
  }
}
