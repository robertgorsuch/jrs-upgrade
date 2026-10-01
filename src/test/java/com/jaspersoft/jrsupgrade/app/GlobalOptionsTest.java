package com.jaspersoft.jrsupgrade.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Review finding 4.4: {@code --non-interactive} means "never prompt, fail where a human is needed";
 * only {@code --yes} answers a confirmation.
 */
class GlobalOptionsTest {

  @Test
  void should_not_confirm_when_only_non_interactive_is_given() {
    GlobalOptions g = new GlobalOptions();
    g.nonInteractive = true;

    assertThat(g.yes()).isFalse();
    assertThat(g.nonInteractive()).isTrue();
  }

  @Test
  void should_confirm_and_never_prompt_when_yes_is_given() {
    GlobalOptions g = new GlobalOptions();
    g.yes = true;

    assertThat(g.yes()).isTrue();
    assertThat(g.nonInteractive()).as("--yes implies no prompting").isTrue();
  }
}
