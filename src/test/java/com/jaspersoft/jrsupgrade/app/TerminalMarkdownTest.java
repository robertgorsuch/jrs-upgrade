package com.jaspersoft.jrsupgrade.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Issue #60: embedded Markdown is readable in a terminal. */
class TerminalMarkdownTest {

  private static final Pattern NEWLINE = Pattern.compile("\\R");

  private static List<String> render(int width, String... lines) {
    return List.of(NEWLINE.split(TerminalMarkdown.render(List.of(lines), width), -1));
  }

  @Test
  void should_underline_headings_and_drop_hashes_when_rendering() {
    assertThat(
            render(80, "# jrs-upgrade guide", "", "## Installing", "", "### `jrs-upgrade doctor`"))
        .containsSubsequence(
            "jrs-upgrade guide", "=================", "", "Installing", "----------")
        .contains("jrs-upgrade doctor")
        .noneMatch(l -> l.startsWith("#"));
  }

  @Test
  void should_strip_inline_markup_and_keep_code_text_when_rendering() {
    assertThat(
            render(
                120,
                "Run **`jrs-upgrade doctor`** first, *always*; see [the guide](docs/guide.md) and"
                    + " [below](#exit-codes). A `a|b` stays."))
        .first()
        .isEqualTo(
            "Run jrs-upgrade doctor first, always; see the guide (docs/guide.md) and below. A a|b"
                + " stays.");
  }

  @Test
  void should_join_hard_wrapped_lines_and_rewrap_at_width_when_rendering_a_paragraph() {
    List<String> out =
        render(
            40,
            "one two three four five six seven eight",
            "nine ten eleven twelve thirteen fourteen fifteen");
    assertThat(out).allMatch(l -> l.length() <= 40);
    assertThat(String.join(" ", out).trim())
        .isEqualTo(
            "one two three four five six seven eight nine ten eleven twelve thirteen fourteen"
                + " fifteen");
  }

  @Test
  void should_indent_wrapped_bullet_text_under_its_marker_when_rendering_lists() {
    List<String> out =
        render(
            40,
            "- **Mutates:** the files the manifest lists under the Tomcat directory",
            "  - nested item",
            "1. first numbered step with enough words to wrap");
    assertThat(out.get(0)).startsWith("- Mutates: the files");
    assertThat(out.get(1)).startsWith("  ").doesNotStartWith("  -");
    assertThat(out).contains("  - nested item").anyMatch(l -> l.startsWith("1. first"));
  }

  @Test
  void should_align_columns_and_drop_pipes_when_a_table_fits() {
    List<String> out =
        render(
            80, "| Code | Meaning |", "|:---:|---|", "| 0 | success |", "| 9 | `runs.lock` held |");
    assertThat(out)
        .containsSubsequence(
            "Code  Meaning", "----  --------------", "0     success", "9     runs.lock held")
        .noneMatch(l -> l.contains("|"));
  }

  @Test
  void should_keep_a_pipe_inside_code_in_a_table_cell_when_splitting_columns() {
    List<String> out =
        render(
            100,
            "| You see | Do this |",
            "|---|---|",
            "| exit 8 | `jrs-upgrade runs recover <id> --resume|--rollback` |");
    assertThat(out).anyMatch(l -> l.endsWith("jrs-upgrade runs recover <id> --resume|--rollback"));
  }

  @Test
  void should_render_one_block_per_row_when_a_table_is_wider_than_the_width() {
    List<String> out =
        render(
            40,
            "| Path | Purpose |",
            "|---|---|",
            "| `config.yaml` | the configuration written by init and edited by you |",
            "| `state.db` | SQLite journal |");
    assertThat(out)
        .containsSubsequence(
            "Path: config.yaml", "Purpose: the configuration written by", "", "Path: state.db")
        .allMatch(l -> l.length() <= 40)
        .noneMatch(l -> l.contains("|"));
  }

  @Test
  void should_indent_code_blocks_verbatim_and_drop_fences_when_rendering() {
    assertThat(
            render(
                20,
                "```bash",
                "jrs-upgrade hotfix apply a-very-long-bundle-name.zip --plan",
                "```"))
        .containsExactly("    jrs-upgrade hotfix apply a-very-long-bundle-name.zip --plan", "");
  }

  @Test
  void should_render_quotes_and_rules_without_markup_when_rendering() {
    assertThat(render(80, "> **Note.** Read this.", "", "---", "", "After."))
        .containsSubsequence("    Note. Read this.", "", "-".repeat(40), "", "After.");
  }

  @Test
  void should_treat_an_equals_underline_as_a_heading_when_it_follows_a_line() {
    assertThat(
            render(80, "jrs-upgrade doctor [--json]", "===========================", "Body text."))
        .containsSubsequence(
            "jrs-upgrade doctor [--json]", "===========================", "Body text.");
  }

  @Test
  void should_leave_no_markdown_markup_when_rendering_the_upgrade_explanation() {
    String text = Explain.text("upgrade").orElseThrow();
    List<String> out =
        List.of(NEWLINE.split(TerminalMarkdown.render(List.of(NEWLINE.split(text)), 80)));

    assertThat(out)
        .noneMatch(l -> l.contains("**"))
        .noneMatch(l -> l.contains("`"))
        .anyMatch(l -> l.startsWith("- Mutates:"))
        .anyMatch(l -> l.contains("WEB-INF/lib"))
        .allMatch(l -> l.length() <= 80 || !l.trim().contains(" "));
  }
}
