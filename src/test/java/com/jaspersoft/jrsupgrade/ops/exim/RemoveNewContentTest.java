package com.jaspersoft.jrsupgrade.ops.exim;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.Event;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #139 (ADR-0036): the rollback anchor for folders an import creates from nothing. A live
 * check against a real 10.0.0 server found that a failed import of new content left everything it
 * had created while the run reported a rollback; these tests pin what is recorded at run time and
 * what a compensation deletes.
 */
class RemoveNewContentTest {

  @TempDir Path tmp;

  private EximFakeAdapter adapter;
  private EximFixture fx;
  private final List<Event> events = new ArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    adapter = new EximFakeAdapter();
    fx = new EximFixture(tmp, () -> adapter);
  }

  @AfterEach
  void tearDown() {
    fx.close();
  }

  private Context ctx() {
    return fx.context(EximFixture.RUN);
  }

  private static RemoveNewContent step(String... uris) {
    return new RemoveNewContent("import", List.of(uris));
  }

  private List<String> recorded() throws IOException {
    return Files.readAllLines(RemoveNewContent.recordFile(ctx()));
  }

  private List<String> logs() {
    return events.stream()
        .filter(e -> e instanceof Event.Log)
        .map(e -> ((Event.Log) e).message())
        .toList();
  }

  // ---- which folders are new -----------------------------------------------------------------

  @Test
  void should_find_the_topmost_missing_ancestor_of_each_folder() {
    adapter.existing = Optional.of(Set.of("/a"));

    assertThat(RemoveNewContent.newRoots(adapter, List.of("/a/b/c"))).containsExactly("/a/b");
    adapter.existing = Optional.of(Set.of());
    assertThat(RemoveNewContent.newRoots(adapter, List.of("/a/b/c"))).containsExactly("/a");
  }

  @Test
  void should_find_nothing_when_every_folder_exists() {
    adapter.existing = Optional.of(Set.of("/a/b/c", "/a/b", "/a"));

    assertThat(RemoveNewContent.newRoots(adapter, List.of("/a/b/c", "/a/b"))).isEmpty();
  }

  @Test
  void should_keep_one_root_when_several_folders_share_a_missing_ancestor() {
    adapter.existing = Optional.of(Set.of("/public"));

    assertThat(RemoveNewContent.newRoots(adapter, List.of("/x/one", "/x/two", "/x", "/public/y")))
        .containsExactly("/public/y", "/x");
  }

  @Test
  void should_never_name_the_repository_root() {
    adapter.existing = Optional.of(Set.of());

    assertThat(RemoveNewContent.newRoots(adapter, List.of("/", "/top")))
        .containsExactly("/top")
        .doesNotContain("/");
  }

  @Test
  void should_separate_organisation_folders_from_the_ones_resources_can_delete() {
    Set<String> roots = new java.util.TreeSet<>(List.of("/organizations/acme", "/public/new"));

    assertThat(RemoveNewContent.removable(roots)).containsExactly("/public/new");
    assertThat(RemoveNewContent.organisations(roots)).containsExactly("/organizations/acme");
  }

  // ---- execute ---------------------------------------------------------------------------------

  @Test
  void should_record_the_roots_that_do_not_exist_when_it_runs_and_change_nothing_on_the_server()
      throws IOException {
    adapter.existing = Optional.of(Set.of("/public"));

    StepResult result = step("/public/new", "/public").execute(ctx(), events::add);

    assertThat(result).isInstanceOf(StepResult.Ok.class);
    assertThat(recorded()).containsExactly("/public/new");
    assertThat(adapter.deleted).isEmpty();
    assertThat(adapter.imports).isEmpty();
    assertThat(logs()).anyMatch(m -> m.contains("removed again if the import fails"));
  }

  @Test
  void should_record_nothing_and_say_so_when_every_folder_exists() throws IOException {
    adapter.existing = Optional.of(Set.of("/public"));

    step("/public").execute(ctx(), events::add);

    assertThat(recorded()).isEmpty();
    assertThat(logs()).anyMatch(m -> m.contains("exists already"));
  }

  /** Someone creating the folder between the plan and the run must not have it deleted. */
  @Test
  void should_not_record_a_folder_that_appeared_after_planning() throws IOException {
    adapter.existing = Optional.of(Set.of("/public", "/public/new"));

    step("/public/new").execute(ctx(), events::add);

    assertThat(recorded()).isEmpty();
  }

  @Test
  void should_warn_and_not_record_an_organisation_it_would_create() throws IOException {
    adapter.existing = Optional.of(Set.of("/organizations"));

    step("/organizations/acme").execute(ctx(), events::add);

    assertThat(recorded()).isEmpty();
    assertThat(logs())
        .anyMatch(
            m -> m.contains("DELETE /rest_v2/organizations/acme") && m.contains("does not exist"));
  }

  @Test
  void should_fail_before_the_import_when_it_cannot_tell_which_folders_exist() {
    adapter.existsFailure = Optional.of("server unreachable");

    StepResult result = step("/public/new").execute(ctx(), events::add);

    assertThat(result).isInstanceOf(StepResult.Failed.class);
    assertThat(((StepResult.Failed) result).failure().cause())
        .contains("cannot tell which of /public/new exist")
        .contains("server unreachable");
  }

  // ---- compensate ------------------------------------------------------------------------------

  @Test
  void should_delete_every_recorded_root_deepest_first_when_it_compensates() throws IOException {
    adapter.existing = Optional.of(Set.of("/public"));
    RemoveNewContent step = step("/public/a/b", "/public/c");
    step.execute(ctx(), events::add);
    adapter.existing = Optional.of(Set.of("/public", "/public/a", "/public/a/b", "/public/c"));

    StepResult result = step.compensate(ctx(), events::add);

    assertThat(result).isInstanceOf(StepResult.Ok.class);
    assertThat(adapter.deleted).containsExactly("/public/a", "/public/c");
    assertThat(logs())
        .anyMatch(m -> m.contains("deleted /public/a (created by the failed import)"));
  }

  @Test
  void should_treat_a_root_the_import_never_created_as_nothing_to_do() {
    adapter.existing = Optional.of(Set.of("/public"));
    RemoveNewContent step = step("/public/new");
    step.execute(ctx(), events::add);
    // the import failed before it created anything

    StepResult result = step.compensate(ctx(), events::add);

    assertThat(result).isInstanceOf(StepResult.Ok.class);
    assertThat(adapter.deleted).isEmpty();
    assertThat(logs()).anyMatch(m -> m.contains("the import did not create it"));
  }

  @Test
  void should_say_nothing_was_recorded_when_every_folder_existed() {
    adapter.existing = Optional.of(Set.of("/public"));
    RemoveNewContent step = step("/public");
    step.execute(ctx(), events::add);

    StepResult result = step.compensate(ctx(), events::add);

    assertThat(result).isInstanceOf(StepResult.Ok.class);
    assertThat(adapter.deleted).isEmpty();
    assertThat(logs()).anyMatch(m -> m.contains("no new folder was recorded"));
  }

  @Test
  void should_do_nothing_when_the_step_never_ran() {
    StepResult result = step("/public/new").compensate(ctx(), events::add);

    assertThat(result).isInstanceOf(StepResult.Ok.class);
    assertThat(adapter.deleted).isEmpty();
    assertThat(logs()).anyMatch(m -> m.contains("the import never started"));
  }

  @Test
  void should_fail_with_the_roots_left_behind_when_a_deletion_is_refused() {
    adapter.existing = Optional.of(Set.of("/public"));
    RemoveNewContent step = step("/public/a", "/public/b");
    step.execute(ctx(), events::add);
    adapter.existing = Optional.of(Set.of("/public", "/public/a", "/public/b"));
    adapter.undeletable.add("/public/a");

    StepResult result = step.compensate(ctx(), events::add);

    assertThat(result).isInstanceOf(StepResult.Failed.class);
    assertThat(((StepResult.Failed) result).failure().cause())
        .contains("1 of 2 folders the failed import created remain")
        .contains("/public/a");
    assertThat(adapter.deleted).containsExactly("/public/b");
  }

  /** The Runner compensates mutating steps only, and this compensation must run. */
  @Test
  void should_declare_itself_a_mutating_rollback_anchor_of_the_import_phase() {
    RemoveNewContent step = step("/public/new");

    assertThat(step.mutating()).isTrue();
    assertThat(step.phase()).isEqualTo("import");
    assertThat(step.id()).isEqualTo("import.new-content-rollback");
    assertThat(step.detail()).contains("/public/new");
  }
}
