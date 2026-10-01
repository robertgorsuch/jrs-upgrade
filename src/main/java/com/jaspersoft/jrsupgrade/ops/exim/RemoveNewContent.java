package com.jaspersoft.jrsupgrade.ops.exim;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepFailure;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The rollback anchor for folders an import creates from nothing (issue #139, ADR-0036): when the
 * archive's folder does not exist on the server, a failed import leaves whatever it managed to
 * create, and no snapshot or listing covers that (they cover folders that already existed). It is
 * in every import plan whose sidecar names specific folders, whether or not they exist at plan
 * time, so a plan rebuilt for {@code runs recover} after the import has created them has the same
 * steps; what is new is decided when the step runs. Invariants: {@link #execute} records, in the
 * run directory ({@link #recordFile}), the topmost missing ancestor of each folder that does not
 * exist right then, and writes nothing to the server; an organisation's own folder is never
 * recorded, because resources cannot delete it (the operator is told to use {@code DELETE
 * /rest_v2/organizations/<id>}); {@code /} is never recorded; the step declares itself mutating
 * although it writes nothing to the repository, because the Runner compensates mutating steps only
 * and this compensation must run whenever a later step of the phase fails; {@link #compensate}
 * deletes each recorded root that exists now, deepest first, and a root that is already gone is not
 * an error, so a repeated compensation deletes nothing twice; a deletion that fails makes the step
 * fail with the roots left behind (rollback incomplete, exit 4) instead of claiming a rollback that
 * did not happen; a record that is missing means the step never ran, so nothing was imported and
 * there is nothing to remove.
 */
final class RemoveNewContent implements Step {

  static final String ID = "import.new-content-rollback";
  static final String FILE = "new-content-roots.txt";

  /** {@code /organizations/<id>}: an organisation's own folder. */
  private static final Pattern ORGANIZATION_FOLDER = Pattern.compile("/organizations/[^/]+");

  private final String phase;
  private final List<String> uris;

  RemoveNewContent(String phase, List<String> uris) {
    this.phase = Objects.requireNonNull(phase, "phase");
    this.uris = List.copyOf(Objects.requireNonNull(uris, "uris"));
  }

  static Path recordFile(Context ctx) {
    return ctx.home().runDir(ctx.runId()).resolve(FILE);
  }

  /**
   * The topmost folder that does not exist yet on the way to each of {@code uris}: for {@code
   * /a/b/c} with only {@code /a} present it is {@code /a/b}; nothing for a URI that exists; a root
   * below another root is dropped, and {@code /} never appears.
   */
  static Set<String> newRoots(JrsAdapter adapter, List<String> uris) {
    List<String> tops = new ArrayList<>();
    for (String uri : uris) {
      String top = null;
      String current = uri;
      while (current != null && !current.isEmpty() && !current.equals("/")) {
        if (adapter.resourceExists(current)) {
          break;
        }
        top = current;
        current = parent(current);
      }
      if (top != null) {
        tops.add(top);
      }
    }
    Set<String> roots = new TreeSet<>();
    for (String candidate : tops) {
      if (tops.stream().noneMatch(other -> !other.equals(candidate) && covers(other, candidate))) {
        roots.add(candidate);
      }
    }
    return roots;
  }

  /** Of {@code roots}, the ones resources can delete: an organisation's own folder is not. */
  static List<String> removable(Set<String> roots) {
    return roots.stream().filter(r -> !ORGANIZATION_FOLDER.matcher(r).matches()).toList();
  }

  /**
   * Of {@code roots}, the organisation folders the import would create and jrs-upgrade cannot
   * remove.
   */
  static List<String> organisations(Set<String> roots) {
    return roots.stream().filter(r -> ORGANIZATION_FOLDER.matcher(r).matches()).toList();
  }

  private static boolean covers(String ancestor, String uri) {
    return uri.startsWith(ancestor.endsWith("/") ? ancestor : ancestor + "/");
  }

  private static String parent(String uri) {
    int slash = uri.lastIndexOf('/');
    return slash <= 0 ? null : uri.substring(0, slash);
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Enable new-content rollback";
  }

  @Override
  public String phase() {
    return phase;
  }

  @Override
  public String detail() {
    return "removes what the import creates under " + String.join(", ", uris) + " if it fails";
  }

  /** True although nothing is written to the repository: see the class comment. */
  @Override
  public boolean mutating() {
    return true;
  }

  @Override
  public CheckResult precheck(Context ctx) {
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    Set<String> roots;
    try {
      roots = newRoots(ctx.service(JrsAdapter.class), uris);
    } catch (RuntimeException e) {
      return StepResult.failed(
          StepFailure.recoverable(
              "cannot tell which of "
                  + String.join(", ", uris)
                  + " exist: "
                  + e.getMessage()
                  + "; without that a rollback could not remove what the import creates",
              "check that the server answers GET /rest_v2/resources, then run again"));
    }
    for (String organisation : organisations(roots)) {
      EximLogs.warn(
          out,
          ctx,
          this,
          organisation
              + " is an organisation that does not exist yet; if the import fails jrs-upgrade cannot"
              + " remove it through resources, delete it with DELETE /rest_v2/organizations/"
              + organisation.substring(organisation.lastIndexOf('/') + 1));
    }
    List<String> removable = removable(roots);
    Path file = recordFile(ctx);
    try {
      Files.createDirectories(file.getParent());
      Files.write(file, removable, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return StepResult.failed(
          StepFailure.recoverable(
              "cannot write " + file + ": " + e.getMessage(),
              "free the run directory, then run again"));
    }
    EximLogs.info(
        out,
        ctx,
        this,
        removable.isEmpty()
            ? "every folder the archive names exists already; nothing to remove on a failure"
            : "does not exist yet, removed again if the import fails: "
                + String.join(", ", removable));
    return StepResult.ok();
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    Path file = recordFile(ctx);
    if (!Files.isRegularFile(file)) {
      EximLogs.info(out, ctx, this, "no record of new content: the import never started");
      return StepResult.ok();
    }
    List<String> roots;
    try {
      roots = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      return failed("cannot read " + file + ": " + e.getMessage(), List.of());
    }
    roots.removeIf(String::isBlank);
    if (roots.isEmpty()) {
      EximLogs.info(out, ctx, this, "no new folder was recorded; nothing to remove");
      return StepResult.ok();
    }
    roots.sort(Comparator.comparingInt(RemoveNewContent::depth).reversed());
    JrsAdapter adapter = ctx.service(JrsAdapter.class);
    List<String> left = new ArrayList<>();
    int removed = 0;
    for (String root : roots) {
      try {
        if (!adapter.resourceExists(root)) {
          EximLogs.info(out, ctx, this, root + " is not there; the import did not create it");
          continue;
        }
        adapter.deleteResource(root);
        removed++;
        EximLogs.info(out, ctx, this, "deleted " + root + " (created by the failed import)");
      } catch (RuntimeException e) {
        left.add(root);
        EximLogs.warn(out, ctx, this, "cannot delete " + root + ": " + e.getMessage());
      }
    }
    if (!left.isEmpty()) {
      return failed(
          left.size() + " of " + roots.size() + " folders the failed import created remain", left);
    }
    EximLogs.info(
        out,
        ctx,
        this,
        removed + " folder(s) the failed import created deleted with their content");
    return StepResult.ok();
  }

  private static StepResult failed(String cause, List<String> left) {
    return StepResult.failed(
        StepFailure.recoverable(
            cause + (left.isEmpty() ? "" : ": " + String.join(", ", left)),
            "delete them by hand (DELETE /rest_v2/resources<uri>, or the repository page) and"
                + " check the repository"));
  }

  private static int depth(String uri) {
    return uri.length() - uri.replace("/", "").length();
  }
}
