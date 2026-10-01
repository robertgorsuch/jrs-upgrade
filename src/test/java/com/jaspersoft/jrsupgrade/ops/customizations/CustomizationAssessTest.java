package com.jaspersoft.jrsupgrade.ops.customizations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsupgrade.ops.FakeLayout;
import com.jaspersoft.jrsupgrade.ops.FakeServices;
import com.jaspersoft.jrsupgrade.ops.Services;
import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.Findings;
import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.MergeFinding;
import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.MergeStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0003: {@code customizations scan --target} judges the site's changes against an 8.2.0 to
 * 10.1.0 upgrade.
 */
class CustomizationAssessTest {

  @TempDir Path tmp;

  FakeServices fake;
  DefaultCustomizationOperations ops;
  Path webapp;
  Path vendor;
  Path target;

  @BeforeEach
  void setUp() throws Exception {
    Path install = FakeLayout.linux(Files.createDirectories(tmp.resolve("jrs")));
    webapp = install.resolve("apache-tomcat").resolve("webapps").resolve("jasperserver-pro");
    vendor =
        Files.createDirectories(
            tmp.resolve("jasperreports-server-pro-8.2.0-bin").resolve("jasperserver-pro"));
    target =
        Files.createDirectories(
            tmp.resolve("jasperreports-server-pro-10.1.0-bin").resolve("jasperserver-pro"));
    Files.createDirectories(vendor.resolve("WEB-INF"));
    Files.createDirectories(target.resolve("WEB-INF"));
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
  }

  @AfterEach
  void tearDown() {
    fake.close();
  }

  static String slashes(Path p) {
    return p.toAbsolutePath().toString().replace('\\', '/');
  }

  static void write(Path root, String rel, String content) throws Exception {
    Path file = root.resolve(rel);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  Findings assess(Optional<Path> mergeDir) {
    return ops.assess(ops.scan(vendor.getParent()), target.getParent(), Optional.empty(), mergeDir);
  }

  /** Issue #6, its acceptance: ehcache.xml on 10.1 says where the cache settings went. */
  @Test
  void should_say_where_a_relocated_file_went() throws Exception {
    write(vendor, "WEB-INF/ehcache.xml", "<ehcache/>\n");
    write(webapp, "WEB-INF/ehcache.xml", "<ehcache maxElements=\"9\"/>\n");

    Findings f = assess(Optional.empty());

    assertThat(f.sourceVersion()).isEqualTo("8.2.0");
    assertThat(f.targetVersion()).isEqualTo("10.1.0");
    assertThat(f.relocations())
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.path()).isEqualTo("WEB-INF/ehcache.xml");
              assertThat(r.kind()).isEqualTo("REMOVED");
              assertThat(r.description())
                  .contains("removed in 10.0")
                  .contains("appCacheType in default_master.properties")
                  .contains("*-ehcache.xml")
                  .contains("NGRA upgrade guide");
            });
    assertThat(f.merges())
        .singleElement()
        .satisfies(m -> assertThat(m.status()).isEqualTo(MergeStatus.NOT_IN_TARGET));
  }

  /** Issue #6: the three inputs, the merged file and the patch, under the file's own path. */
  @Test
  void should_write_a_three_way_merge_of_each_changed_file_the_target_ships() throws Exception {
    String rel = "WEB-INF/applicationContext-custom.xml";
    write(vendor, rel, "a\nb\nc\n");
    write(webapp, rel, "a\nB\nc\n");
    write(target, rel, "a\nb\nc\nd\n");
    String conflicting = "WEB-INF/js.config.properties";
    write(vendor, conflicting, "x=1\n");
    write(webapp, conflicting, "x=mine\n");
    write(target, conflicting, "x=theirs\n");
    Path out = tmp.resolve("merge");

    Findings f = assess(Optional.of(out));

    MergeFinding clean = merge(f, rel);
    assertThat(clean.status()).isEqualTo(MergeStatus.CLEAN);
    assertThat(Files.readString(out.resolve(rel + ".merged"))).isEqualTo("a\nB\nc\nd\n");
    assertThat(Files.readString(out.resolve(rel + ".patch"))).contains("-b").contains("+B");
    assertThat(out.resolve(rel + ".base")).hasContent("a\nb\nc");
    assertThat(out.resolve(rel + ".mine")).exists();
    assertThat(out.resolve(rel + ".theirs")).exists();
    MergeFinding conflict = merge(f, conflicting);
    assertThat(conflict.status()).isEqualTo(MergeStatus.CONFLICT);
    assertThat(conflict.conflicts()).isEqualTo(1);
    assertThat(Files.readString(out.resolve(conflicting + ".merged")))
        .contains("x=mine")
        .contains("x=theirs")
        .contains("<<<<<<<");
  }

  @Test
  void should_merge_without_writing_when_no_merge_dir_is_given() throws Exception {
    String rel = "WEB-INF/applicationContext-custom.xml";
    write(vendor, rel, "a\n");
    write(webapp, rel, "b\n");
    write(target, rel, "a\n");

    Findings f = assess(Optional.empty());

    assertThat(merge(f, rel).status()).isEqualTo(MergeStatus.CLEAN);
    assertThat(merge(f, rel).merged()).isEmpty();
  }

  @Test
  void should_refuse_a_target_whose_version_cannot_be_told() throws Exception {
    Path anonymous =
        Files.createDirectories(tmp.resolve("dist").resolve("jasperserver-pro").resolve("WEB-INF"))
            .getParent();
    CustomizationOperations.Scan scan = ops.scan(vendor.getParent());

    assertThatThrownBy(() -> ops.assess(scan, anonymous.getParent(), Optional.empty()))
        .isInstanceOf(CustomizationException.class)
        .hasMessageContaining("cannot tell the target version");
    assertThat(ops.assess(scan, anonymous.getParent(), Optional.of("10.1.0")).targetVersion())
        .isEqualTo("10.1.0");
  }

  static MergeFinding merge(Findings f, String rel) {
    return f.merges().stream().filter(m -> m.path().equals(rel)).findFirst().orElseThrow();
  }
}
