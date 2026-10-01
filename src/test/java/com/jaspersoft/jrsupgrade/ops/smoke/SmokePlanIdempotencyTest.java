package com.jaspersoft.jrsupgrade.ops.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.engine.CancellationToken;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.ops.FakeServices;
import com.jaspersoft.jrsupgrade.ops.Idempotency;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 8 idempotency of the mutating smoke plan (spec §6.1, §12.2): every step consults the
 * repository before acting, so executing or compensating it twice issues each server mutation
 * exactly once.
 */
class SmokePlanIdempotencyTest {

  private static final String RUN = "r-smoke";
  private static final String YAML =
      """
      server:
        baseUrl: http://localhost:8080/jasperserver-pro
        auth:
          username: jasperadmin
          passwordRef: env:JRS_PASSWORD
      """;

  @TempDir Path tmp;

  private static Context ctx(FakeServices fake) {
    return new Context(
        RUN,
        fake.home,
        fake.platform,
        new CancellationToken(),
        Map.of(JrsAdapter.class, fake.adapter));
  }

  private static Plan plan(FakeServices fake) {
    return SmokePlan.build(RUN, fake.adapter.identity);
  }

  private static long calls(FakeServices fake, String prefix) {
    return fake.adapter.calls.stream().filter(c -> c.startsWith(prefix)).count();
  }

  /** A deep, ordered rendering of the fake repository tree. */
  private static String repository(FakeServices fake) {
    return new java.util.TreeMap<>(fake.adapter.folders).toString();
  }

  @Test
  void should_create_the_folder_once_when_create_folder_executes_twice() throws IOException {
    try (FakeServices fake = FakeServices.in(tmp).yaml(YAML)) {
      Step create = Idempotency.step(plan(fake), "smoke-folder-create");
      Idempotency.executeOk(create, ctx(fake));
      Idempotency.executeOk(create, ctx(fake));
      assertThat(calls(fake, "createFolder")).isEqualTo(1);
      assertThat(fake.adapter.folders.get("/temp")).containsExactly(SmokePlan.folderUri(RUN));
    }
  }

  @Test
  void should_delete_the_folder_once_when_create_folder_compensates_twice() throws IOException {
    try (FakeServices fake = FakeServices.in(tmp).yaml(YAML)) {
      Step create = Idempotency.step(plan(fake), "smoke-folder-create");
      Idempotency.executeOk(create, ctx(fake));
      Idempotency.compensateOk(create, ctx(fake));
      Idempotency.compensateOk(create, ctx(fake));
      assertThat(calls(fake, "deleteResource")).isEqualTo(1);
      assertThat(fake.adapter.folders.get("/temp")).isEmpty();
    }
  }

  @Test
  void should_upload_the_report_once_when_upload_report_executes_twice() throws IOException {
    try (FakeServices fake = FakeServices.in(tmp).yaml(YAML)) {
      Plan plan = plan(fake);
      Idempotency.executeOk(Idempotency.step(plan, "smoke-folder-create"), ctx(fake));
      Step upload = Idempotency.step(plan, "smoke-report-upload");
      Idempotency.executeOk(upload, ctx(fake));
      Idempotency.executeOk(upload, ctx(fake));
      assertThat(calls(fake, "uploadJrxmlReport")).isEqualTo(1);
      assertThat(fake.adapter.folders.get(SmokePlan.folderUri(RUN)))
          .containsExactly(SmokePlan.reportUri(RUN));
    }
  }

  @Test
  void should_delete_the_report_once_when_upload_report_compensates_twice() throws IOException {
    try (FakeServices fake = FakeServices.in(tmp).yaml(YAML)) {
      Plan plan = plan(fake);
      Idempotency.executeOk(Idempotency.step(plan, "smoke-folder-create"), ctx(fake));
      Step upload = Idempotency.step(plan, "smoke-report-upload");
      Idempotency.executeOk(upload, ctx(fake));
      Idempotency.compensateOk(upload, ctx(fake));
      Idempotency.compensateOk(upload, ctx(fake));
      assertThat(calls(fake, "deleteResource")).isEqualTo(1);
      assertThat(fake.adapter.folders.get(SmokePlan.folderUri(RUN))).isEmpty();
    }
  }

  @Test
  void should_not_mutate_when_run_report_executes_twice() throws IOException {
    try (FakeServices fake = FakeServices.in(tmp).yaml(YAML)) {
      Plan plan = plan(fake);
      Idempotency.runUpTo(plan, ctx(fake), "smoke-report-upload");
      Step run = Idempotency.step(plan, "smoke-report-run");
      String before = repository(fake);
      assertThat(run.mutating()).isFalse();
      Idempotency.executeOk(run, ctx(fake));
      Idempotency.executeOk(run, ctx(fake));
      assertThat(calls(fake, "runReportToPdf")).isEqualTo(2);
      assertThat(repository(fake)).isEqualTo(before);
      assertThat(fake.home.stagingDir(RUN).resolve("smoke.pdf")).doesNotExist();
    }
  }

  @Test
  void should_delete_the_folder_once_when_delete_folder_executes_twice() throws IOException {
    try (FakeServices fake = FakeServices.in(tmp).yaml(YAML)) {
      Plan plan = plan(fake);
      Idempotency.runUpTo(plan, ctx(fake), "smoke-report-run");
      Step delete = Idempotency.step(plan, "smoke-folder-delete");
      assertThat(delete.irreversible()).isTrue();
      Idempotency.executeOk(delete, ctx(fake));
      Idempotency.executeOk(delete, ctx(fake));
      Idempotency.compensateOk(delete, ctx(fake));
      assertThat(calls(fake, "deleteResource")).isEqualTo(1);
      assertThat(fake.adapter.folders.get("/temp")).isEmpty();
      assertThat(fake.adapter.folders).doesNotContainKey(SmokePlan.folderUri(RUN));
    }
  }
}
