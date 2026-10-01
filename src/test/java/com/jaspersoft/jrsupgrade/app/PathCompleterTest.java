package com.jaspersoft.jrsupgrade.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathCompleterTest {

  @TempDir Path dir;

  private final PathCompleter completer = new PathCompleter();
  private final LineReader reader = mock(LineReader.class);

  private record TestLine(String line, int cursor) implements ParsedLine {
    @Override
    public String word() {
      return line;
    }

    @Override
    public int wordCursor() {
      return cursor;
    }

    @Override
    public int wordIndex() {
      return 0;
    }

    @Override
    public List<String> words() {
      return List.of(line);
    }
  }

  private List<Candidate> complete(String typed) {
    List<Candidate> candidates = new ArrayList<>();
    completer.complete(reader, new TestLine(typed, typed.length()), candidates);
    return candidates;
  }

  @Test
  void should_list_every_entry_when_typed_has_no_prefix() throws IOException {
    Files.createFile(dir.resolve("alpha.txt"));
    Files.createDirectory(dir.resolve("beta"));

    List<Candidate> candidates = complete(dir + "/");

    assertThat(candidates)
        .extracting(Candidate::value)
        .containsExactlyInAnyOrder(dir + "/alpha.txt", dir + "/beta/");
  }

  @Test
  void should_filter_case_insensitively_by_the_typed_prefix() throws IOException {
    Files.createFile(dir.resolve("Report.pdf"));
    Files.createFile(dir.resolve("readme.txt"));
    Files.createFile(dir.resolve("other.txt"));

    List<Candidate> candidates = complete(dir + "/re");

    assertThat(candidates)
        .extracting(Candidate::value)
        .containsExactlyInAnyOrder(dir + "/Report.pdf", dir + "/readme.txt");
  }

  @Test
  void should_mark_a_matched_directory_with_a_trailing_slash() throws IOException {
    Files.createDirectory(dir.resolve("sub"));

    List<Candidate> candidates = complete(dir + "/su");

    assertThat(candidates).extracting(Candidate::value).containsExactly(dir + "/sub/");
    assertThat(candidates.get(0).complete()).isFalse();
  }

  @Test
  void should_return_no_candidates_when_the_directory_does_not_exist() {
    List<Candidate> candidates = complete(dir + "/no-such-dir/prefix");

    assertThat(candidates).isEmpty();
  }

  @Test
  void should_return_no_candidates_when_typed_names_a_file_not_a_directory() throws IOException {
    Path file = Files.createFile(dir.resolve("leaf.txt"));

    List<Candidate> candidates = complete(file + "/");

    assertThat(candidates).isEmpty();
  }
}
