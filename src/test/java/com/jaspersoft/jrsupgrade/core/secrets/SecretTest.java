package com.jaspersoft.jrsupgrade.core.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecretTest {

  @Test
  void should_hide_value_when_converted_to_string() {
    try (Secret s = Secret.fromString("hunter2")) {
      assertThat(s.toString()).isEqualTo("[secret]").doesNotContain("hunter2");
      assertThat(s.length()).isEqualTo(7);
    }
  }

  @Test
  void should_copy_input_and_output_when_arrays_are_mutated() {
    char[] source = "hunter2".toCharArray();
    try (Secret s = Secret.of(source)) {
      source[0] = 'X';
      char[] copy = s.chars();
      copy[1] = 'Y';
      assertThat(s.chars()).containsExactly("hunter2".toCharArray());
    }
  }

  @Test
  void should_zero_and_refuse_access_when_closed() {
    Secret s = Secret.fromString("hunter2");
    s.close();
    s.close();

    assertThat(s.isClosed()).isTrue();
    assertThatThrownBy(s::chars).isInstanceOf(IllegalStateException.class);
    assertThat(s.toString()).isEqualTo("[secret]");
  }

  @Test
  void should_compare_in_constant_time_when_matching() {
    try (Secret a = Secret.fromString("hunter2");
        Secret b = Secret.fromString("hunter2");
        Secret c = Secret.fromString("hunter3");
        Secret d = Secret.fromString("hunter22")) {
      assertThat(a.matches(b)).isTrue();
      assertThat(a.matches(c)).isFalse();
      assertThat(a.matches(d)).isFalse();
      b.close();
      assertThat(a.matches(b)).isFalse();
    }
  }
}
