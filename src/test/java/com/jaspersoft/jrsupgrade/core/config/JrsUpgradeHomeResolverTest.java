package com.jaspersoft.jrsupgrade.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.platform.DefaultHome;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JrsUpgradeHomeResolverTest {

  private final Platform platform = mock(Platform.class);

  @Test
  void should_use_env_var_when_jrsupgrade_home_is_set() {
    Path custom = Path.of("custom-home").toAbsolutePath();

    JrsUpgradeHome home =
        JrsUpgradeHomeResolver.resolve(Map.of("JRS_UPGRADE_HOME", "custom-home"), platform);

    assertThat(home.root()).isEqualTo(custom.normalize());
    assertThat(home.configFile()).isEqualTo(custom.normalize().resolve("config.yaml"));
  }

  /** Field test 2, G3: {@code ~} in JRS_UPGRADE_HOME means the operator's home. */
  @Test
  void should_expand_a_leading_tilde_in_jrsupgrade_home() {
    Path expected = Path.of("home", "r", "h").toAbsolutePath().normalize();

    JrsUpgradeHome home =
        JrsUpgradeHomeResolver.resolve(
            Map.of(
                "JRS_UPGRADE_HOME",
                "~/h",
                "HOME",
                Path.of("home", "r").toAbsolutePath().toString()),
            platform);

    assertThat(home.root()).isEqualTo(expected);
  }

  /** ADR-0041: a home pointed elsewhere by `jrs-upgrade home set` is followed, from any source. */
  @Test
  void should_follow_a_redirect_when_the_env_home_or_the_default_home_holds_one(
      @org.junit.jupiter.api.io.TempDir Path tmp) throws java.io.IOException {
    Path small = java.nio.file.Files.createDirectories(tmp.resolve("small"));
    Path big = tmp.resolve("big");
    java.nio.file.Files.writeString(
        com.jaspersoft.jrsupgrade.core.platform.HomeRedirect.file(small),
        com.jaspersoft.jrsupgrade.core.platform.HomeRedirect.content(big));
    when(platform.defaultHome()).thenReturn(small);

    assertThat(
            JrsUpgradeHomeResolver.resolve(Map.of("JRS_UPGRADE_HOME", small.toString()), platform)
                .root())
        .isEqualTo(big.toAbsolutePath().normalize());
    assertThat(JrsUpgradeHomeResolver.resolve(Map.of(), platform).root())
        .isEqualTo(big.toAbsolutePath().normalize());
  }

  /**
   * Review of #169: with the system home unwritable, a redirect in the per-user fallback must not
   * get around the refusal; a redirect in the system home itself is the shared answer.
   */
  @Test
  void should_follow_only_the_system_homes_redirect_when_the_system_home_is_unwritable(
      @org.junit.jupiter.api.io.TempDir Path tmp) throws java.io.IOException {
    Path perUser =
        java.nio.file.Files.createDirectories(tmp.resolve("user").resolve(".jrs-upgrade"));
    Path systemHome =
        java.nio.file.Files.createDirectories(tmp.resolve("var").resolve("jrs-upgrade"));
    java.nio.file.Files.writeString(
        com.jaspersoft.jrsupgrade.core.platform.HomeRedirect.file(perUser),
        com.jaspersoft.jrsupgrade.core.platform.HomeRedirect.content(tmp.resolve("mine")));
    when(platform.defaultHome()).thenReturn(perUser);
    DefaultHome.Choice choice = new DefaultHome.Choice(perUser, systemHome, true, true);

    assertThatThrownBy(() -> JrsUpgradeHomeResolver.resolve(Map.of(), platform, choice))
        .isInstanceOf(ConfigException.class);

    java.nio.file.Files.writeString(
        com.jaspersoft.jrsupgrade.core.platform.HomeRedirect.file(systemHome),
        com.jaspersoft.jrsupgrade.core.platform.HomeRedirect.content(tmp.resolve("shared")));

    assertThat(JrsUpgradeHomeResolver.resolve(Map.of(), platform, choice).root())
        .isEqualTo(tmp.resolve("shared").toAbsolutePath().normalize());
  }

  @Test
  void should_fall_back_to_platform_default_when_env_var_is_absent_or_blank() {
    Path dflt = Path.of("platform-default").toAbsolutePath();
    when(platform.defaultHome()).thenReturn(dflt);

    assertThat(JrsUpgradeHomeResolver.resolve(Map.of(), platform).root()).isEqualTo(dflt);
    assertThat(JrsUpgradeHomeResolver.resolve(Map.of("JRS_UPGRADE_HOME", "  "), platform).root())
        .isEqualTo(dflt);
  }

  @Test
  void should_refuse_when_the_system_home_exists_but_this_user_cannot_write_to_it() {
    Path perUser = Path.of("home", "bob", ".jrs-upgrade").toAbsolutePath();
    Path systemHome = Path.of("var", "lib", "jrs-upgrade").toAbsolutePath();
    when(platform.defaultHome()).thenReturn(perUser);
    DefaultHome.Choice choice = new DefaultHome.Choice(perUser, systemHome, true, true);

    assertThatThrownBy(() -> JrsUpgradeHomeResolver.resolve(Map.of(), platform, choice))
        .isInstanceOf(ConfigException.class)
        .hasMessageContaining(systemHome.toString())
        .hasMessageContaining("run elevated");
  }

  @Test
  void should_allow_an_explicit_home_when_the_system_home_is_unwritable() {
    Path perUser = Path.of("home", "bob", ".jrs-upgrade").toAbsolutePath();
    DefaultHome.Choice choice =
        new DefaultHome.Choice(
            perUser, Path.of("var", "lib", "jrs-upgrade").toAbsolutePath(), true, true);

    JrsUpgradeHome home =
        JrsUpgradeHomeResolver.resolve(Map.of("JRS_UPGRADE_HOME", "custom-home"), platform, choice);

    assertThat(home.root()).isEqualTo(Path.of("custom-home").toAbsolutePath().normalize());
  }

  @Test
  void should_not_refuse_when_the_per_user_home_was_chosen_because_none_exists() {
    Path perUser = Path.of("home", "bob", ".jrs-upgrade").toAbsolutePath();
    when(platform.defaultHome()).thenReturn(perUser);
    DefaultHome.Choice choice =
        new DefaultHome.Choice(
            perUser, Path.of("var", "lib", "jrs-upgrade").toAbsolutePath(), true, false);

    assertThat(JrsUpgradeHomeResolver.resolve(Map.of(), platform, choice).root())
        .isEqualTo(perUser);
  }
}
