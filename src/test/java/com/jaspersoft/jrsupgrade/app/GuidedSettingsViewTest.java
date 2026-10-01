package com.jaspersoft.jrsupgrade.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Review of #172: what the guided settings entry is given, from a real home. */
class GuidedSettingsViewTest {

  @TempDir Path tmp;

  private GuidedMode.SettingsView view(Path home) {
    JrsUpgradeCommand command = new JrsUpgradeCommand();
    new CommandLine(command).parseArgs("--home", home.toString());
    return command.settings();
  }

  @Test
  void should_say_there_is_no_file_when_the_home_has_no_configuration() {
    GuidedMode.SettingsView view = view(tmp.resolve("empty"));

    assertThat(view.exists()).isFalse();
  }

  @Test
  void should_report_the_problem_when_the_configuration_exists_but_is_broken() throws Exception {
    Path home = Files.createDirectories(tmp.resolve("broken"));
    Files.writeString(home.resolve("config.yaml"), "server: [unclosed\n", StandardCharsets.UTF_8);

    GuidedMode.SettingsView view = view(home);

    assertThat(view.exists()).isTrue();
    assertThat(view.problem()).isPresent();
  }

  @Test
  void should_redact_a_secret_shaped_value_when_listing_the_settings() throws Exception {
    Path home = Files.createDirectories(tmp.resolve("ok"));
    Files.writeString(
        home.resolve("config.yaml"),
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
        database:
          type: postgresql
          url: jdbc:postgresql://db/jrs?user=jrs&password=hunter2
        """,
        StandardCharsets.UTF_8);

    GuidedMode.SettingsView view = view(home);

    assertThat(view.problem()).isEmpty();
    assertThat(view.values().get("server.baseUrl"))
        .isEqualTo("http://localhost:8080/jasperserver-pro");
    assertThat(String.join(" ", view.values().values())).doesNotContain("hunter2");
  }
}
