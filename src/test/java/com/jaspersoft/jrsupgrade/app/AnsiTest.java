package com.jaspersoft.jrsupgrade.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.ops.ReportItem;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Review finding 3.5: colour and glyph choice are separate decisions, {@code --color} and {@code
 * --ascii} override both, and {@code NO_COLOR} still wins over the automatic choice.
 */
class AnsiTest {

  @Test
  void should_colour_when_color_is_always_even_without_a_terminal() {
    GlobalOptions g = new GlobalOptions();
    g.color = GlobalOptions.Color.ALWAYS;

    Ansi ansi = Ansi.forStdout(g, Map.of());

    assertThat(ansi.enabled()).isTrue();
    assertThat(ansi.status(ReportItem.Status.FAIL)).startsWith(Ansi.RED).endsWith(Ansi.RESET);
  }

  @Test
  void should_not_colour_when_color_is_never_or_no_color_is_given() {
    GlobalOptions never = new GlobalOptions();
    never.color = GlobalOptions.Color.NEVER;
    GlobalOptions legacy = new GlobalOptions();
    legacy.noColor = true;

    assertThat(Ansi.forStdout(never, Map.of()).enabled()).isFalse();
    assertThat(Ansi.forStdout(legacy, Map.of()).enabled()).isFalse();
    assertThat(legacy.color()).isEqualTo(GlobalOptions.Color.NEVER);
    assertThat(Ansi.forStdout(legacy, Map.of()).status(ReportItem.Status.FAIL)).isEqualTo("x FAIL");
  }

  @Test
  void should_not_colour_when_no_color_is_set_in_the_environment() {
    GlobalOptions g = new GlobalOptions();

    assertThat(Ansi.forStdout(g, Map.of("NO_COLOR", "1")).enabled()).isFalse();
  }

  @Test
  void should_refuse_glyphs_when_ascii_is_given() {
    GlobalOptions g = new GlobalOptions();
    g.ascii = true;
    g.color = GlobalOptions.Color.ALWAYS;

    Ansi ansi = Ansi.forStdout(g, Map.of());

    assertThat(ansi.unicode()).isFalse();
    assertThat(ansi.enabled()).as("--ascii drops glyphs, not colour").isTrue();
  }

  @Test
  void should_refuse_glyphs_when_the_output_code_page_cannot_carry_them() {
    assertThat(StandardCharsets.UTF_8.newEncoder().canEncode(Ansi.GLYPHS)).isTrue();
    assertThat(java.nio.charset.Charset.forName("IBM850").newEncoder().canEncode(Ansi.GLYPHS))
        .as("cp850 has no tick or arrows")
        .isFalse();
  }
}
