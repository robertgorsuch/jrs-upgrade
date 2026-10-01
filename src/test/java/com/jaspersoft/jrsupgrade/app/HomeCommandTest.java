package com.jaspersoft.jrsupgrade.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.json.Json;
import com.jaspersoft.jrsupgrade.core.platform.HomeRedirect;
import com.jaspersoft.jrsupgrade.core.state.HotfixInstalled;
import com.jaspersoft.jrsupgrade.core.state.HotfixState;
import com.jaspersoft.jrsupgrade.core.state.StateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Field test 3 (ADR-0041): the jrs-upgrade home can be seen and moved without a setting in it. */
class HomeCommandTest {

  @TempDir Path tmp;

  @Test
  void should_redirect_every_later_command_when_the_home_is_set() throws Exception {
    Path small = Files.createDirectories(tmp.resolve("small"));
    Path big = tmp.resolve("big");

    InitCommandTest.Run set =
        InitCommandTest.run("home", "set", big.toString(), "--home", small.toString());
    InitCommandTest.Run show =
        InitCommandTest.run("home", "show", "--json", "--home", small.toString());

    assertThat(set.code()).as(set.out() + set.err()).isZero();
    assertThat(set.out()).contains("home reset");
    assertThat(HomeRedirect.target(small)).contains(big.toAbsolutePath().normalize());
    JsonNode doc = Json.mapper().readTree(show.out());
    assertThat(doc.get("home").asText()).isEqualTo(big.toAbsolutePath().normalize().toString());
    assertThat(doc.get("base").asText()).isEqualTo(small.toAbsolutePath().normalize().toString());
    assertThat(doc.get("source").asText()).isEqualTo("--home");
    assertThat(big).isDirectory();
  }

  @Test
  void should_refuse_to_move_when_the_home_still_holds_an_installed_hotfix() throws Exception {
    Path small = Files.createDirectories(tmp.resolve("small"));
    try (StateStore store = StateStore.open(new JrsUpgradeHome(small), Clock.systemUTC())) {
      store.recordHotfixInstalled(
          new HotfixInstalled(
              "HF-1", "1", "t", "r-1", Optional.empty(), HotfixState.INSTALLED, Instant.now()),
          List.of());
    }

    InitCommandTest.Run refused =
        InitCommandTest.run(
            "home", "set", tmp.resolve("big").toString(), "--home", small.toString());
    InitCommandTest.Run forced =
        InitCommandTest.run(
            "home", "set", tmp.resolve("big").toString(), "--force", "--home", small.toString());

    assertThat(refused.code()).isEqualTo(ExitCodes.PRECHECK_FAILED);
    assertThat(refused.err()).contains("1 installed hotfix").contains("--force");
    assertThat(forced.code()).as(forced.err()).isZero();
    assertThat(HomeRedirect.target(small)).isPresent();
  }

  @Test
  void should_remove_the_redirect_when_the_home_is_reset() throws Exception {
    Path small = Files.createDirectories(tmp.resolve("small"));
    InitCommandTest.run("home", "set", tmp.resolve("big").toString(), "--home", small.toString());

    InitCommandTest.Run reset = InitCommandTest.run("home", "reset", "--home", small.toString());

    assertThat(reset.code()).as(reset.err()).isZero();
    assertThat(HomeRedirect.file(small)).doesNotExist();
  }

  /** Review of #169: after one move, the home holding the redirect is guarded as well. */
  @Test
  void should_refuse_a_directory_when_it_is_inside_the_home_that_holds_the_redirect()
      throws Exception {
    Path small = Files.createDirectories(tmp.resolve("small"));
    InitCommandTest.run("home", "set", tmp.resolve("big").toString(), "--home", small.toString());

    InitCommandTest.Run nested =
        InitCommandTest.run(
            "home", "set", small.resolve("inner").toString(), "--home", small.toString());

    assertThat(nested.code()).isEqualTo(ExitCodes.USAGE);
    assertThat(HomeRedirect.target(small))
        .contains(tmp.resolve("big").toAbsolutePath().normalize());
  }

  @Test
  void should_refuse_a_directory_when_it_is_the_home_or_inside_it() throws Exception {
    Path small = Files.createDirectories(tmp.resolve("small"));

    InitCommandTest.Run same =
        InitCommandTest.run("home", "set", small.toString(), "--home", small.toString());
    InitCommandTest.Run inside =
        InitCommandTest.run(
            "home", "set", small.resolve("sub").toString(), "--home", small.toString());

    assertThat(same.code()).isEqualTo(ExitCodes.USAGE);
    assertThat(inside.code()).isEqualTo(ExitCodes.USAGE);
    assertThat(HomeRedirect.file(small)).doesNotExist();
  }
}
