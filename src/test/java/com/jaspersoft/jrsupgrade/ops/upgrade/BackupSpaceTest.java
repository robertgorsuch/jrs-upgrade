package com.jaspersoft.jrsupgrade.ops.upgrade;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.UpgradeOptions;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupSpaceTest {

  @TempDir Path tmp;

  /**
   * Review finding 1.16: the webapp backup had no free-space check at all before archiving a
   * multi-gigabyte tree onto the snapshot volume.
   */
  @Test
  void should_fail_the_webapp_backup_precheck_when_the_snapshot_volume_is_short_of_space()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan =
          f.ops().planUpgrade(UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, f.packageDir));
      f.fake.platform.freeSpaceUnder.put(f.fake.home.snapshots(), 10L);

      CheckResult result = UpgradeFixture.step(plan, "backup-webapp").precheck(f.ctx("r-space"));

      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message()).contains("free");

      f.fake.platform.freeSpaceUnder.clear();
      assertThat(UpgradeFixture.step(plan, "backup-webapp").precheck(f.ctx("r-space")))
          .isInstanceOf(CheckResult.Pass.class);
    }
  }

  /** Field test 2, U3: the full export, the largest file of the run, had no space check at all. */
  @Test
  void should_fail_the_full_export_precheck_when_the_snapshot_volume_is_short_of_space()
      throws Exception {
    try (UpgradeFixture f = UpgradeFixture.create(tmp)) {
      Plan plan =
          f.ops().planUpgrade(UpgradeOptions.newdb(UpgradeFixture.NEW_VERSION, f.packageDir));
      f.fake.platform.freeSpaceUnder.put(f.fake.home.snapshots(), 10L);

      CheckResult result = UpgradeFixture.step(plan, "full-export").precheck(f.ctx("r-space"));

      assertThat(result).isInstanceOf(CheckResult.Fail.class);
      assertThat(((CheckResult.Fail) result).message())
          .contains("full export needs about")
          .contains("free under");
      assertThat(((CheckResult.Fail) result).remediation()).contains("--home");

      f.fake.platform.freeSpaceUnder.clear();
      assertThat(UpgradeFixture.step(plan, "full-export").precheck(f.ctx("r-space")))
          .isInstanceOf(CheckResult.Pass.class);
    }
  }
}
