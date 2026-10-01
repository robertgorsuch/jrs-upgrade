package com.jaspersoft.jrsupgrade.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Field test 3 (ADR-0041): a home can be pointed at a directory on a bigger volume. */
class HomeRedirectTest {

  @TempDir Path tmp;

  @Test
  void should_follow_the_redirect_when_the_home_names_another_directory() throws IOException {
    Path home = Files.createDirectories(tmp.resolve("small"));
    Path big = tmp.resolve("big");
    Files.writeString(HomeRedirect.file(home), HomeRedirect.content(big), StandardCharsets.UTF_8);

    assertThat(HomeRedirect.follow(home)).isEqualTo(big.toAbsolutePath().normalize());
  }

  @Test
  void should_stay_when_the_home_has_no_redirect() throws IOException {
    Path home = Files.createDirectories(tmp.resolve("home"));

    assertThat(HomeRedirect.target(home)).isEmpty();
    assertThat(HomeRedirect.follow(home)).isEqualTo(home.toAbsolutePath().normalize());
  }

  @Test
  void should_follow_one_hop_only_when_the_target_redirects_again() throws IOException {
    Path first = Files.createDirectories(tmp.resolve("first"));
    Path second = Files.createDirectories(tmp.resolve("second"));
    Path third = tmp.resolve("third");
    Files.writeString(HomeRedirect.file(first), HomeRedirect.content(second));
    Files.writeString(HomeRedirect.file(second), HomeRedirect.content(third));

    assertThat(HomeRedirect.follow(first)).isEqualTo(second.toAbsolutePath().normalize());
  }

  @Test
  void should_ignore_the_redirect_when_it_names_a_relative_path_or_nothing() throws IOException {
    Path home = Files.createDirectories(tmp.resolve("home"));
    Files.writeString(HomeRedirect.file(home), "# comment only\n\nrelative/dir\n");

    assertThat(HomeRedirect.target(home)).isEmpty();

    Files.writeString(HomeRedirect.file(home), "# nothing\n");

    assertThat(HomeRedirect.target(home)).isEmpty();
  }
}
