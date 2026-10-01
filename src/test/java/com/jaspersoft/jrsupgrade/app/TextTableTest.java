package com.jaspersoft.jrsupgrade.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Field test 2, G8: tables fit the terminal instead of overflowing and garbling. */
class TextTableTest {

  @Test
  void should_wrap_the_last_column_at_the_width() {
    TextTable t = new TextTable(40);
    t.row("key", "a value that is far too long to fit on one forty column line");

    List<String> lines = t.lines();

    assertThat(lines).hasSize(2);
    assertThat(lines).allMatch(l -> TextTable.visibleLength(l) <= 40);
    assertThat(lines.get(0)).startsWith("key  a value");
    assertThat(lines.get(1)).startsWith("     ").doesNotStartWith("      ");
    assertThat(String.join(" ", lines).replaceAll("\\s+", " "))
        .contains("a value that is far too long to fit on one forty column line");
  }

  @Test
  void should_put_a_token_longer_than_the_room_on_its_own_line_unsplit() {
    TextTable t = new TextTable(30);
    t.row("path", "see /very/long/path/that/cannot/be/broken/anywhere.zip now");

    List<String> lines = t.lines();

    assertThat(lines)
        .containsExactly(
            "path  see", "      /very/long/path/that/cannot/be/broken/anywhere.zip", "      now");
  }

  @Test
  void should_not_wrap_without_a_width_or_when_the_last_column_has_no_room() {
    TextTable free = new TextTable().row("a", "b".repeat(200));
    assertThat(free.lines()).hasSize(1);

    TextTable cramped = new TextTable(20).row("a".repeat(15), "one two three four five six");
    assertThat(cramped.lines()).hasSize(1);
  }

  @Test
  void should_measure_width_without_ansi_escapes_when_wrapping() {
    Ansi ansi = new Ansi(true, true);
    TextTable t = new TextTable(24);
    t.row(ansi.dim("STATUS"), "twelve chars and a few more words");

    assertThat(t.lines()).allMatch(l -> TextTable.visibleLength(l) <= 24);
    assertThat(t.lines().get(0)).contains("STATUS");
  }

  @Test
  void should_default_to_eighty_columns_when_columns_is_unset_or_silly() {
    assertThat(Terminal.width(Map.of())).isEqualTo(80);
    assertThat(Terminal.width(Map.of("COLUMNS", "132"))).isEqualTo(132);
    assertThat(Terminal.width(Map.of("COLUMNS", "12"))).isEqualTo(80);
    assertThat(Terminal.width(Map.of("COLUMNS", "wide"))).isEqualTo(80);
    assertThat(TerminalMarkdown.width(Map.of())).isEqualTo(80);
  }
}
