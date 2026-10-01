package com.jaspersoft.jrsupgrade.ops.merge;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class Diff3Test {

  private static List<String> lines(String text) {
    return text.isEmpty() ? List.of() : List.of(text.split(" ", -1));
  }

  private static Diff3.Result merge(String base, String mine, String theirs) {
    return Diff3.merge(lines(base), lines(mine), lines(theirs));
  }

  @Test
  void should_apply_both_changes_when_they_do_not_overlap() {
    Diff3.Result r = merge("a b c d e f", "a B c d e f", "a b c d E f g");
    assertThat(r.lines()).isEqualTo(lines("a B c d E f g"));
    assertThat(r.clean()).isTrue();
  }

  @Test
  void should_take_the_one_side_that_changed() {
    assertThat(merge("a b c", "a b c", "a x c").lines()).isEqualTo(lines("a x c"));
    assertThat(merge("a b c", "a x c", "a b c").lines()).isEqualTo(lines("a x c"));
    assertThat(merge("a b c", "a c", "a b c d").lines()).isEqualTo(lines("a c d"));
    assertThat(merge("a b c", "new a b c", "a b c").lines()).isEqualTo(lines("new a b c"));
  }

  @Test
  void should_take_a_change_once_when_both_made_it() {
    Diff3.Result r = merge("a b c", "a x c", "a x c");
    assertThat(r.lines()).isEqualTo(lines("a x c"));
    assertThat(r.clean()).isTrue();
  }

  @Test
  void should_be_the_input_when_all_three_are_identical_or_empty() {
    assertThat(merge("a b c", "a b c", "a b c").lines()).isEqualTo(lines("a b c"));
    Diff3.Result empty = merge("", "", "");
    assertThat(empty.lines()).isEmpty();
    assertThat(empty.clean()).isTrue();
  }

  @Test
  void should_write_both_sides_and_the_base_between_markers_when_the_changes_overlap() {
    Diff3.Result r = merge("a b c", "a mine c", "a theirs c");
    assertThat(r.conflicts()).isEqualTo(1);
    assertThat(r.lines())
        .containsExactly(
            "a",
            Diff3.MARK_MINE,
            "mine",
            Diff3.MARK_BASE,
            "b",
            Diff3.MARK_SEPARATOR,
            "theirs",
            Diff3.MARK_THEIRS,
            "c");
  }

  @Test
  void should_conflict_when_there_is_no_base_and_the_two_differ() {
    Diff3.Result r = merge("", "a b", "a c");
    assertThat(r.conflicts()).isEqualTo(1);
    assertThat(merge("", "a b", "a b").clean()).isTrue();
    assertThat(merge("", "a b", "a b").lines()).isEqualTo(lines("a b"));
  }

  @Test
  void should_keep_a_change_and_mark_a_conflict_in_the_same_file() {
    Diff3.Result r = merge("a b c d e f g h", "a B c d e f X h", "a b c d e f Y h i");
    assertThat(r.conflicts()).isEqualTo(1);
    assertThat(r.lines().subList(0, 6)).isEqualTo(lines("a B c d e f"));
    assertThat(r.lines().get(r.lines().size() - 1)).isEqualTo("i");
  }

  @Test
  void should_find_the_longest_common_subsequence() {
    List<String> a = lines("a b c a b b a");
    List<String> b = lines("c b a b a c");
    int[] match = Diff.matches(a, b);
    int common = 0;
    int last = -1;
    for (int i = 0; i < a.size(); i++) {
      if (match[i] >= 0) {
        assertThat(a.get(i)).isEqualTo(b.get(match[i]));
        assertThat(match[i]).isGreaterThan(last);
        last = match[i];
        common++;
      }
    }
    assertThat(common).isEqualTo(4);
  }

  @Test
  void should_rebuild_the_second_file_from_the_matches_whatever_the_files() {
    Random random = new Random(7);
    for (int round = 0; round < 200; round++) {
      List<String> a = new ArrayList<>();
      List<String> b = new ArrayList<>();
      for (int i = random.nextInt(30); i > 0; i--) {
        a.add(String.valueOf(random.nextInt(6)));
      }
      for (int i = random.nextInt(30); i > 0; i--) {
        b.add(String.valueOf(random.nextInt(6)));
      }
      int[] match = Diff.matches(a, b);
      int last = -1;
      for (int i = 0; i < a.size(); i++) {
        if (match[i] >= 0) {
          assertThat(a.get(i)).isEqualTo(b.get(match[i]));
          assertThat(match[i]).isGreaterThan(last);
          last = match[i];
        }
      }
      // a side that did not change always merges to the other side
      assertThat(Diff3.merge(a, a, b).lines()).isEqualTo(b);
      assertThat(Diff3.merge(a, b, a).lines()).isEqualTo(b);
    }
  }

  @Test
  void should_print_a_unified_diff_with_context_and_nothing_when_equal() {
    List<String> a = lines("1 2 3 4 5 6 7 8 9 10 11 12");
    List<String> b = lines("1 2 3 4 5 six 7 8 9 10 11 12 13");
    assertThat(Diff.unified("base", "mine", a, b))
        .containsExactly(
            "--- base",
            "+++ mine",
            "@@ -3,10 +3,11 @@",
            " 3",
            " 4",
            " 5",
            "-6",
            "+six",
            " 7",
            " 8",
            " 9",
            " 10",
            " 11",
            " 12",
            "+13");
    assertThat(Diff.unified("base", "mine", a, a)).isEmpty();
    assertThat(Diff.unified("x", "y", List.of(), lines("a")))
        .containsExactly("--- x", "+++ y", "@@ -0,0 +1,1 @@", "+a");
  }

  @Test
  void should_split_text_into_lines_without_line_ends_and_write_it_back_in_the_same_ends() {
    Text windows = Text.of("a\r\nb\r\n".getBytes(StandardCharsets.ISO_8859_1));
    assertThat(windows.lines()).containsExactly("a", "b");
    assertThat(windows.eol()).isEqualTo("\r\n");
    assertThat(new String(windows.bytes(List.of("x", "y")), StandardCharsets.ISO_8859_1))
        .isEqualTo("x\r\ny\r\n");
    Text ragged = Text.of("a\n\nb".getBytes(StandardCharsets.ISO_8859_1));
    assertThat(ragged.lines()).containsExactly("a", "", "b");
    assertThat(new String(ragged.bytes(ragged.lines()), StandardCharsets.ISO_8859_1))
        .isEqualTo("a\n\nb");
    assertThat(Text.of(new byte[0]).lines()).isEmpty();
    assertThat(Text.of(new byte[0]).bytes(List.of())).isEmpty();
  }
}
