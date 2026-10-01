package com.jaspersoft.jrsupgrade.ops.customizations;

import static com.jaspersoft.jrsupgrade.ops.customizations.TestArchives.entries;
import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.compat.CompatMatrix;
import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.JarFinding;
import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.JarVerdict;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #4: NGRA's hand-patched jars against a 10.1 target. */
class JarRetirementTest {

  @TempDir Path tmp;

  private PackageIndex target() throws Exception {
    Path war =
        TestArchives.write(
            tmp.resolve("target/jasperserver-pro.war"),
            TestArchives.zip(
                entries(
                    "WEB-INF/lib/commons-lang3-3.14.0.jar",
                    TestArchives.mavenJar(
                        "org.apache.commons", "commons-lang3", "3.14.0", Map.of()),
                    "WEB-INF/lib/jackson-databind-2.15.0.jar",
                    TestArchives.zip(Map.of("x", new byte[0])),
                    "WEB-INF/lib/mina-core-2.0.25.jar",
                    TestArchives.zip(Map.of("x", new byte[0])))));
    return PackageIndex.read(war);
  }

  private Path ours(String name, byte[] bytes) throws Exception {
    return TestArchives.write(tmp.resolve("installed/WEB-INF/lib").resolve(name), bytes);
  }

  @Test
  void should_judge_each_jar_against_the_target_and_the_matrix() throws Exception {
    List<Path> jars =
        List.of(
            ours(
                "commons-lang3-3.12.0.jar",
                TestArchives.mavenJar("org.apache.commons", "commons-lang3", "3.12.0", Map.of())),
            ours("jackson-databind-2.16.1.jar", TestArchives.zip(Map.of("x", new byte[0]))),
            ours("bigquery-jdbc-1.5.2.jar", TestArchives.zip(Map.of("x", new byte[0]))),
            ours("TIredshift.jar", TestArchives.zip(Map.of("x", new byte[0]))),
            ours("ngra-auth.jar", TestArchives.zip(Map.of("x", new byte[0]))));

    List<JarFinding> findings =
        JarRetirement.judge(
            jars, target(), CompatMatrix.load().rules().jarRules("8.2.0", "10.1.0"));

    assertThat(findings)
        .extracting(JarFinding::jar, JarFinding::verdict)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("commons-lang3-3.12.0.jar", JarVerdict.DROP),
            org.assertj.core.groups.Tuple.tuple("jackson-databind-2.16.1.jar", JarVerdict.REVIEW),
            org.assertj.core.groups.Tuple.tuple("bigquery-jdbc-1.5.2.jar", JarVerdict.KEEP),
            org.assertj.core.groups.Tuple.tuple("TIredshift.jar", JarVerdict.REPLACE),
            org.assertj.core.groups.Tuple.tuple("ngra-auth.jar", JarVerdict.UNRESOLVED));
    assertThat(findings.get(0).targetJar()).contains("commons-lang3-3.14.0.jar");
    assertThat(findings.get(0).coordinates()).contains("org.apache.commons:commons-lang3:3.12.0");
    assertThat(findings.get(3).reason()).contains("re-point the data sources").contains("NGRA");
  }

  @Test
  void should_drop_a_jar_the_target_ships_in_the_same_version() throws Exception {
    JarFinding same =
        JarRetirement.judge(
            "mina-core-2.0.25.jar",
            PackageIndex.fromName("mina-core-2.0.25.jar"),
            target(),
            List.of());
    assertThat(same.verdict()).isEqualTo(JarVerdict.DROP);
    assertThat(same.reason()).contains("the same version");
  }

  @Test
  void should_order_free_form_versions() {
    assertThat(Versions.compare("3.14.0", "3.12.0")).isPositive();
    assertThat(Versions.compare("1.2", "1.2.0")).isZero();
    assertThat(Versions.compare("2.0.10", "2.0.9")).isPositive();
    assertThat(Versions.compare("5.8.9", "5.8.9-patched")).isNegative();
    assertThat(Versions.compare("1.0.RELEASE", "1.0.release")).isZero();
  }
}
