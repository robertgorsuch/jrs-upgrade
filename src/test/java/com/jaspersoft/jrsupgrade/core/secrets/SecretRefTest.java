package com.jaspersoft.jrsupgrade.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SecretRefTest {

  @Test
  void should_parse_each_kind_when_prefix_is_known() {
    assertThat(SecretRef.parse("env:JRS_PASSWORD")).isEqualTo(new SecretRef.Env("JRS_PASSWORD"));
    assertThat(SecretRef.parse("file:/run/secrets/db"))
        .isEqualTo(new SecretRef.File(Path.of("/run/secrets/db")));
    assertThat(SecretRef.parse("enc:db.password")).isEqualTo(new SecretRef.Enc("db.password"));
    assertThat(SecretRef.parse("  ENV:X ")).isEqualTo(new SecretRef.Env("X"));
  }

  @Test
  void should_render_back_to_parsable_text_when_parsed() {
    for (String text : new String[] {"env:JRS_PASSWORD", "enc:db", "file:/run/secrets/db"}) {
      SecretRef ref = SecretRef.parse(text);
      assertThat(SecretRef.parse(ref.render())).isEqualTo(ref);
    }
  }

  @Test
  void should_reject_unknown_prefix_when_parsing() {
    assertThatThrownBy(() -> SecretRef.parse("vault:db"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("vault:db")
        .hasMessageContaining("env:NAME, file:/path or enc:NAME");
  }

  @Test
  void should_reject_missing_value_or_separator_when_parsing() {
    assertThatThrownBy(() -> SecretRef.parse("env:")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SecretRef.parse("hunter2"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SecretRef.parse(":x")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void should_reject_invalid_names_when_kind_restricts_them() {
    assertThatThrownBy(() -> SecretRef.parse("env:9bad"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("env:9bad");
    assertThatThrownBy(() -> SecretRef.parse("enc:has space"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
