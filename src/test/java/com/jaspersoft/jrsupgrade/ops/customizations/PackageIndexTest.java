package com.jaspersoft.jrsupgrade.ops.customizations;

import static com.jaspersoft.jrsupgrade.ops.customizations.TestArchives.entries;
import static com.jaspersoft.jrsupgrade.ops.customizations.TestArchives.text;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackageIndexTest {

  @TempDir Path tmp;

  static Map<String, byte[]> webapp() throws Exception {
    return entries(
        "WEB-INF/web.xml",
        text("<web-app/>"),
        "WEB-INF/lib/commons-lang3-3.14.0.jar",
        TestArchives.mavenJar("org.apache.commons", "commons-lang3", "3.14.0", Map.of()),
        "WEB-INF/lib/jasperserver-api-impl-10.1.0.jar",
        TestArchives.classJar(
            Map.of(
                "com.jaspersoft.jasperserver.api.Base",
                TestArchives.classFile(
                    "com.jaspersoft.jasperserver.api.Base",
                    "java.lang.Object",
                    List.of(),
                    List.of(),
                    List.of()))),
        "WEB-INF/lib/opaque.jar",
        TestArchives.zip(Map.of("x.txt", text("x"))));
  }

  @Test
  void should_index_files_jars_coordinates_and_classes_of_a_war_without_unpacking_it()
      throws Exception {
    Path war = TestArchives.write(tmp.resolve("jasperserver-pro.war"), TestArchives.zip(webapp()));

    PackageIndex index = PackageIndex.read(war);

    assertThat(index.files()).contains("WEB-INF/web.xml", "WEB-INF/lib/opaque.jar");
    assertThat(index.jar("commons-lang3-3.14.0.jar").orElseThrow().coordinates())
        .hasValueSatisfying(
            c -> {
              assertThat(c.groupId()).contains("org.apache.commons");
              assertThat(c.from()).isEqualTo(PackageIndex.From.POM);
            });
    assertThat(index.jar("jasperserver-api-impl-10.1.0.jar").orElseThrow().coordinates())
        .hasValueSatisfying(
            c -> {
              assertThat(c.artifactId()).isEqualTo("jasperserver-api-impl");
              assertThat(c.version()).isEqualTo("10.1.0");
              assertThat(c.from()).isEqualTo(PackageIndex.From.FILE_NAME);
            });
    assertThat(index.jar("opaque.jar").orElseThrow().coordinates()).isEmpty();
    assertThat(index.classes())
        .containsEntry("com.jaspersoft.jasperserver.api.Base", "jasperserver-api-impl-10.1.0.jar");
    assertThat(index.read("WEB-INF/web.xml"))
        .hasValueSatisfying(b -> assertThat(b).isEqualTo(text("<web-app/>")));
    assertThat(index.read("WEB-INF/absent.xml")).isEmpty();
  }

  @Test
  void should_index_an_exploded_webapp_the_same_way() throws Exception {
    Path dir = tmp.resolve("jasperserver-pro");
    for (Map.Entry<String, byte[]> e : webapp().entrySet()) {
      TestArchives.write(dir.resolve(e.getKey()), e.getValue());
    }

    PackageIndex index = PackageIndex.read(dir);

    assertThat(index.jars()).hasSize(3);
    assertThat(index.classes()).containsKey("com.jaspersoft.jasperserver.api.Base");
    assertThat(index.read("WEB-INF/web.xml")).isPresent();
  }

  @Test
  void should_read_coordinates_from_the_file_name_only_when_it_states_a_version() {
    assertThat(PackageIndex.fromName("spring-security-core-5.8.9.jar"))
        .hasValueSatisfying(c -> assertThat(c.version()).isEqualTo("5.8.9"));
    assertThat(PackageIndex.fromName("mina-core-2.0.21-patched.jar"))
        .hasValueSatisfying(c -> assertThat(c.artifactId()).isEqualTo("mina-core"));
    assertThat(PackageIndex.fromName("TIredshift.jar")).isEqualTo(Optional.empty());
  }
}
