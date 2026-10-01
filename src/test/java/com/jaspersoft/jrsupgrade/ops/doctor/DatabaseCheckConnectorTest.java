package com.jaspersoft.jrsupgrade.ops.doctor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.ops.FakeServices;
import com.jaspersoft.jrsupgrade.ops.ReportItem;
import com.jaspersoft.jrsupgrade.ops.Services;
import com.jaspersoft.jrsupgrade.ops.db.FakeJdbcConnector;
import com.jaspersoft.jrsupgrade.ops.db.JdbcException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseCheckConnectorTest {

  @TempDir Path tmp;

  private Services services(FakeServices fake) throws IOException {
    Path drivers = Files.createDirectories(tmp.resolve("drivers"));
    fake.env.put("DB_PW", "pw");
    return fake.yaml(
            """
            database:
              type: postgresql
              url: jdbc:postgresql://db/jrs
              username: jasperdb
              passwordRef: env:DB_PW
              driverDir: %s
            """
                .formatted(drivers.toAbsolutePath().toString().replace('\\', '/')))
        .build();
  }

  @Test
  void should_pass_when_connector_answers_probe_with_a_row() throws IOException {
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"))) {
      FakeJdbcConnector jdbc = new FakeJdbcConnector();
      ReportItem item = DatabaseCheck.check(services(fake), jdbc);
      assertThat(item.status()).isEqualTo(ReportItem.Status.PASS);
      assertThat(item.detail()).contains("FakeDB 1.0").contains("as jasperdb");
      assertThat(jdbc.executed).containsExactly("SELECT 1");
      assertThat(jdbc.connections).containsExactly("jdbc:postgresql://db/jrs as jasperdb");
    }
  }

  @Test
  void should_fail_with_driver_remediation_when_no_driver_matches() throws IOException {
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"))) {
      FakeJdbcConnector jdbc = new FakeJdbcConnector();
      jdbc.connectFailure =
          Optional.of(
              new JdbcException(JdbcException.Kind.NO_MATCHING_DRIVER, "no driver accepts x"));
      ReportItem item = DatabaseCheck.check(services(fake), jdbc);
      assertThat(item.status()).isEqualTo(ReportItem.Status.FAIL);
      assertThat(item.detail()).isEqualTo("no driver accepts x");
      assertThat(item.remediation()).contains("driver jar matches database.type");
    }
  }
}
