package com.jaspersoft.jrsupgrade.ops.customizations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsupgrade.core.state.Customization;
import com.jaspersoft.jrsupgrade.ops.FakeLayout;
import com.jaspersoft.jrsupgrade.ops.FakeServices;
import com.jaspersoft.jrsupgrade.ops.Services;
import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.Change;
import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.Scan;
import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.ScanEntry;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #72: find the files an operator changed in the installed webapp by comparing it with the
 * vendor's untouched copy, instead of registering each one by hand.
 */
class CustomizationScanTest {

  @TempDir Path tmp;

  private FakeServices fake;
  private DefaultCustomizationOperations ops;
  private Path webapp;

  @BeforeEach
  void setUp() throws Exception {
    Path install = FakeLayout.linux(Files.createDirectories(tmp.resolve("jrs")));
    webapp = install.resolve("apache-tomcat").resolve("webapps").resolve("jasperserver-pro");
    fake = FakeServices.in(tmp.resolve("home"));
    fake.platform.realFiles = true;
    fake.yaml(
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
          webappName: jasperserver-pro
          installDir: %s
          tomcatDir: %s
        service:
          kind: manual
        """
            .formatted(slashes(install), slashes(install.resolve("apache-tomcat"))));
    Services services = fake.build();
    ops =
        new DefaultCustomizationOperations(
            services, new SnapshotStore(fake.home, services.platform().files(), fake.clock));
    installed("WEB-INF/classes/jasperserver.properties", "theme=custom\n");
    installed("WEB-INF/lib/foo.jar", "same jar");
    installed("scripts/extra.js", "added by the site");
    installed("WEB-INF/hibernate.properties", "dialect=site\n");
    installed("WEB-INF/logs/jasperserver.log", "log lines");
  }

  @AfterEach
  void tearDown() {
    fake.close();
  }

  private static String slashes(Path p) {
    return p.toAbsolutePath().toString().replace('\\', '/');
  }

  private void installed(String rel, String content) throws Exception {
    Path file = webapp.resolve(rel);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  private static final Map<String, String> VENDOR =
      Map.of(
          "WEB-INF/classes/jasperserver.properties", "theme=default\n",
          "WEB-INF/lib/foo.jar", "same jar",
          "WEB-INF/lib/removed.jar", "gone from the site",
          "WEB-INF/hibernate.properties", "dialect=vendor\n");

  private Path vendorDir() throws Exception {
    Path dir = tmp.resolve("dist").resolve("jasperserver-pro");
    for (Map.Entry<String, String> e : VENDOR.entrySet()) {
      Path file = dir.resolve(e.getKey());
      Files.createDirectories(file.getParent());
      Files.writeString(file, e.getValue(), StandardCharsets.UTF_8);
    }
    return dir;
  }

  private Path vendorWar() throws Exception {
    Path war = Files.createDirectories(tmp.resolve("pkg")).resolve("jasperserver-pro.war");
    try (OutputStream out = Files.newOutputStream(war);
        ZipOutputStream zip = new ZipOutputStream(out)) {
      for (Map.Entry<String, String> e : VENDOR.entrySet()) {
        zip.putNextEntry(new ZipEntry(e.getKey()));
        zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return war;
  }

  private static Map<String, ScanEntry> byPath(Scan scan) {
    return scan.entries().stream()
        .collect(Collectors.toMap(ScanEntry::relativePath, Function.identity()));
  }

  @Test
  void should_list_changed_added_removed_and_installer_files_against_an_unpacked_webapp()
      throws Exception {
    Scan scan = ops.scan(vendorDir().getParent());

    Map<String, ScanEntry> entries = byPath(scan);
    assertThat(entries.get("WEB-INF/classes/jasperserver.properties").change())
        .isEqualTo(Change.CHANGED);
    assertThat(entries.get("scripts/extra.js").change()).isEqualTo(Change.ADDED);
    assertThat(entries.get("WEB-INF/lib/removed.jar").change()).isEqualTo(Change.REMOVED);
    assertThat(entries.get("WEB-INF/hibernate.properties").change()).isEqualTo(Change.INSTALLER);
    assertThat(entries)
        .doesNotContainKey("WEB-INF/lib/foo.jar")
        .doesNotContainKey("WEB-INF/logs/jasperserver.log");
    assertThat(scan.entries()).allMatch(e -> !e.registered());
  }

  @Test
  void should_compare_against_the_war_without_unpacking_it() throws Exception {
    Scan scan = ops.scan(vendorWar());

    Map<String, ScanEntry> entries = byPath(scan);
    assertThat(entries.get("WEB-INF/classes/jasperserver.properties").change())
        .isEqualTo(Change.CHANGED);
    assertThat(entries.get("WEB-INF/lib/removed.jar").change()).isEqualTo(Change.REMOVED);
    assertThat(entries).doesNotContainKey("WEB-INF/lib/foo.jar");
    assertThat(tmp.resolve("pkg")).isDirectoryNotContaining("glob:**/WEB-INF");
  }

  /** Issue #117: since 8.2 the installer-written Hibernate file is under WEB-INF/classes. */
  @Test
  void should_report_the_8_2_hibernate_path_as_installer_written() throws Exception {
    installed("WEB-INF/classes/hibernate.properties", "dialect=site\n");

    Map<String, ScanEntry> entries = byPath(ops.scan(vendorDir().getParent()));

    assertThat(entries.get("WEB-INF/classes/hibernate.properties").change())
        .isEqualTo(Change.INSTALLER);
  }

  private void tomcatFile(String rel) throws Exception {
    Path file = webapp.getParent().getParent().resolve(rel);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "x", StandardCharsets.UTF_8);
  }

  /** Issue #117: what the upgrade guides say to carry over to a new Tomcat. */
  @Test
  void should_list_the_tomcat_side_files_to_carry_over_and_not_the_ones_tomcat_ships()
      throws Exception {
    tomcatFile("bin/setenv.sh");
    tomcatFile("conf/Catalina/localhost/jasperserver-pro.xml");
    tomcatFile("conf/Catalina/localhost/notes.txt");
    tomcatFile("lib/postgresql-42.5.5.jar");
    tomcatFile("lib/catalina.jar");
    tomcatFile("lib/tomcat-coyote.jar");
    tomcatFile("lib/servlet-api.jar");
    tomcatFile("lib/readme.txt");

    var entries = ops.scanTomcat();

    assertThat(entries)
        .extracting(e -> e.kind() + " " + e.relativePath())
        .containsExactly(
            "SETENV bin/setenv.sh",
            "SERVER_XML conf/server.xml",
            "CONTEXT_FRAGMENT conf/Catalina/localhost/jasperserver-pro.xml",
            "LIBRARY lib/postgresql-42.5.5.jar");
    assertThat(entries).allMatch(e -> !e.registered());
  }

  @Test
  void should_mark_a_registered_tomcat_file_as_registered() throws Exception {
    tomcatFile("bin/setenv.sh");
    ops.register(webapp.getParent().getParent().resolve("bin/setenv.sh"));

    assertThat(ops.scanTomcat())
        .filteredOn(e -> e.relativePath().equals("bin/setenv.sh"))
        .allMatch(e -> e.registered());
  }

  @Test
  void should_list_only_the_files_that_exist_when_the_tomcat_has_no_site_files() throws Exception {
    assertThat(ops.scanTomcat())
        .extracting(e -> e.relativePath())
        .containsExactly("conf/server.xml");
  }

  @Test
  void should_know_which_library_names_tomcat_ships() {
    assertThat(TomcatScanner.shippedByTomcat("catalina.jar")).isTrue();
    assertThat(TomcatScanner.shippedByTomcat("tomcat-jdbc.jar")).isTrue();
    assertThat(TomcatScanner.shippedByTomcat("ecj-3.33.0.jar")).isTrue();
    assertThat(TomcatScanner.shippedByTomcat("jakarta.servlet-api.jar")).isTrue();
    assertThat(TomcatScanner.shippedByTomcat("postgresql-42.5.5.jar")).isFalse();
    assertThat(TomcatScanner.shippedByTomcat("jasperreports-fonts.jar")).isFalse();
    assertThat(TomcatScanner.shippedByTomcat("ojdbc11.jar")).isFalse();
  }

  /** The lib directory of the bundled Tomcat 10.1.41 of a real 10.0.0 installation (issue #117). */
  @Test
  void should_report_only_the_three_site_jars_of_a_real_10_0_0_bundle() {
    var jars =
        java.util.List.of(
            "annotations-api.jar",
            "catalina-ant.jar",
            "catalina-ha.jar",
            "catalina-ssi.jar",
            "catalina-storeconfig.jar",
            "catalina-tribes.jar",
            "catalina.jar",
            "ecj-4.27.jar",
            "el-api.jar",
            "iijdbc.jar",
            "jakartaee-migration-1.0.9-shaded.jar",
            "jasper-el.jar",
            "jasper.jar",
            "jaspic-api.jar",
            "jsp-api.jar",
            "mariadb-java-client-2.5.4.jar",
            "postgresql-42.5.5.jar",
            "servlet-api.jar",
            "tomcat-api.jar",
            "tomcat-coyote-ffm.jar",
            "tomcat-coyote.jar",
            "tomcat-dbcp.jar",
            "tomcat-i18n-cs.jar",
            "tomcat-i18n-pt-BR.jar",
            "tomcat-i18n-zh-CN.jar",
            "tomcat-jdbc.jar",
            "tomcat-jni.jar",
            "tomcat-util-scan.jar",
            "tomcat-util.jar",
            "tomcat-websocket.jar",
            "websocket-api.jar",
            "websocket-client-api.jar");

    assertThat(jars.stream().filter(j -> !TomcatScanner.shippedByTomcat(j)))
        .containsExactly("iijdbc.jar", "mariadb-java-client-2.5.4.jar", "postgresql-42.5.5.jar");
  }

  /** Issue #117: scripts/ is an overlay, so registering a file there earns a warning. */
  @Test
  void should_advise_against_per_file_registration_under_scripts_only() {
    assertThat(ops.registrationAdvice(webapp.resolve("scripts/extra.js")))
        .hasValueSatisfying(a -> assertThat(a).contains("jasperserver-ui").contains("overlay"));
    assertThat(ops.registrationAdvice(webapp.resolve("WEB-INF/classes/jasperserver.properties")))
        .isEmpty();
  }

  @Test
  void should_register_changed_files_with_the_vendor_hash_as_original_and_added_ones_as_they_are()
      throws Exception {
    Scan scan = ops.scan(vendorWar());

    var registered = ops.registerScan(scan);

    assertThat(registered)
        .extracting(c -> webapp.relativize(c.path()).toString().replace('\\', '/'))
        .containsExactlyInAnyOrder("WEB-INF/classes/jasperserver.properties", "scripts/extra.js");
    Customization changed =
        registered.stream()
            .filter(c -> c.path().endsWith("jasperserver.properties"))
            .findFirst()
            .orElseThrow();
    assertThat(changed.originalSha256())
        .isEqualTo(
            fake.platform
                .files()
                .sha256(
                    Files.writeString(
                        tmp.resolve("vendor-copy"), "theme=default\n", StandardCharsets.UTF_8)));
    assertThat(ops.scan(vendorWar()).entries())
        .filteredOn(e -> e.change() == Change.CHANGED || e.change() == Change.ADDED)
        .allMatch(ScanEntry::registered);
    assertThat(ops.registerScan(ops.scan(vendorWar()))).as("nothing left to register").isEmpty();
  }

  @Test
  void should_report_installer_files_the_vendor_copy_lacks_as_installer_not_added()
      throws Exception {
    // a real 10.0.0 install has an installer-written keystore.init.properties the war lacks
    installed("WEB-INF/classes/keystore.init.properties", "ks=/opt/jrs\n");

    Map<String, ScanEntry> entries = byPath(ops.scan(vendorWar()));

    assertThat(entries.get("WEB-INF/classes/keystore.init.properties").change())
        .isEqualTo(Change.INSTALLER);
    assertThat(ops.registerScan(ops.scan(vendorWar())))
        .noneMatch(c -> c.path().endsWith("keystore.init.properties"));
  }

  @Test
  void should_ignore_backup_copies_an_operator_left_next_to_an_edited_file() throws Exception {
    installed("WEB-INF/js.config.properties.bak-2026-07-30", "backup");
    installed("WEB-INF/web.xml.bak", "backup");
    installed("WEB-INF/web.xml.orig", "backup");
    installed("scripts/extra.js~", "editor backup");

    Map<String, ScanEntry> entries = byPath(ops.scan(vendorWar()));

    assertThat(entries.keySet())
        .noneMatch(p -> p.contains(".bak") || p.endsWith(".orig") || p.endsWith("~"))
        .contains("scripts/extra.js");
  }

  @Test
  void should_refuse_a_vendor_path_that_holds_no_webapp() throws Exception {
    Path empty = Files.createDirectories(tmp.resolve("nothing"));

    assertThatThrownBy(() -> ops.scan(empty))
        .isInstanceOf(CustomizationException.class)
        .hasMessageContaining("jasperserver-pro");
    assertThat(Optional.of(empty)).isPresent();
  }
}
