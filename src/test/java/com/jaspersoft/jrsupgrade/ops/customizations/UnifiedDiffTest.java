package com.jaspersoft.jrsupgrade.ops.customizations;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class UnifiedDiffTest {

  @Test
  void should_return_no_lines_when_inputs_are_identical() {
    assertThat(UnifiedDiff.of(List.of("a", "b"), List.of("a", "b"), "x", "y")).isEmpty();
  }

  @Test
  void should_emit_headers_hunk_and_markers_when_one_line_changes() {
    List<String> out =
        UnifiedDiff.of(
            List.of("a", "b", "c", "d", "e"), List.of("a", "b", "X", "d", "e"), "x", "y");

    assertThat(out)
        .containsExactly("--- x", "+++ y", "@@ -1,5 +1,5 @@", " a", " b", "-c", "+X", " d", " e");
  }

  @Test
  void should_limit_context_to_three_lines_when_the_change_is_deep_in_the_file() {
    List<String> a = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
    List<String> b = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");

    List<String> out = UnifiedDiff.of(a, b, "x", "y");

    assertThat(out).containsExactly("--- x", "+++ y", "@@ -8,3 +8,4 @@", " 8", " 9", " 10", "+11");
  }

  @Test
  void should_report_insertions_and_deletions_when_both_occur() {
    List<String> out = UnifiedDiff.of(List.of("a", "b"), List.of("b", "c"), "x", "y");

    assertThat(out).containsExactly("--- x", "+++ y", "@@ -1,2 +1,2 @@", "-a", " b", "+c");
  }
}
