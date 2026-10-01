package com.jaspersoft.jrsupgrade.ops.upgrade;

import static com.jaspersoft.jrsupgrade.ops.customizations.TestArchives.entries;
import static com.jaspersoft.jrsupgrade.ops.customizations.TestArchives.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.RunOptions;
import com.jaspersoft.jrsupgrade.core.engine.RunOutcome;
import com.jaspersoft.jrsupgrade.ops.customizations.TestArchives;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.Mode;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.UpgradeOptions;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #10: an 8.2 export imported by a newdb upgrade to 9.0 overwrites the vendor's Ad Hoc
 * templates of the same names.
 */
class AdhocTemplatesTest {

  @TempDir Path tmp;

  /** The 9.0 package's minimal catalog: two vendor templates. */
  static void catalog(UpgradeFixture f) throws Exception {
    TestArchives.write(
        f.packageDir.resolve(
            "buildomatic/install_resources/export/js-catalog-postgresql-minimal-pro.zip"),
        TestArchives.zip(
            entries(
                "index.xml", text("<export/>"),
                "resources/public/templates/actual_size.xml", text("<vendor 9.0/>"),
                "resources/public/templates/fit_to_page.xml", text("<vendor same/>"))));
  }

  /** NGRA's 8.2 export: an old copy of one vendor name, an identical one, and its own template. */
  static Path export(UpgradeFixture f) throws Exception {
    return TestArchives.write(
        f.root.resolve("exports/ngra-8.2.zip"),
        TestArchives.zip(
            entries(
                "index.xml", text("<export/>"),
                "resources/public/templates/actual_size.xml", text("<legacy 8.2/>"),
                "resources/public/templates/fit_to_page.xml", text("<vendor same/>"),
                "resources/public/templates/TRComplianceParameters.xml", text("<ngra/>"))));
  }

  static UpgradeOptions options(UpgradeFixture f) throws Exception {
    return UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, f.packageDir)
        .withExistingExport(export(f));
  }

  /** The acceptance of issue #10. */
  @Test
  void should_name_the_overwritten_vendor_templates_and_leave_the_sites_own_alone()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      catalog(f);
      Plan plan = f.ops().planUpgrade(options(f));

      assertThat(UpgradeFixture.ids(plan))
          .containsSubsequence("run-vendor-upgrade", "check-adhoc-templates", "start-service")
          .doesNotContain("restore-vendor-templates");
      assertThat(plan.summary().warnings())
          .anySatisfy(w -> assertThat(w).contains("--restore-vendor-templates"));

      RunOutcome outcome = f.run(plan, "r-tpl", RunOptions.DEFAULT);

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.logs())
          .anySatisfy(
              m ->
                  assertThat(m)
                      .contains("overwrote the vendor's Ad Hoc templates actual_size")
                      .doesNotContain("fit_to_page")
                      .contains("import-minimal-pro"))
          .anySatisfy(
              m -> assertThat(m).contains("left as they are").contains("TRComplianceParameters"));
      assertThat(f.vendorLogText()).doesNotContain("import-minimal-pro");
    }
  }

  @Test
  void should_run_import_minimal_only_when_asked() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      catalog(f);
      Plan plan = f.ops().planUpgrade(options(f).withRestoreVendorTemplates(true));

      assertThat(UpgradeFixture.ids(plan))
          .containsSubsequence(
              "check-adhoc-templates", "restore-vendor-templates", "start-service");

      RunOutcome outcome = f.run(plan, "r-tpl-restore", RunOptions.DEFAULT);

      assertThat(outcome).as(String.join("\n", f.logs())).isInstanceOf(RunOutcome.Succeeded.class);
      assertThat(f.vendorLogText()).contains("import-minimal-pro");
    }
  }

  @Test
  void should_leave_samedb_alone_and_refuse_the_flag_there() throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      UpgradeOptions samedb =
          new UpgradeOptions(UpgradeFixture.NEW_VERSION, f.packageDir, Mode.SAMEDB, true);

      assertThat(UpgradeFixture.ids(f.ops().planUpgrade(samedb)))
          .doesNotContain("check-adhoc-templates");
      assertThatThrownBy(() -> f.ops().planUpgrade(samedb.withRestoreVendorTemplates(true)))
          .isInstanceOf(UpgradeException.class)
          .satisfies(e -> assertThat(((UpgradeException) e).exitCode()).isEqualTo(1));
    }
  }

  @Test
  void should_apply_only_from_before_9_0_to_9_0_or_later() {
    assertThat(TemplateSteps.applies(Optional.of("8.2.0"), "9.0.0")).isTrue();
    assertThat(TemplateSteps.applies(Optional.of("8.2.0"), "10.0.0")).isTrue();
    assertThat(TemplateSteps.applies(Optional.empty(), "10.0.0")).isTrue();
    assertThat(TemplateSteps.applies(Optional.of("9.0.0"), "10.1.0")).isFalse();
    assertThat(TemplateSteps.applies(Optional.of("7.9.0"), "8.2.0")).isFalse();
  }

  @Test
  void should_compare_templates_by_name_and_content() {
    TemplateSteps.Comparison c =
        TemplateSteps.compare(
            new java.util.TreeMap<>(
                java.util.Map.of("a", text("old"), "b", text("same"), "mine", text("x"))),
            java.util.Map.of("a", text("new"), "b", text("same")));
    assertThat(c.overwritten()).isEqualTo(List.of("a"));
    assertThat(c.untouched()).isEqualTo(List.of("mine"));
  }
}
