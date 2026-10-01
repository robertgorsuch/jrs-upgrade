package com.jaspersoft.jrsupgrade.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Review finding 3.5: Windows console programs write the OEM code page, so their output is decoded
 * with it rather than with the ANSI code page {@code native.encoding} reports.
 */
class WindowsCodePageTest {

  @Test
  void should_read_the_code_page_from_a_chcp_line() {
    assertThat(WindowsCodePage.codePageOf("Active code page: 850"))
        .contains(Charset.forName("IBM850"));
    assertThat(WindowsCodePage.codePageOf("Aktive Codepage: 437."))
        .contains(Charset.forName("IBM437"));
    assertThat(WindowsCodePage.codePageOf("Active code page: 65001"))
        .contains(StandardCharsets.UTF_8);
  }

  @Test
  void should_be_empty_when_the_line_carries_no_code_page() {
    assertThat(WindowsCodePage.codePageOf("")).isEmpty();
    assertThat(WindowsCodePage.codePageOf(null)).isEmpty();
    assertThat(WindowsCodePage.codePageOf("chcp.com is not recognized")).isEmpty();
  }

  @Test
  void should_accept_a_charset_name_as_well_as_a_number() {
    assertThat(WindowsCodePage.forCodePageOrName("IBM850")).contains(Charset.forName("IBM850"));
    assertThat(WindowsCodePage.forCodePageOrName("UTF-8")).contains(StandardCharsets.UTF_8);
    assertThat(WindowsCodePage.forCodePageOrName("not-a-charset")).isEmpty();
    assertThat(WindowsCodePage.forCodePageOrName("  ")).isEmpty();
  }

  @Test
  void should_honour_the_override_property() {
    String saved = System.getProperty(WindowsCodePage.PROPERTY);
    WindowsCodePage.forget();
    System.setProperty(WindowsCodePage.PROPERTY, "IBM437");
    try {
      assertThat(WindowsCodePage.consoleCharset()).isEqualTo(Charset.forName("IBM437"));
    } finally {
      if (saved == null) {
        System.clearProperty(WindowsCodePage.PROPERTY);
      } else {
        System.setProperty(WindowsCodePage.PROPERTY, saved);
      }
      WindowsCodePage.forget();
    }
  }
}
