package com.jaspersoft.jrsupgrade.core.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformDetectionTest {

  private static final String SERVER_XML =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <Server port="8005" shutdown="SHUTDOWN">
        <Service name="Catalina">
          <!--
          <Connector port="8443" protocol="org.apache.coyote.http11.Http11NioProtocol"
                     maxThreads="150" SSLEnabled="true" />
          -->
          <Connector port="8009" protocol="AJP/1.3" redirectPort="8443" />
          <Connector port="8181"
                     protocol="HTTP/1.1"
                     connectionTimeout="20000"
                     redirectPort="8443" />
          <Engine name="Catalina" defaultHost="localhost" />
        </Service>
      </Server>
      """;

  private final Platform platform = Platforms.detect();

  @Test
  void should_detect_layout_when_install_dir_has_pro_webapp(@TempDir Path install)
      throws IOException {
    Path tomcat = fakeInstall(install, "apache-tomcat-9.0.85", "jasperserver-pro", SERVER_XML);

    Optional<TomcatLayout> detected = platform.detectTomcat(install);

    assertThat(detected).isPresent();
    TomcatLayout layout = detected.get();
    assertThat(layout.installDir()).isEqualTo(install.toAbsolutePath().normalize());
    assertThat(layout.tomcatDir()).isEqualTo(tomcat);
    assertThat(layout.webappName()).isEqualTo("jasperserver-pro");
    assertThat(layout.webappDir()).isEqualTo(tomcat.resolve("webapps").resolve("jasperserver-pro"));
    assertThat(layout.webInfLib()).isDirectory();
    assertThat(layout.buildomaticDir()).contains(install.resolve("buildomatic"));
    assertThat(layout.bundledJavaHome()).contains(install.resolve("java"));
    assertThat(layout.httpPort()).contains(8181);
    assertThat(layout.requiresServiceStop(layout.webInfLib().resolve("x.jar"))).isTrue();
    assertThat(layout.requiresServiceStop(tomcat.resolve("conf").resolve("server.xml"))).isFalse();
  }

  @Test
  void should_detect_community_webapp_when_only_jasperserver_exists(@TempDir Path install)
      throws IOException {
    fakeInstall(install, "tomcat", "jasperserver", "<Server/>");

    Optional<TomcatLayout> detected = platform.detectTomcat(install);

    assertThat(detected).isPresent();
    assertThat(detected.get().webappName()).isEqualTo("jasperserver");
    assertThat(detected.get().httpPort()).isEmpty();
  }

  @Test
  void should_return_empty_when_no_webapp_dir_exists(@TempDir Path install) throws IOException {
    Files.createDirectories(install.resolve("apache-tomcat-9").resolve("webapps").resolve("ROOT"));

    assertThat(platform.detectTomcat(install)).isEmpty();
    assertThat(platform.detectTomcat(install.resolve("missing"))).isEmpty();
  }

  /** Field test 3: "-9.0.0" sorted before "-10.0.0" as text, so a JRS 9 was proposed first. */
  @Test
  void should_list_the_highest_version_first_when_globbing_install_dirs(@TempDir Path parent)
      throws IOException {
    for (String name :
        List.of(
            "jasperreports-server-9.0.0",
            "jasperreports-server-10.0.0",
            "jasperreports-server-8.2.0")) {
      Files.createDirectories(parent.resolve(name));
    }

    assertThat(AbstractPlatform.glob(parent, "jasperreports-server*"))
        .extracting(p -> p.getFileName().toString())
        .containsExactly(
            "jasperreports-server-10.0.0",
            "jasperreports-server-9.0.0",
            "jasperreports-server-8.2.0");
  }

  @Test
  void should_pick_the_highest_tomcat_with_a_webapp_when_an_install_holds_several(
      @TempDir Path install) throws IOException {
    fakeInstall(install, "apache-tomcat-9.0.85", "jasperserver-pro", SERVER_XML);
    Path ten = fakeInstall(install, "apache-tomcat-10.1.18", "jasperserver-pro", SERVER_XML);
    Files.createDirectories(install.resolve("apache-tomcat-11.0.0").resolve("webapps"));

    assertThat(platform.detectTomcat(install).map(TomcatLayout::tomcatDir)).contains(ten);
  }

  @Test
  void should_mark_running_installs_and_name_the_blind_spot_when_a_jvm_cannot_be_read(
      @TempDir Path install) throws IOException {
    fakeInstall(install, "apache-tomcat", "jasperserver-pro", SERVER_XML);
    TomcatProcessFinder.TomcatProcess opaque =
        new TomcatProcessFinder.TomcatProcess(
            77, "", Optional.empty(), Optional.empty(), Optional.empty());
    Platform windows =
        new WindowsPlatform(
            Platform.Arch.X86_64,
            new FakeProcessRunner(),
            new DefaultFileOps(),
            OperatorPrompt.nonInteractive(),
            new FakeTomcatProcessFinder(
                List.of(List.of(FakeTomcatProcessFinder.tomcatUnder(install), opaque))));

    InstallScan scan = windows.scanInstallDirs();

    Path tomcat = install.resolve("apache-tomcat").toAbsolutePath().normalize();
    assertThat(scan.candidates()).first().isEqualTo(tomcat);
    assertThat(scan.isRunning(tomcat)).isTrue();
    assertThat(scan.processScanLimit())
        .hasValueSatisfying(l -> assertThat(l).contains("1 Java process").contains("elevated"));
    assertThat(windows.candidateInstallDirs()).isEqualTo(scan.candidates());
  }

  @Test
  void should_list_existing_unique_dirs_when_scanning_candidates() {
    List<Path> candidates = platform.candidateInstallDirs();

    assertThat(candidates).allSatisfy(p -> assertThat(p).isDirectory().isAbsolute());
    assertThat(candidates).doesNotHaveDuplicates();
  }

  @Test
  void should_point_default_home_at_a_jrsupgrade_dir_when_detected() {
    Path home = platform.defaultHome();

    assertThat(home).isAbsolute();
    assertThat(home.getFileName().toString()).isIn("jrs-upgrade", ".jrs-upgrade");
  }

  @Test
  void should_match_host_os_when_detecting() {
    boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    assertThat(platform.os())
        .isEqualTo(windows ? Platform.OsFamily.WINDOWS : Platform.OsFamily.LINUX);
    assertThat(platform.files()).isInstanceOf(windows ? WindowsFileOps.class : LinuxFileOps.class);
    assertThat(platform.processes()).isInstanceOf(DefaultProcessRunner.class);
  }

  @Test
  void should_map_os_and_arch_names_when_classifying() {
    assertThat(Platforms.osFamily("Windows 11")).contains(Platform.OsFamily.WINDOWS);
    assertThat(Platforms.osFamily("Linux")).contains(Platform.OsFamily.LINUX);
    assertThat(Platforms.osFamily("Mac OS X")).as("review 3.1: macOS is not Linux").isEmpty();
    assertThat(Platforms.osFamily("FreeBSD")).isEmpty();
    assertThat(Platforms.osFamily("")).isEmpty();
    assertThat(Platforms.arch("amd64")).isEqualTo(Platform.Arch.X86_64);
    assertThat(Platforms.arch("x86_64")).isEqualTo(Platform.Arch.X86_64);
    assertThat(Platforms.arch("aarch64")).isEqualTo(Platform.Arch.OTHER);
  }

  /**
   * Review finding 3.1: a host outside ADR-0002 is refused with the reason, never treated as Linux.
   */
  @Test
  void should_name_the_unsupported_host_when_os_or_arch_is_outside_adr_0002() {
    assertThat(Platforms.unsupportedReason("Windows 11", "amd64")).isEmpty();
    assertThat(Platforms.unsupportedReason("Linux", "x86_64")).isEmpty();
    assertThat(Platforms.unsupportedReason("Mac OS X", "aarch64"))
        .hasValueSatisfying(
            r -> assertThat(r).contains("Mac OS X").contains("aarch64").contains("ADR-0002"));
    assertThat(Platforms.unsupportedReason("Linux", "aarch64"))
        .hasValueSatisfying(r -> assertThat(r).contains("aarch64"));
    assertThat(Platforms.unsupportedReason("FreeBSD", "amd64"))
        .hasValueSatisfying(r -> assertThat(r).contains("FreeBSD"));
  }

  @Test
  void should_throw_unsupported_platform_when_detecting_on_a_foreign_host() {
    assertThatThrownBy(
            () -> Platforms.detect("Mac OS X", "aarch64", OperatorPrompt.nonInteractive()))
        .isInstanceOf(UnsupportedPlatformException.class)
        .hasMessageContaining("Mac OS X")
        .hasMessageContaining("aarch64");
  }

  @Test
  void should_parse_install_location_when_reg_query_line_matches() {
    assertThat(
            WindowsPlatform.parseInstallLocation(
                "    InstallLocation    REG_SZ    C:\\Jaspersoft\\jasperreports-server-8.2.0"))
        .contains(Path.of("C:\\Jaspersoft\\jasperreports-server-8.2.0"));
    assertThat(WindowsPlatform.parseInstallLocation("    DisplayName    REG_SZ    JasperReports"))
        .isEmpty();
  }

  @Test
  void should_pick_controller_by_kind_when_building_services(@TempDir Path dir) {
    Platform testable =
        Platforms.forTesting(
            Platform.OsFamily.LINUX,
            Platform.Arch.X86_64,
            new FakeProcessRunner(),
            new DefaultFileOps(),
            OperatorPrompt.nonInteractive());
    Duration t = Duration.ofSeconds(30);

    assertThat(
            testable.services(
                new ServiceConfig(
                    ServiceConfig.Kind.WINDOWS_SERVICE,
                    Optional.of("jasperreportsTomcat"),
                    Optional.empty(),
                    t)))
        .isInstanceOf(WindowsServiceController.class);
    assertThat(
            testable.services(
                new ServiceConfig(
                    ServiceConfig.Kind.SYSTEMD, Optional.of("jasperserver"), Optional.empty(), t)))
        .isInstanceOf(SystemdServiceController.class);
    assertThat(
            testable.services(
                new ServiceConfig(
                    ServiceConfig.Kind.CTLSCRIPT,
                    Optional.empty(),
                    Optional.of(dir.resolve("ctlscript.sh")),
                    t)))
        .isInstanceOf(ScriptServiceController.class);
    assertThat(
            testable.services(
                new ServiceConfig(
                    ServiceConfig.Kind.CATALINA,
                    Optional.empty(),
                    Optional.of(dir.resolve("bin").resolve("catalina.sh")),
                    t)))
        .isInstanceOf(ScriptServiceController.class);
    assertThat(
            testable.services(
                new ServiceConfig(
                    ServiceConfig.Kind.MANUAL, Optional.empty(), Optional.empty(), t)))
        .isInstanceOf(ManualServiceController.class);
  }

  static Path fakeInstall(Path install, String tomcatName, String webapp, String serverXml)
      throws IOException {
    Path tomcat = install.resolve(tomcatName);
    Files.createDirectories(
        tomcat.resolve("webapps").resolve(webapp).resolve("WEB-INF").resolve("lib"));
    Files.createDirectories(tomcat.resolve("conf"));
    Files.writeString(
        tomcat.resolve("conf").resolve("server.xml"), serverXml, StandardCharsets.UTF_8);
    Files.createDirectories(install.resolve("buildomatic"));
    Path javaBin = install.resolve("java").resolve("bin");
    Files.createDirectories(javaBin);
    Files.writeString(javaBin.resolve("java"), "", StandardCharsets.UTF_8);
    Files.writeString(javaBin.resolve("java.exe"), "", StandardCharsets.UTF_8);
    return tomcat.toAbsolutePath().normalize();
  }
}
