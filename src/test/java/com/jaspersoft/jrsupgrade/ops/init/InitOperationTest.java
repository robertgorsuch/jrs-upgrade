package com.jaspersoft.jrsupgrade.ops.init;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.config.ConfigLoader;
import com.jaspersoft.jrsupgrade.core.platform.LinuxInit;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.platform.ServiceConfig;
import com.jaspersoft.jrsupgrade.ops.FakeLayout;
import com.jaspersoft.jrsupgrade.ops.FakePlatform;
import com.jaspersoft.jrsupgrade.ops.FakeServices;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InitOperationTest {

  @TempDir Path tmp;

  @Test
  void should_detect_linux_layout_with_ctlscript_when_no_systemd_unit_matches() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.LINUX)) {
      fake.platform.on(
          List.of("systemctl"), FakePlatform.Response.ok("ssh.service loaded active running"));
      InitOperation init = new InitOperation(fake.build(), () -> Optional.of("jasperserver"));

      InitReport report = init.detect(Optional.of(install));
      Config config = init.toConfig(report);

      assertThat(report.detectedInstall()).isTrue();
      assertThat(config.server().baseUrl())
          .contains(URI.create("http://localhost:8081/jasperserver-pro"));
      assertThat(config.server().webappName()).contains(Config.WebappName.JASPERSERVER_PRO);
      assertThat(config.server().installDir()).contains(install.toAbsolutePath().normalize());
      assertThat(config.server().tomcatDir())
          .contains(install.toAbsolutePath().normalize().resolve("apache-tomcat"));
      assertThat(config.server().runAsUser()).contains("jasperserver");
      assertThat(config.service().kind()).contains(ServiceConfig.Kind.CTLSCRIPT);
      assertThat(config.service().scriptPath())
          .contains(install.toAbsolutePath().normalize().resolve("ctlscript.sh"));
      // #73: shown with their source but not copied; jrs-upgrade reads them from buildomatic
      assertThat(config.database().type()).isEmpty();
      assertThat(config.database().url()).isEmpty();
      assertThat(config.database().username()).isEmpty();
      assertThat(report.values())
          .anyMatch(
              v ->
                  v.key().equals("database.url")
                      && v.value().equals("jdbc:postgresql://db.example.internal:5433/jasperserver")
                      && v.source().contains("not copied"))
          .anyMatch(v -> v.key().equals("database.username") && v.value().equals("jasperdb"));
      assertThat(config.database().passwordRef().map(r -> r.render()))
          .contains("env:JRS_DB_PASSWORD");
      assertThat(config.vendor().javaHome())
          .contains(install.toAbsolutePath().normalize().resolve("java"));
      assertThat(report.values())
          .anyMatch(v -> v.key().equals("server.baseUrl") && v.source().contains("conf/server.xml"))
          .anyMatch(
              v ->
                  v.key().equals("database.type")
                      && v.source().contains("default_master.properties"))
          // #65: the report says this Java is for buildomatic, not the one jrs-upgrade runs on
          .anyMatch(
              v ->
                  v.key().equals("vendor.javaHome")
                      && v.source().contains("buildomatic")
                      && v.source().contains("not the Java jrs-upgrade runs on"))
          .noneMatch(v -> v.value().contains("Sup3rSecret"));
    }
  }

  /**
   * Issue #59: on the commercial edition jasperadmin administers one organisation only, and a
   * full-server export needs superuser.
   */
  @Test
  void should_propose_superuser_when_the_installation_is_the_commercial_edition() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.LINUX)) {
      InitOperation init = new InitOperation(fake.build(), Optional::empty);

      InitReport report = init.detect(Optional.of(install));

      assertThat(report.config().server().auth().username()).contains("superuser");
      assertThat(report.values())
          .anyMatch(
              v ->
                  v.key().equals("server.auth.username")
                      && v.value().equals("superuser")
                      && v.source().contains("commercial edition"));
    }
  }

  @Test
  void should_propose_jasperadmin_when_the_installation_is_the_community_edition()
      throws Exception {
    Path install = FakeLayout.linuxCommunity(tmp.resolve("jrs"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.LINUX)) {
      InitOperation init = new InitOperation(fake.build(), Optional::empty);

      InitReport report = init.detect(Optional.of(install));

      assertThat(report.config().server().auth().username()).contains("jasperadmin");
      assertThat(report.values())
          .anyMatch(
              v ->
                  v.key().equals("server.auth.username")
                      && v.value().equals("jasperadmin")
                      && v.source().contains("community edition"));
    }
  }

  /** Review finding 3.2: a supervised host is named and systemctl is never probed. */
  @Test
  void should_name_the_supervisor_and_skip_systemctl_when_process_one_is_not_systemd()
      throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    LinuxInit.Detected supervisord =
        new LinuxInit.Detected(
            LinuxInit.Kind.SUPERVISOR, Optional.of("supervisord"), "supervisord is process 1");
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.LINUX)) {
      fake.platform.on(
          List.of("systemctl"),
          FakePlatform.Response.ok("jasperreportsTomcat.service loaded active running"));
      InitOperation init =
          new InitOperation(fake.build(), () -> Optional.of("jasperserver"), () -> supervisord);

      InitReport report = init.detect(Optional.of(install));
      Config config = init.toConfig(report);

      assertThat(config.service().kind()).contains(ServiceConfig.Kind.CTLSCRIPT);
      assertThat(report.values())
          .anyMatch(
              v ->
                  v.key().equals(InitOperation.SERVICE_MANAGER)
                      && v.value().equals("supervisor")
                      && v.source().contains("supervisord"))
          .anyMatch(
              v ->
                  v.key().equals(InitOperation.SERVICE_MANAGER)
                      && v.value().equals("warning")
                      && v.source().contains("may restart the server mid-run"));
    }
  }

  /** Review finding 3.2: an unreadable /proc still lets the systemd probe decide. */
  @Test
  void should_record_no_service_manager_when_process_one_cannot_be_read() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    LinuxInit.Detected unknown =
        new LinuxInit.Detected(
            LinuxInit.Kind.UNKNOWN,
            Optional.empty(),
            "cannot read /proc/1/comm; no service" + " manager detected");
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.LINUX)) {
      fake.platform.on(
          List.of("systemctl"),
          FakePlatform.Response.ok("jasperreportsTomcat.service loaded active running"));
      InitOperation init =
          new InitOperation(fake.build(), () -> Optional.of("jasperserver"), () -> unknown);

      InitReport report = init.detect(Optional.of(install));
      Config config = init.toConfig(report);

      assertThat(config.service().kind()).contains(ServiceConfig.Kind.SYSTEMD);
      assertThat(report.values())
          .anyMatch(
              v ->
                  v.key().equals(InitOperation.SERVICE_MANAGER)
                      && v.source().contains("no service manager detected"))
          .noneMatch(
              v -> v.key().equals(InitOperation.SERVICE_MANAGER) && v.value().equals("warning"));
    }
  }

  @Test
  void should_detect_windows_service_when_sc_query_lists_a_jasper_service() throws Exception {
    Path install = FakeLayout.windows(tmp.resolve("jrs"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.WINDOWS)) {
      fake.platform.on(
          List.of("sc.exe", "query"),
          FakePlatform.Response.ok(
              "SERVICE_NAME: Spooler",
              "DISPLAY_NAME: Print Spooler",
              "SERVICE_NAME: jasperreportsTomcat",
              "DISPLAY_NAME: JasperReports Server Tomcat"));
      fake.platform.on(
          List.of("sc.exe", "qc"),
          FakePlatform.Response.ok(
              "SERVICE_NAME: jasperreportsTomcat",
              "        BINARY_PATH_NAME   : \""
                  + install.resolve("tomcat").resolve("bin").resolve("tomcat9.exe")
                  + "\" //RS//jasperreportsTomcat"));
      InitOperation init = new InitOperation(fake.build(), Optional::empty);

      InitReport report = init.detect(Optional.of(install));
      Config config = report.config();

      assertThat(config.service().kind()).contains(ServiceConfig.Kind.WINDOWS_SERVICE);
      assertThat(config.service().name()).contains("jasperreportsTomcat");
      assertThat(report.values())
          .filteredOn(v -> v.key().equals("service.name"))
          .singleElement()
          .satisfies(v -> assertThat(v.source()).contains("its executable is under"));
      assertThat(config.server().baseUrl())
          .contains(URI.create("http://localhost:8080/jasperserver-pro"));
      assertThat(config.server().tomcatDir())
          .contains(install.toAbsolutePath().normalize().resolve("tomcat"));
      assertThat(config.server().runAsUser()).isEmpty();
      assertThat(fake.platform.invocations).anyMatch(c -> c.get(0).equals("sc.exe"));
    }
  }

  /**
   * A second Tomcat on the host: the service picked by name alone is kept, but the report says its
   * executable was not confirmed under the detected Tomcat directory (assessment item P4).
   */
  @Test
  void should_flag_a_service_chosen_by_name_when_its_executable_is_elsewhere() throws Exception {
    Path install = FakeLayout.windows(tmp.resolve("jrs"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.WINDOWS)) {
      fake.platform.on(
          List.of("sc.exe", "query"),
          FakePlatform.Response.ok("SERVICE_NAME: Tomcat9", "DISPLAY_NAME: Apache Tomcat 9"));
      fake.platform.on(
          List.of("sc.exe", "qc"),
          FakePlatform.Response.ok(
              "SERVICE_NAME: Tomcat9",
              "        BINARY_PATH_NAME   : \"C:\\other\\tomcat\\bin\\tomcat9.exe\" //RS//Tomcat9"));
      InitOperation init = new InitOperation(fake.build(), Optional::empty);

      InitReport report = init.detect(Optional.of(install));

      assertThat(report.config().service().name()).contains("Tomcat9");
      assertThat(report.values())
          .filteredOn(v -> v.key().equals("service.name"))
          .singleElement()
          .satisfies(v -> assertThat(v.source()).contains("chosen by name only"));
    }
  }

  @Test
  void should_fall_back_to_manual_when_no_service_or_script_exists() throws Exception {
    Path install = tmp.resolve("bare");
    Files.createDirectories(install.resolve("tomcat").resolve("webapps").resolve("jasperserver"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.LINUX)) {
      InitOperation init = new InitOperation(fake.build(), Optional::empty);

      InitReport report = init.detect(Optional.of(install));
      Config config = report.config();

      assertThat(config.service().kind()).contains(ServiceConfig.Kind.MANUAL);
      assertThat(report.values())
          .filteredOn(v -> v.key().equals("service.kind"))
          .singleElement()
          .satisfies(v -> assertThat(v.source()).contains("ask you to stop and start"));
      assertThat(config.server().webappName()).contains(Config.WebappName.JASPERSERVER);
      assertThat(config.database().type()).isEmpty();
      assertThat(config.server().baseUrl())
          .contains(URI.create("http://localhost:8080/jasperserver"));
    }
  }

  /**
   * A WAR + buildomatic install has no vendor-registered service; its Tomcat's own catalina script
   * is what jrs-upgrade runs, and the report says so in words an operator recognises (issue #147).
   */
  @Test
  void should_propose_the_catalina_script_when_a_war_install_has_no_service() throws Exception {
    Path install = tmp.resolve("war");
    Path tomcat = install.resolve("tomcat");
    Files.createDirectories(tomcat.resolve("webapps").resolve("jasperserver-pro"));
    Files.createDirectories(tomcat.resolve("bin"));
    Files.writeString(tomcat.resolve("bin").resolve("catalina.sh"), "#!/bin/sh\n");
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.LINUX)) {
      InitOperation init = new InitOperation(fake.build(), Optional::empty);

      InitReport report = init.detect(Optional.of(install));

      assertThat(report.config().service().kind()).contains(ServiceConfig.Kind.CATALINA);
      assertThat(report.values())
          .filteredOn(v -> v.key().equals("service.kind"))
          .singleElement()
          .satisfies(
              v ->
                  assertThat(v.source())
                      .contains("no registered service")
                      .contains("WAR")
                      .contains("jrs-upgrade runs catalina.sh"));
    }
  }

  @Test
  void should_report_nothing_detected_when_hint_has_no_layout() throws Exception {
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"))) {
      InitOperation init = new InitOperation(fake.build(), Optional::empty);

      InitReport report = init.detect(Optional.of(tmp.resolve("missing")));

      assertThat(report.detectedInstall()).isFalse();
      assertThat(report.values()).anyMatch(v -> v.value().equals("(not detected)"));
    }
  }

  @Test
  void should_use_platform_candidates_when_no_hint_given() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"))) {
      fake.platform.candidates.add(tmp.resolve("not-an-install"));
      fake.platform.candidates.add(install);
      InitOperation init = new InitOperation(fake.build(), Optional::empty);

      InitReport report = init.detect(Optional.empty());

      assertThat(report.detectedInstall()).isTrue();
      assertThat(report.values())
          .anyMatch(v -> v.key().equals("server.installDir") && v.source().contains("candidate"));
    }
  }

  /** An installation whose webapp jars state {@code version}, as a real WEB-INF/lib does. */
  private Path installOf(String dir, String version) throws Exception {
    Path install = FakeLayout.linux(tmp.resolve(dir));
    Files.writeString(
        install
            .resolve("apache-tomcat/webapps/jasperserver-pro/WEB-INF/lib")
            .resolve("jasperserver-api-common-" + version + ".jar"),
        "");
    return install.toAbsolutePath().normalize();
  }

  /** Field test 3: a JRS 9 listed first was proposed while the JRS 10 beside it was running. */
  @Test
  void should_propose_the_running_installation_when_another_is_listed_first() throws Exception {
    Path nine = installOf("jrs9", "9.0.0");
    Path ten = installOf("jrs10", "10.0.0");
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.LINUX)) {
      fake.platform.candidates.addAll(List.of(nine, ten));
      fake.platform.running.add(ten);
      InitOperation init = new InitOperation(fake.build(), Optional::empty);

      InitReport report = init.detect(Optional.empty());

      assertThat(report.config().server().installDir()).contains(ten);
      assertThat(report.candidates())
          .extracting(c -> c.layout().installDir(), InitReport.Candidate::running)
          .containsExactly(tuple(ten, true), tuple(nine, false));
      assertThat(report.candidates().get(0).chosen()).isTrue();
      assertThat(report.candidates().get(0).version()).contains("10.0.0");
      assertThat(report.candidates().get(0).edition()).isEqualTo("commercial");
      assertThat(report.values())
          .anyMatch(
              v ->
                  v.key().equals("server.installDir")
                      && v.source().contains("running")
                      && v.source().contains("2"));
    }
  }

  @Test
  void should_prefer_a_running_older_installation_when_a_newer_one_is_stopped() throws Exception {
    Path nine = installOf("jrs9", "9.0.0");
    Path ten = installOf("jrs10", "10.0.0");
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.LINUX)) {
      fake.platform.candidates.addAll(List.of(ten, nine));
      fake.platform.running.add(nine);
      InitOperation init = new InitOperation(fake.build(), Optional::empty);

      InitReport report = init.detect(Optional.empty());

      assertThat(report.config().server().installDir()).contains(nine);
    }
  }

  @Test
  void should_rank_10_above_9_by_number_when_neither_installation_runs() throws Exception {
    // path order and text order of the versions would both put 9.0.0 first
    Path nine = installOf("jrs-a", "9.0.0");
    Path ten = installOf("jrs-b", "10.0.0");
    Path unknown = FakeLayout.linux(tmp.resolve("jrs-0")).toAbsolutePath().normalize();
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.LINUX)) {
      fake.platform.candidates.addAll(List.of(unknown, nine, ten));
      InitOperation init = new InitOperation(fake.build(), Optional::empty);

      InitReport report = init.detect(Optional.empty());

      assertThat(report.candidates())
          .extracting(c -> c.layout().installDir())
          .containsExactly(ten, nine, unknown);
      assertThat(report.config().server().installDir()).contains(ten);
    }
  }

  /** A running Tomcat is found at its Tomcat dir, the search at the install root: one entry. */
  @Test
  void should_list_one_installation_when_the_root_and_its_tomcat_are_both_candidates()
      throws Exception {
    Path install = installOf("jrs", "10.0.0");
    Path tomcat = install.resolve("apache-tomcat");
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.LINUX)) {
      fake.platform.candidates.addAll(List.of(tomcat, install));
      fake.platform.running.add(tomcat);
      InitOperation init = new InitOperation(fake.build(), Optional::empty);

      InitReport report = init.detect(Optional.empty());

      assertThat(report.candidates()).hasSize(1);
      assertThat(report.candidates().get(0).layout().installDir()).isEqualTo(install);
      assertThat(report.candidates().get(0).running()).isTrue();
    }
  }

  @Test
  void should_detect_the_picked_installation_when_the_operator_chooses_another() throws Exception {
    Path nine = installOf("jrs9", "9.0.0");
    Path ten = installOf("jrs10", "10.0.0");
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.LINUX)) {
      fake.platform.candidates.addAll(List.of(nine, ten));
      InitOperation init = new InitOperation(fake.build(), Optional::empty);
      InitReport first = init.detect(Optional.empty());

      InitReport picked = init.choose(first, 1, Optional.empty());

      assertThat(picked.config().server().installDir()).contains(nine);
      assertThat(picked.candidates())
          .extracting(c -> c.layout().installDir(), InitReport.Candidate::chosen)
          .containsExactly(tuple(ten, false), tuple(nine, true));
      assertThat(picked.values())
          .anyMatch(v -> v.key().equals("server.installDir") && v.source().contains("chosen"));
      assertThatThrownBy(() -> init.choose(first, 2, Optional.empty()))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void should_pass_on_what_the_process_scan_could_not_see_when_the_platform_reports_it()
      throws Exception {
    Path install = installOf("jrs", "10.0.0");
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"), Platform.OsFamily.WINDOWS)) {
      fake.platform.candidates.add(install);
      fake.platform.processScanLimit =
          Optional.of("1 Java process whose command line this account cannot read");
      InitOperation init = new InitOperation(fake.build(), Optional::empty);

      assertThat(init.detect(Optional.empty()).notes())
          .singleElement()
          .satisfies(n -> assertThat(n).contains("cannot read"));
      // with --install-dir there is no search, so nothing to warn about
      assertThat(init.detect(Optional.of(install)).notes()).isEmpty();
      assertThat(init.detect(Optional.of(install)).candidates()).isEmpty();
    }
  }

  @Test
  void should_write_a_schema_valid_config_and_refuse_to_overwrite_without_force() throws Exception {
    Path install = FakeLayout.linux(tmp.resolve("jrs"));
    try (FakeServices fake = FakeServices.in(tmp.resolve("home"))) {
      InitOperation init = new InitOperation(fake.build(), Optional::empty);
      Config config = init.detect(Optional.of(install)).config();

      Path written = init.write(config, false);

      String yaml = Files.readString(written, StandardCharsets.UTF_8);
      assertThat(yaml).contains("webappName: jasperserver-pro").doesNotContain("Sup3rSecret");
      Config reloaded = new ConfigLoader().load(fake.home, Map.of(), Map.of());
      assertThat(reloaded).isEqualTo(config);
      assertThatThrownBy(() -> init.write(config, false))
          .isInstanceOf(FileAlreadyExistsException.class);
      assertThat(init.write(config, true)).isEqualTo(written);
    }
  }

  @Test
  void should_prefer_jasper_names_over_tomcat_names_when_picking_a_service() {
    assertThat(InitOperation.pickServiceName(List.of("Tomcat9", "jasperreportsTomcat")))
        .contains("jasperreportsTomcat");
    assertThat(InitOperation.pickServiceName(List.of("Spooler", "Tomcat9"))).contains("Tomcat9");
    assertThat(InitOperation.pickServiceName(List.of("Spooler"))).isEmpty();
  }
}
