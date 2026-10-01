package com.jaspersoft.jrsupgrade.ops.init;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.platform.ServiceConfig;
import com.jaspersoft.jrsupgrade.ops.FakeLayout;
import com.jaspersoft.jrsupgrade.ops.FakePlatform;
import com.jaspersoft.jrsupgrade.ops.FakeServices;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AWS guide p.30 (issue #113): the vendor's AWS images control Tomcat through {@code
 * tomcat.socket}. A socket-activated Tomcat must be driven through its socket, so init prefers the
 * socket unit whenever the service and its socket are both listed.
 */
class InitSocketUnitTest {

  @TempDir Path tmp;

  @Test
  void should_prefer_the_socket_unit_over_its_service_and_keep_the_rest() {
    assertThat(
            InitOperation.preferSockets(
                List.of("tomcat.service", "ssh.service", "tomcat.socket", "jasperreports")))
        .containsExactly("tomcat.socket", "ssh.service", "jasperreports");
    assertThat(InitOperation.preferSockets(List.of("tomcat", "tomcat.socket")))
        .containsExactly("tomcat.socket");
    assertThat(InitOperation.serviceUnitOf("tomcat.socket")).isEqualTo("tomcat.service");
    assertThat(InitOperation.serviceUnitOf("jasperreports.service"))
        .isEqualTo("jasperreports.service");
  }

  @Test
  void should_detect_the_socket_unit_when_systemctl_lists_service_and_socket() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.LINUX)) {
      fake.platform.on(
          List.of("systemctl"),
          FakePlatform.Response.ok(
              "tomcat.service loaded active running Apache Tomcat",
              "tomcat.socket loaded active listening Apache Tomcat Socket"));
      InitOperation init = new InitOperation(fake.build(), () -> Optional.of("jasperserver"));

      InitReport report = init.detect(Optional.of(install));
      Config config = init.toConfig(report);

      assertThat(config.service().kind()).contains(ServiceConfig.Kind.SYSTEMD);
      assertThat(config.service().name()).contains("tomcat.socket");
      assertThat(fake.platform.invocations)
          .anyMatch(c -> c.contains("list-units") && c.contains("--type=service,socket"));
    }
  }
}
