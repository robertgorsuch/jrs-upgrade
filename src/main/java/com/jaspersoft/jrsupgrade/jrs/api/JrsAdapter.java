package com.jaspersoft.jrsupgrade.jrs.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The one server adapter (spec §7.2, ADR-0004). Behaviour is driven entirely by {@link
 * #capabilities()} probed after {@link #identity()}; there is no per-version subclass. Invariants:
 * every method is safe to call repeatedly; downloads stream to disk; the only repository-mutating
 * methods are {@link #startImport}, {@link #createFolder}, {@link #uploadJrxmlReport} and {@link
 * #deleteResource}, and every one of them is only ever called from inside a {@code Step}. {@link
 * #close()} ends the server session the adapter opened, never throws and may be called more than
 * once (issue #114).
 */
public interface JrsAdapter extends AutoCloseable {

  ServerIdentity identity();

  /**
   * Ends the server session this adapter established, if any, so a run leaves nothing behind that a
   * session cap or a licence could count (vendor review 3.5). A failure is logged and swallowed:
   * closing never fails an operation that already finished.
   */
  @Override
  default void close() {}

  /**
   * Re-reads the server identity from the server, ignoring and replacing any value cached by {@link
   * #identity()}. Callers that must observe the server's <em>current</em> reachability, such as the
   * step that waits for a service restart to finish, use this rather than {@link #identity()}.
   */
  ServerIdentity refreshIdentity();

  Session login(Credentials credentials);

  Handles.ExportHandle startExport(ExportRequest request);

  Handles.ExportStatus pollExport(Handles.ExportHandle handle);

  /** Streams the finished export to {@code target} and returns it. */
  Path downloadExport(Handles.ExportHandle handle, Path target);

  /**
   * As above, abandoning the transfer as soon as {@code cancelled} answers true; implementations
   * that cannot watch a transfer fall back to the plain download.
   */
  default Path downloadExport(Handles.ExportHandle handle, Path target, BooleanSupplier cancelled) {
    return downloadExport(handle, target);
  }

  Handles.ImportHandle startImport(ImportRequest request, Path archive);

  /** As above, abandoning the upload as soon as {@code cancelled} answers true. */
  default Handles.ImportHandle startImport(
      ImportRequest request, Path archive, BooleanSupplier cancelled) {
    return startImport(request, archive);
  }

  Handles.ImportStatus pollImport(Handles.ImportHandle handle);

  /**
   * Abandons an import task the server is holding ({@code DELETE /rest_v2/import/{id}}, REST
   * reference 10.1 p.124); a task that is already gone is not an error.
   */
  void cancelImport(Handles.ImportHandle handle);

  KeystoreInfo keystore();

  Set<Capability> capabilities();

  HealthReport health();

  // ---- read-only helpers used by doctor and smoke (spec §12) ----

  /** URIs of the direct children of {@code folderUri}, e.g. {@code "/"}. */
  List<String> listFolder(String folderUri);

  /**
   * URIs of every resource and folder under {@code folderUri}, at any depth, the folder itself
   * excluded (issue #100: the import rollback lists the target subtree before and after a failed
   * import and deletes the difference). This default walks {@link #listFolder} breadth-first and
   * treats a child that cannot be listed as a leaf; the REST adapter asks the server for the
   * recursive listing in pages instead.
   */
  default List<String> listTree(String folderUri) {
    java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
    java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
    queue.add(folderUri);
    while (!queue.isEmpty()) {
      String folder = queue.poll();
      List<String> children;
      try {
        children = listFolder(folder);
      } catch (RuntimeException notAFolder) {
        continue;
      }
      for (String child : children) {
        if (!child.equals(folder) && !child.equals(folderUri) && seen.add(child)) {
          queue.add(child);
        }
      }
    }
    return List.copyOf(seen);
  }

  /**
   * True when a resource (folder or otherwise) exists at {@code uri}; false when the server answers
   * 404. Export planning asks this so a mistyped {@code --uri} is refused before anything runs
   * instead of producing an empty archive (field test 2, E3).
   */
  boolean resourceExists(String uri);

  /** Runs the report at {@code reportUri} to PDF, streaming the bytes to {@code target}. */
  Path runReportToPdf(String reportUri, Path target);

  /** True when {@code GET /rest_v2/jobs} answers. */
  boolean schedulerReachable();

  // ---- mutating helpers used only by smoke --mutating, inside a Step ----

  void createFolder(String folderUri, String label);

  void uploadJrxmlReport(String folderUri, String label, Path jrxml);

  void deleteResource(String uri);
}
