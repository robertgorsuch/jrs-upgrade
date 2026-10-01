package com.jaspersoft.jrsupgrade.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Field test 2, G3: {@code ~} at the start of a path means the operator's home directory. */
class UserPathsTest {

  @Test
  void should_expand_a_leading_tilde_only() {
    assertThat(UserPaths.expand("~/ws/x", Map.of("HOME", "/home/r"))).isEqualTo("/home/r/ws/x");
    assertThat(UserPaths.expand("~", Map.of("HOME", "/home/r"))).isEqualTo("/home/r");
    assertThat(UserPaths.expand("~\\ws", Map.of("HOME", "C:\\Users\\r")))
        .isEqualTo("C:\\Users\\r\\ws");
    assertThat(UserPaths.expand("~bob/x", Map.of("HOME", "/home/r"))).isEqualTo("~bob/x");
    assertThat(UserPaths.expand("a~b", Map.of("HOME", "/h"))).isEqualTo("a~b");
    assertThat(UserPaths.expand("/abs/~/x", Map.of("HOME", "/h"))).isEqualTo("/abs/~/x");
  }

  @Test
  void should_prefer_home_then_userprofile_then_the_jvm_home() {
    assertThat(UserPaths.expand("~/x", Map.of("HOME", "/h", "USERPROFILE", "C:\\u")))
        .isEqualTo("/h/x");
    assertThat(UserPaths.expand("~/x", Map.of("USERPROFILE", "C:\\u"))).isEqualTo("C:\\u/x");
    assertThat(UserPaths.expand("~/x", Map.of())).isEqualTo(System.getProperty("user.home") + "/x");
  }
}
