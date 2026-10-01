package com.jaspersoft.jrsupgrade.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.state.Customization;
import com.jaspersoft.jrsupgrade.ops.customizations.TestArchives;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.UpgradeOptions;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR-0003: the upgrade plan judges the registered customizations against the target. */
class CustomizationFindingsPlanTest {

  @TempDir Path tmp;

  static void register(UpgradeFixture f, Path file) {
    f.store().registerCustomization(new Customization(file, "aa", Optional.empty(), Instant.EPOCH));
  }

  static Plan plan(UpgradeFixture f) {
    return f.ops().planUpgrade(UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, f.packageDir));
  }

  /** Issue #4: a hand-patched jar the target ships newer is to be dropped. */
  @Test
  void should_give_each_registered_jar_a_verdict_in_the_plan() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path jar = f.webappDir.resolve("WEB-INF/lib/commons-lang3-3.12.0.jar");
      UpgradeFixture.write(jar, "jar");
      UpgradeFixture.write(
          f.packageDir.resolve("jasperserver-pro/WEB-INF/lib/commons-lang3-3.14.0.jar"), "jar");
      register(f, jar);

      assertThat(plan(f).summary().warnings())
          .anySatisfy(
              w ->
                  assertThat(w)
                      .contains("jar commons-lang3-3.12.0.jar: DROP")
                      .contains("3.14.0")
                      .contains("issue #4"));
    }
  }

  /** Issue #5: a registered jar built on a vendor type the target lacks, and on javax.servlet. */
  @Test
  void should_name_missing_vendor_types_and_a_jakarta_recompile_for_a_10_x_target()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path jar = f.webappDir.resolve("WEB-INF/lib/ngra-auth.jar");
      String audit = "com.jaspersoft.jasperserver.api.logging.audit.AuditService";
      java.nio.file.Files.write(
          jar,
          TestArchives.classJar(
              java.util.Map.of(
                  "com.ngra.Filter",
                  TestArchives.classFile(
                      "com.ngra.Filter",
                      "java.lang.Object",
                      java.util.List.of(),
                      java.util.List.of(audit),
                      java.util.List.of("(Ljavax/servlet/ServletRequest;)V")))));
      register(f, jar);

      Plan plan = f.ops().planUpgrade(UpgradeOptions.newdb("10.0.0", f.packageDir));

      assertThat(plan.summary().warnings())
          .anySatisfy(w -> assertThat(w).contains(audit).contains("MISSING").contains("issue #5"))
          .anySatisfy(
              w ->
                  assertThat(w)
                      .contains("jar ngra-auth.jar refers to javax.servlet")
                      .contains("Jakarta EE 10"));
    }
  }

  /** Issue #8: a registered override with annotatedClasses on the vendor sessionFactory. */
  @Test
  void should_name_constructs_the_target_handles_differently() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Path xml = f.webappDir.resolve("WEB-INF/ngra-applicationContext.xml");
      UpgradeFixture.write(
          xml,
          "<beans><bean id=\"sessionFactory\">"
              + "<property name=\"annotatedClasses\"/></bean></beans>");
      register(f, xml);

      Plan plan = f.ops().planUpgrade(UpgradeOptions.newdb("10.0.0", f.packageDir));

      assertThat(plan.summary().warnings())
          .anySatisfy(
              w ->
                  assertThat(w)
                      .contains("WEB-INF/ngra-applicationContext.xml:1")
                      .contains("persistence.xml")
                      .contains("issue #8"));
    }
  }

  @Test
  void should_say_nothing_about_jars_when_none_is_registered() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      assertThat(plan(f).summary().warnings()).noneMatch(w -> w.contains("issue #4"));
    }
  }
}
