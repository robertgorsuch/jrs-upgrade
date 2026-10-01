package com.jaspersoft.jrsupgrade.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TomcatVersionTest {

  @TempDir Path tmp;

  @Test
  void should_read_the_version_from_the_catalina_jar_manifest_when_present() throws IOException {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, "10.1.24");
    Path lib = Files.createDirectories(tmp.resolve("lib"));
    try (OutputStream out = Files.newOutputStream(lib.resolve("catalina.jar"));
        JarOutputStream jar = new JarOutputStream(out, manifest)) {
      jar.flush();
    }
    Files.writeString(tmp.resolve("RELEASE-NOTES"), "Apache Tomcat Version 9.0.1\n");

    assertThat(TomcatVersion.detect(tmp)).contains("10.1.24");
  }

  @Test
  void should_fall_back_to_the_release_notes_when_there_is_no_jar() throws IOException {
    Files.writeString(
        tmp.resolve("RELEASE-NOTES"),
        "================================\n"
            + "Apache Tomcat Version 11.0.11\n"
            + "Release Notes\n",
        StandardCharsets.ISO_8859_1);

    assertThat(TomcatVersion.detect(tmp)).contains("11.0.11");
  }

  @Test
  void should_be_empty_when_neither_source_exists() {
    assertThat(TomcatVersion.detect(tmp)).isEmpty();
  }
}
