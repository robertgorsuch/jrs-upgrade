package com.jaspersoft.jrsupgrade.ops.exim;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepFailure;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.jrs.api.ExportRequest;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Records, just before an import, every resource URI under the folders the import targets, so the
 * rollback can delete what the failed import created (issue #100, ADR-0031). Invariants: read-only
 * against the server; the listing is one URI per line, sorted, under the run directory ({@link
 * #listingFile}), so {@code runs recover} finds it and retention prunes it with the run; a listing
 * that cannot be taken fails the step before anything is imported, because an import whose rollback
 * would silently leave additions behind is what the field test complained of; nothing to
 * compensate.
 */
final class RecordRepositoryListing implements Step {

  static final String ID = "backup.pre-import-listing";
  static final String FILE = "pre-import-listing.txt";

  private final List<String> roots;

  RecordRepositoryListing(List<String> roots) {
    this.roots = List.copyOf(Objects.requireNonNull(roots, "roots"));
  }

  /** The folders an import targets: the snapshot's uris, or the root for a full-server import. */
  static List<String> rootsOf(ExportRequest snapshot) {
    if (snapshot.fullServer()
        || snapshot.scope() == ExportRequest.Scope.EVERYTHING
        || snapshot.uris().isEmpty()) {
      return List.of("/");
    }
    return List.copyOf(new TreeSet<>(snapshot.uris()));
  }

  static Path listingFile(Context ctx) {
    return ctx.home().runDir(ctx.runId()).resolve(FILE);
  }

  /** Every URI under the roots, sorted; the roots themselves excluded. */
  static Set<String> list(JrsAdapter adapter, List<String> roots) {
    TreeSet<String> uris = new TreeSet<>();
    for (String root : roots) {
      uris.addAll(adapter.listTree(root));
    }
    uris.removeAll(roots);
    return uris;
  }

  List<String> roots() {
    return roots;
  }

  /**
   * Issue #100, ADR-0031: deletes what the failed import created, the URIs under the roots that the
   * listing taken just before the import did not hold, deepest first so a created folder goes after
   * its children; logged as {@code step}. A missing listing or a server that cannot be listed
   * leaves the additions behind with a warning naming them as such; one resource that cannot be
   * deleted is logged and the rest still go. Deleting again after a first rollback finds nothing
   * new, so a repeated compensation deletes nothing twice.
   */
  static void deleteAdditions(Context ctx, EventSink out, Step step, List<String> roots) {
    Path listing = listingFile(ctx);
    if (!Files.isRegularFile(listing)) {
      EximLogs.warn(
          out,
          ctx,
          step,
          "no pre-import listing at "
              + listing
              + "; resources the failed import created under "
              + String.join(", ", roots)
              + " are left behind");
      return;
    }
    Set<String> before;
    Set<String> now;
    try {
      before = new HashSet<>(Files.readAllLines(listing, StandardCharsets.UTF_8));
      now = list(ctx.service(JrsAdapter.class), roots);
    } catch (IOException | RuntimeException e) {
      EximLogs.warn(
          out,
          ctx,
          step,
          "cannot compare the repository with the pre-import listing ("
              + e.getMessage()
              + "); resources the failed import created under "
              + String.join(", ", roots)
              + " are left behind");
      return;
    }
    List<String> additions =
        now.stream()
            .filter(uri -> !before.contains(uri))
            .sorted(
                Comparator.comparingInt(
                        (String uri) -> uri.length() - uri.replace("/", "").length())
                    .reversed()
                    .thenComparing(Comparator.naturalOrder()))
            .toList();
    if (additions.isEmpty()) {
      EximLogs.info(out, ctx, step, "the failed import created nothing that is still there");
      return;
    }
    int failed = 0;
    JrsAdapter adapter = ctx.service(JrsAdapter.class);
    for (String uri : additions) {
      try {
        adapter.deleteResource(uri);
        EximLogs.info(out, ctx, step, "deleted " + uri + " (created by the failed import)");
      } catch (RuntimeException e) {
        failed++;
        EximLogs.warn(out, ctx, step, "cannot delete " + uri + ": " + e.getMessage());
      }
    }
    EximLogs.info(
        out,
        ctx,
        step,
        (additions.size() - failed)
            + " of "
            + additions.size()
            + " resources the failed import created deleted"
            + (failed > 0 ? "; " + failed + " left behind, see the warnings above" : ""));
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Record the repository listing";
  }

  @Override
  public String phase() {
    return DefaultExportImportOperations.BACKUP_PHASE;
  }

  @Override
  public String detail() {
    return "every URI under " + String.join(", ", roots) + " -> runs/<runId>/" + FILE;
  }

  @Override
  public boolean mutating() {
    return false;
  }

  @Override
  public CheckResult precheck(Context ctx) {
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    Set<String> uris;
    try {
      uris = list(ctx.service(JrsAdapter.class), roots);
    } catch (RuntimeException e) {
      return StepResult.failed(
          StepFailure.recoverable(
              "cannot list the repository under "
                  + String.join(", ", roots)
                  + ": "
                  + e.getMessage()
                  + "; without the listing a rollback could not delete what the import creates",
              "check that the server answers GET /rest_v2/resources, then run again"));
    }
    Path file = listingFile(ctx);
    try {
      Files.createDirectories(file.getParent());
      Files.write(file, uris, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return StepResult.failed(
          StepFailure.recoverable(
              "cannot write " + file + ": " + e.getMessage(),
              "free the run directory, then run again"));
    }
    EximLogs.info(
        out, ctx, this, uris.size() + " URIs under " + String.join(", ", roots) + " recorded");
    return StepResult.ok();
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    // the listing is run bookkeeping; retention removes it with the run directory
    return StepResult.ok();
  }
}
