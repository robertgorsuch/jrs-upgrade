package com.jaspersoft.jrsupgrade.jrs.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.engine.Sleeper;
import com.jaspersoft.jrsupgrade.core.engine.StepFailure;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.platform.ProcessRunner;
import com.jaspersoft.jrsupgrade.core.platform.ServiceConfig;
import com.jaspersoft.jrsupgrade.core.platform.ServiceController;
import com.jaspersoft.jrsupgrade.core.platform.ServiceController.State;
import com.jaspersoft.jrsupgrade.jrs.CapturingRunner;
import com.jaspersoft.jrsupgrade.jrs.FakeServiceController;
import com.jaspersoft.jrsupgrade.jrs.api.ServerIdentity;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Installation guide p.51 (issue #113): the bundled installer registers {@code
 * jasperreportsPostgreSQL} beside {@code jasperreportsTomcat} and starts the database first. The
 * start step does the same when the host has such a service, and names it when it will not come up.
 */
class CompanionDatabaseTest {

  private static CapturingRunner listing(String... lines) {
    return new CapturingRunner()
        .answer(
            (request, onLine) -> {
              for (String line : lines) {
                onLine.accept(
                    new ProcessRunner.OutputLine(ProcessRunner.OutputLine.Stream.STDOUT, line));
              }
              return new ProcessRunner.Result(0, false, Duration.ofMillis(1));
            });
  }

  @Test
  void should_pick_the_name_that_mentions_both_jasper_and_postgres() {
    assertThat(
            CompanionDatabase.pick(
                List.of(
                    "Spooler",
                    "jasperreportsTomcat",
                    "jasperreportsPostgreSQL",
                    "postgresql-x64-15")))
        .contains("jasperreportsPostgreSQL");
    assertThat(CompanionDatabase.pick(List.of("jasperreportsTomcat", "postgresql-x64-15")))
        .isEmpty();
  }

  @Test
  void should_list_windows_services_and_systemd_units_the_way_init_does() {
    CapturingRunner sc =
        listing(
            "SERVICE_NAME: jasperreportsTomcat",
            "DISPLAY_NAME: JasperReports Server Tomcat",
            "SERVICE_NAME: jasperreportsPostgreSQL");
    assertThat(CompanionDatabase.find(sc, ServiceConfig.Kind.WINDOWS_SERVICE))
        .contains("jasperreportsPostgreSQL");

    CapturingRunner systemctl =
        listing(
            "jasperreportsTomcat.service loaded active running JasperReports Server",
            "jasperreportsPostgreSQL.service loaded inactive dead PostgreSQL");
    assertThat(CompanionDatabase.find(systemctl, ServiceConfig.Kind.SYSTEMD))
        .contains("jasperreportsPostgreSQL.service");
  }

  @Test
  void should_have_no_companion_for_script_and_manual_kinds_or_when_listing_fails() {
    CapturingRunner failing =
        new CapturingRunner()
            .answer(
                (request, onLine) -> {
                  throw new IllegalStateException("no sc.exe");
                });
    assertThat(CompanionDatabase.find(failing, ServiceConfig.Kind.WINDOWS_SERVICE)).isEmpty();
    assertThat(CompanionDatabase.find(listing("x"), ServiceConfig.Kind.CTLSCRIPT)).isEmpty();
    assertThat(CompanionDatabase.find(listing("x"), ServiceConfig.Kind.CATALINA)).isEmpty();
    assertThat(CompanionDatabase.find(listing("x"), ServiceConfig.Kind.MANUAL)).isEmpty();
  }

  /** A runtime whose Tomcat and database controllers are the given fakes. */
  private static ServiceRuntime runtime(
      FakeServiceController tomcat, Optional<ServiceController> database) {
    return new ServiceRuntime() {
      @Override
      public ServiceController controller() {
        return tomcat;
      }

      @Override
      public Optional<ServiceController> databaseController() {
        return database;
      }

      @Override
      public Duration serviceTimeout() {
        return Duration.ofSeconds(1);
      }

      @Override
      public Clock clock() {
        return Clock.systemUTC();
      }

      @Override
      public Sleeper sleeper() {
        return Sleeper.none();
      }

      @Override
      public ServerIdentity refreshIdentity() {
        throw new UnsupportedOperationException();
      }
    };
  }

  @Test
  void should_start_the_database_service_before_tomcat_when_both_are_stopped() {
    FakeServiceController tomcat = new FakeServiceController(State.STOPPED);
    FakeServiceController database = new FakeServiceController(State.STOPPED);

    StepResult result = ServiceSteps.start(runtime(tomcat, Optional.of(database)), () -> false);

    assertThat(result).isInstanceOf(StepResult.Ok.class);
    assertThat(database.state()).isEqualTo(State.RUNNING);
    assertThat(tomcat.state()).isEqualTo(State.RUNNING);
    assertThat(database.calls()).contains("start");
    assertThat(tomcat.calls()).contains("start");
  }

  @Test
  void should_not_touch_a_database_service_that_is_already_running() {
    FakeServiceController tomcat = new FakeServiceController(State.STOPPED);
    FakeServiceController database = new FakeServiceController(State.RUNNING);

    assertThat(ServiceSteps.start(runtime(tomcat, Optional.of(database)), () -> false))
        .isInstanceOf(StepResult.Ok.class);
    assertThat(database.calls()).doesNotContain("start");
    assertThat(tomcat.state()).isEqualTo(State.RUNNING);
  }

  @Test
  void should_fail_recoverably_and_leave_tomcat_alone_when_the_database_will_not_start() {
    FakeServiceController tomcat = new FakeServiceController(State.STOPPED);
    FakeServiceController database = new FakeServiceController(State.STOPPED);
    database.refuse();

    StepResult result = ServiceSteps.start(runtime(tomcat, Optional.of(database)), () -> false);

    assertThat(result).isInstanceOf(StepResult.Failed.class);
    StepFailure failure = ((StepResult.Failed) result).failure();
    assertThat(failure.cause()).contains("database");
    assertThat(tomcat.calls()).doesNotContain("start");
  }
}
