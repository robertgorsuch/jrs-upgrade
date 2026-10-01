package com.jaspersoft.jrsupgrade.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Review §3.4, issue #111: the vendor's troubleshooting files, located and tailed. */
class VendorLogsTest {

  @TempDir Path tmp;

  private Path file(Path p, String content) throws IOException {
    Files.createDirectories(p.getParent());
    return Files.writeString(p, content, StandardCharsets.UTF_8);
  }

  @Test
  void should_locate_the_vendor_files_in_troubleshooting_order_when_all_exist() throws IOException {
    Path install = tmp.resolve("jrs");
    Path tomcat = install.resolve("apache-tomcat");
    Path webapp = tomcat.resolve("webapps").resolve("jasperserver-pro");
    Path buildomatic = install.resolve("buildomatic");
    Path older = file(buildomatic.resolve("logs").resolve("js-export-pro_2026-09-01.log"), "old");
    Path newer =
        file(buildomatic.resolve("logs").resolve("js-upgrade-samedb_2026-09-19.log"), "new");
    Files.setLastModifiedTime(older, FileTime.fromMillis(1_000_000L));
    Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000_000L));
    file(webapp.resolve("WEB-INF").resolve("logs").resolve("jasperserver.log"), "js");
    file(tomcat.resolve("logs").resolve("catalina.out"), "cat");
    file(install.resolve("installation.log"), "inst");
    file(buildomatic.resolve("default_master.properties"), "dbType=postgresql\n");

    List<VendorLogs.Source> sources =
        VendorLogs.locate(
            Optional.of(install),
            Optional.of(tomcat),
            Optional.of(webapp),
            Optional.of(buildomatic));

    assertThat(sources.stream().map(VendorLogs.Source::entry))
        .containsExactly(
            "vendor/buildomatic/js-upgrade-samedb_2026-09-19.log",
            "vendor/jasperserver.log",
            "vendor/catalina.out",
            "vendor/installation.log",
            "vendor/default_master.properties");
    assertThat(sources.get(0).file()).isEqualTo(newer);
    assertThat(sources).filteredOn(VendorLogs.Source::masterProperties).hasSize(1);
  }

  @Test
  void should_take_the_newest_windows_catalina_log_when_there_is_no_catalina_out()
      throws IOException {
    Path tomcat = tmp.resolve("tomcat");
    Path old = file(tomcat.resolve("logs").resolve("catalina.2026-09-18.log"), "a");
    Path recent = file(tomcat.resolve("logs").resolve("catalina.2026-09-19.log"), "b");
    file(tomcat.resolve("logs").resolve("localhost_access_log.2026-09-19.txt"), "x");
    Files.setLastModifiedTime(old, FileTime.fromMillis(1_000_000L));
    Files.setLastModifiedTime(recent, FileTime.fromMillis(2_000_000L));

    assertThat(VendorLogs.catalinaLog(tomcat)).contains(recent);
    assertThat(VendorLogs.catalinaLog(tmp.resolve("missing"))).isEmpty();
  }

  @Test
  void should_yield_nothing_when_no_location_is_configured() {
    assertThat(
            VendorLogs.locate(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()))
        .isEmpty();
  }

  @Test
  void should_stream_only_the_tail_with_a_note_when_the_file_exceeds_the_cap() throws IOException {
    StringBuilder content = new StringBuilder();
    for (int i = 1; i <= 100; i++) {
      content.append("line ").append(i).append('\n');
    }
    Path big = file(tmp.resolve("big.log"), content.toString());
    List<String> lines = new ArrayList<>();

    VendorLogs.tail(big, 60, lines::add);

    assertThat(lines.get(0)).startsWith("... earlier ").contains("bytes omitted");
    // the partial line at the cut is dropped; what follows is whole lines to the end
    assertThat(lines.get(1)).matches("line \\d+");
    assertThat(lines.get(lines.size() - 1)).isEqualTo("line 100");
    assertThat(lines).hasSizeLessThan(12);

    List<String> small = new ArrayList<>();
    VendorLogs.tail(file(tmp.resolve("small.log"), "a\nb\n"), 60, small::add);
    assertThat(small).containsExactly("a", "b");
  }

  @Test
  void should_blank_password_values_and_leave_other_lines_alone() {
    assertThat(VendorLogs.blankPassword("dbPassword=s3cret")).isEqualTo("dbPassword=<blanked>");
    assertThat(VendorLogs.blankPassword("  dbPassword = s3cret")).isEqualTo("dbPassword=<blanked>");
    assertThat(VendorLogs.blankPassword("dbType=postgresql")).isEqualTo("dbType=postgresql");
    assertThat(VendorLogs.blankPassword("# dbPassword=comment")).isEqualTo("# dbPassword=comment");
    assertThat(VendorLogs.blankPassword("")).isEqualTo("");
  }
}
