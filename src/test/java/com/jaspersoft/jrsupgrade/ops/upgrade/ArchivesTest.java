package com.jaspersoft.jrsupgrade.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.engine.CancellationToken;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ArchivesTest {

  @TempDir Path tmp;

  @ParameterizedTest
  @EnumSource(Platform.OsFamily.class)
  void should_round_trip_a_directory_tree_when_creating_and_extracting(Platform.OsFamily os)
      throws Exception {
    Path source = tmp.resolve("src");
    Files.createDirectories(source.resolve("WEB-INF").resolve("lib"));
    Files.createDirectories(source.resolve("empty"));
    Files.writeString(source.resolve("a.txt"), "alpha", StandardCharsets.UTF_8);
    Files.writeString(
        source.resolve("WEB-INF").resolve("lib").resolve("x.jar"), "jar", StandardCharsets.UTF_8);
    Path archive = tmp.resolve("out").resolve("webapp" + Archives.extension(os));

    long written = Archives.create(os, source, archive, new CancellationToken());
    Path target = tmp.resolve("restored");
    long read = Archives.extract(os, archive, target, new CancellationToken());

    assertThat(archive).exists();
    assertThat(written).isEqualTo(5);
    assertThat(read).isEqualTo(5);
    assertThat(target.resolve("a.txt")).hasContent("alpha");
    assertThat(target.resolve("WEB-INF").resolve("lib").resolve("x.jar")).hasContent("jar");
    assertThat(target.resolve("empty")).isDirectory();
    List<String> names = Archives.entries(os, archive);
    assertThat(names).contains("a.txt", "WEB-INF/lib/x.jar", "empty/");
    assertThat(names).allSatisfy(n -> assertThat(n).doesNotContain("\\"));
  }

  /**
   * Assessment item O3: names holding ".." or ":" inside a segment are legal on Linux and were
   * archived fine, then refused on extract by a substring check, so those backups could not be
   * restored. Only a ".." segment or an absolute root is a reason to refuse.
   */
  @ParameterizedTest
  @EnumSource(Platform.OsFamily.class)
  void should_round_trip_a_name_holding_dots_inside_a_segment(Platform.OsFamily os)
      throws Exception {
    Path source = tmp.resolve("src");
    Files.createDirectories(source.resolve("lib..old"));
    Files.writeString(
        source.resolve("lib..old").resolve("a..b.txt"), "dots", StandardCharsets.UTF_8);
    Path archive = tmp.resolve("out").resolve("dots" + Archives.extension(os));

    Archives.create(os, source, archive, new CancellationToken());
    Path target = tmp.resolve("restored");
    Archives.extract(os, archive, target, new CancellationToken());

    assertThat(target.resolve("lib..old").resolve("a..b.txt")).hasContent("dots");
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void should_round_trip_a_name_holding_a_colon() throws Exception {
    Platform.OsFamily os = Platform.OsFamily.LINUX;
    Path source = tmp.resolve("src");
    Files.createDirectories(source);
    Files.writeString(source.resolve("report:2026.txt"), "colon", StandardCharsets.UTF_8);
    Path archive = tmp.resolve("colon.tar.gz");

    Archives.create(os, source, archive, new CancellationToken());
    Path target = tmp.resolve("restored");
    Archives.extract(os, archive, target, new CancellationToken());

    assertThat(target.resolve("report:2026.txt")).hasContent("colon");
  }

  @Test
  void should_refuse_an_entry_with_a_parent_segment_or_an_absolute_root_when_extracting()
      throws Exception {
    for (String name : List.of("../evil.txt", "a/../../evil.txt", "/etc/evil.txt", "C:/evil.txt")) {
      Path archive = tmp.resolve("bad-" + Integer.toHexString(name.hashCode()) + ".tar.gz");
      writeTar(archive, e -> e.file(name, "x"));
      Path target = tmp.resolve("restored-" + Integer.toHexString(name.hashCode()));
      assertThatThrownBy(
              () ->
                  Archives.extract(
                      Platform.OsFamily.LINUX, archive, target, new CancellationToken()))
          .as(name)
          .isInstanceOf(IOException.class)
          .hasMessageContaining(name);
    }
  }

  /** Assessment item O3: a link target must stay inside the tree, on extract as on create. */
  @Test
  void should_refuse_a_link_whose_target_is_absolute_or_leaves_the_tree_when_extracting()
      throws Exception {
    for (String linkTarget :
        List.of("/etc/passwd", "../../outside.txt", "lib/../../../outside.txt")) {
      Path archive = tmp.resolve("link-" + Integer.toHexString(linkTarget.hashCode()) + ".tar.gz");
      writeTar(archive, e -> e.link("lib/evil", linkTarget));
      Path target = tmp.resolve("restored-link-" + Integer.toHexString(linkTarget.hashCode()));
      assertThatThrownBy(
              () ->
                  Archives.extract(
                      Platform.OsFamily.LINUX, archive, target, new CancellationToken()))
          .as(linkTarget)
          .isInstanceOf(IOException.class)
          .hasMessageContaining("link target");
      assertThat(target.resolve("lib").resolve("evil")).doesNotExist();
    }
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void should_refuse_to_archive_a_link_whose_target_is_absolute_or_leaves_the_tree()
      throws Exception {
    Platform.OsFamily os = Platform.OsFamily.LINUX;
    Path outside = Files.writeString(tmp.resolve("outside.txt"), "x", StandardCharsets.UTF_8);
    for (Path linkTarget : List.of(outside.toAbsolutePath(), Path.of("../outside.txt"))) {
      Path source = tmp.resolve("src-" + Integer.toHexString(linkTarget.toString().hashCode()));
      Files.createDirectories(source);
      Files.createSymbolicLink(source.resolve("evil"), linkTarget);
      Path archive =
          tmp.resolve("out-" + Integer.toHexString(linkTarget.toString().hashCode()) + ".tar.gz");
      assertThatThrownBy(() -> Archives.create(os, source, archive, new CancellationToken()))
          .as(linkTarget.toString())
          .isInstanceOf(IOException.class)
          .hasMessageContaining("link target");
      assertThat(archive).doesNotExist();
    }
  }

  /** Writes a gzipped tar with whatever entries the writer adds; names are taken verbatim. */
  private static void writeTar(Path archive, Consumer<TarWriter> entries) throws IOException {
    try (OutputStream raw = Files.newOutputStream(archive);
        GZIPOutputStream gz = new GZIPOutputStream(raw);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gz)) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      entries.accept(
          new TarWriter() {
            @Override
            public void file(String name, String content) {
              try {
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry entry = new TarArchiveEntry(name, true);
                entry.setSize(bytes.length);
                tar.putArchiveEntry(entry);
                tar.write(bytes);
                tar.closeArchiveEntry();
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            }

            @Override
            public void link(String name, String linkTarget) {
              try {
                TarArchiveEntry entry = new TarArchiveEntry(name, TarConstants.LF_SYMLINK, true);
                entry.setLinkName(linkTarget);
                tar.putArchiveEntry(entry);
                tar.closeArchiveEntry();
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            }
          });
    }
  }

  interface TarWriter {
    void file(String name, String content);

    void link(String name, String linkTarget);
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void should_restore_a_symbolic_link_as_a_link_when_round_tripping_a_tree() throws Exception {
    Platform.OsFamily os = Platform.OsFamily.LINUX;
    Path source = tmp.resolve("src");
    Files.createDirectories(source.resolve("WEB-INF").resolve("lib"));
    Path real = source.resolve("WEB-INF").resolve("lib").resolve("real.jar");
    Files.writeString(real, "jar", StandardCharsets.UTF_8);
    Files.createSymbolicLink(
        source.resolve("WEB-INF").resolve("lib").resolve("linked.jar"), Path.of("real.jar"));
    Files.createSymbolicLink(source.resolve("shortcut"), Path.of("WEB-INF/lib"));
    Path archive = tmp.resolve("webapp.tar.gz");

    Archives.create(os, source, archive, new CancellationToken());
    Path target = tmp.resolve("restored");
    Archives.extract(os, archive, target, new CancellationToken());

    Path linked = target.resolve("WEB-INF").resolve("lib").resolve("linked.jar");
    assertThat(Files.isSymbolicLink(linked)).isTrue();
    assertThat(Files.readSymbolicLink(linked)).isEqualTo(Path.of("real.jar"));
    assertThat(linked).hasContent("jar");
    Path shortcut = target.resolve("shortcut");
    assertThat(Files.isSymbolicLink(shortcut)).isTrue();
    assertThat(Files.readSymbolicLink(shortcut)).isEqualTo(Path.of("WEB-INF/lib"));
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void should_restore_permissions_of_files_and_directories_when_extracting() throws Exception {
    Platform.OsFamily os = Platform.OsFamily.LINUX;
    Path source = tmp.resolve("src");
    Path locked = source.resolve("locked");
    Files.createDirectories(locked);
    Path script = source.resolve("run.sh");
    Files.writeString(script, "#!/bin/sh\n", StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-x---"));
    Files.writeString(locked.resolve("inner.txt"), "inner", StandardCharsets.UTF_8);
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
    Path archive = tmp.resolve("webapp.tar.gz");

    Archives.create(os, source, archive, new CancellationToken());
    Path target = tmp.resolve("restored");
    Archives.extract(os, archive, target, new CancellationToken());

    assertThat(
            PosixFilePermissions.toString(Files.getPosixFilePermissions(target.resolve("run.sh"))))
        .isEqualTo("rwxr-x---");
    assertThat(
            PosixFilePermissions.toString(Files.getPosixFilePermissions(target.resolve("locked"))))
        .as("a directory restored read-only first would have nowhere to put its children")
        .isEqualTo("r-xr-xr-x");
    assertThat(target.resolve("locked").resolve("inner.txt")).hasContent("inner");
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void should_refuse_to_create_a_zip_when_the_tree_holds_a_symbolic_link() throws Exception {
    Path source = tmp.resolve("src");
    Files.createDirectories(source);
    Files.writeString(source.resolve("real.jar"), "jar", StandardCharsets.UTF_8);
    Files.createSymbolicLink(source.resolve("linked.jar"), Path.of("real.jar"));
    Path archive = tmp.resolve("webapp.zip");

    assertThatThrownBy(
            () ->
                Archives.create(
                    Platform.OsFamily.WINDOWS, source, archive, new CancellationToken()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("linked.jar")
        .hasMessageContaining("cannot carry a link");
  }

  @Test
  void should_move_current_tree_aside_and_undo_when_restoring() throws Exception {
    Platform.OsFamily os = Platform.OsFamily.WINDOWS;
    Path source = tmp.resolve("src");
    Files.createDirectories(source);
    Files.writeString(source.resolve("v.txt"), "old", StandardCharsets.UTF_8);
    Path archive = tmp.resolve("webapp.zip");
    Archives.create(os, source, archive, new CancellationToken());
    Files.writeString(source.resolve("v.txt"), "new", StandardCharsets.UTF_8);
    Files.writeString(source.resolve("extra.txt"), "new file", StandardCharsets.UTF_8);
    Path aside = tmp.resolve("run").resolve("aside").resolve("src");

    PointB.restoreDir(os, archive, source, aside, new CancellationToken());

    assertThat(source.resolve("v.txt")).hasContent("old");
    assertThat(source.resolve("extra.txt")).doesNotExist();
    assertThat(aside.resolve("v.txt")).hasContent("new");
    assertThat(aside.resolve("extra.txt")).exists();

    assertThat(PointB.undoRestore(source, aside)).isTrue();
    assertThat(source.resolve("v.txt")).hasContent("new");
    assertThat(source.resolve("extra.txt")).exists();
    assertThat(aside).doesNotExist();
    assertThat(PointB.undoRestore(source, aside)).isFalse();
  }

  /** Issue #48: a staging tree under webapps/ is deployable by Tomcat. */
  @Test
  void should_stage_beside_webapps_when_the_target_is_a_webapp() {
    Path tomcat = tmp.resolve("tomcat").toAbsolutePath().normalize();
    Path install = tmp.resolve("install").toAbsolutePath().normalize();

    assertThat(PointB.stagingFor(tomcat.resolve("webapps").resolve("jasperserver-pro")))
        .isEqualTo(tomcat.resolve(".jrs-upgrade-restore-jasperserver-pro"));
    assertThat(PointB.stagingFor(install.resolve("buildomatic")))
        .isEqualTo(install.resolve(".jrs-upgrade-restore-buildomatic"));
  }

  @Test
  void should_leave_only_the_webapp_under_webapps_when_a_restore_finishes() throws Exception {
    Platform.OsFamily os = Platform.OsFamily.WINDOWS;
    Path webapps = Files.createDirectories(tmp.resolve("tomcat").resolve("webapps"));
    Path webapp = Files.createDirectories(webapps.resolve("jasperserver-pro"));
    Files.writeString(webapp.resolve("v.txt"), "old", StandardCharsets.UTF_8);
    Path archive = tmp.resolve("webapp.zip");
    Archives.create(os, webapp, archive, new CancellationToken());
    Files.writeString(webapp.resolve("v.txt"), "new", StandardCharsets.UTF_8);
    Path legacy = Files.createDirectories(webapps.resolve(".jasperserver-pro.jrs-upgrade-restore"));
    Files.writeString(legacy.resolve("left.txt"), "crash", StandardCharsets.UTF_8);
    Path aside = tmp.resolve("run").resolve("aside").resolve("webapp");

    PointB.restoreDir(os, archive, webapp, aside, new CancellationToken());

    try (Stream<Path> listing = Files.list(webapps)) {
      assertThat(listing.map(p -> p.getFileName().toString())).containsExactly("jasperserver-pro");
    }
    assertThat(webapp.resolve("v.txt")).hasContent("old");
    assertThat(tmp.resolve("tomcat").resolve(".jrs-upgrade-restore-jasperserver-pro"))
        .doesNotExist();
  }
}
