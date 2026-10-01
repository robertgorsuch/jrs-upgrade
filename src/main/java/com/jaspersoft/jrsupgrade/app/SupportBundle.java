package com.jaspersoft.jrsupgrade.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.engine.RunRecord;
import com.jaspersoft.jrsupgrade.core.engine.Runner;
import com.jaspersoft.jrsupgrade.core.engine.Transition;
import com.jaspersoft.jrsupgrade.core.json.Json;
import com.jaspersoft.jrsupgrade.core.platform.TomcatLayout;
import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.core.state.StateStore;
import com.jaspersoft.jrsupgrade.core.state.StoredPlan;
import com.jaspersoft.jrsupgrade.jrs.api.ServerIdentity;
import com.jaspersoft.jrsupgrade.jrs.vendor.BuildomaticLocator;
import com.jaspersoft.jrsupgrade.jrs.vendor.BuildomaticResolution;
import com.jaspersoft.jrsupgrade.ops.ConfigShow;
import com.jaspersoft.jrsupgrade.ops.Services;
import com.jaspersoft.jrsupgrade.ops.doctor.DoctorOperation;
import com.jaspersoft.jrsupgrade.ops.doctor.DoctorOptions;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The zip behind {@code jrs-upgrade runs support-bundle} (spec §12.4): {@code run.json} (which
 * carries the stored plan, with its {@code {runId}} placeholders filled in, #161), {@code
 * transitions.jsonl}, {@code server.json}, {@code doctor.json}, {@code config-redacted.yaml}, the
 * run's lines of the JSON log and a short tail of it for context (#160). Invariants: every byte
 * passes through the {@link Redactor} on its way into the archive, so a registered secret cannot
 * appear in any encoding the redactor knows; everything that can fail, which is the live doctor run
 * and the server probe behind it, runs in {@link #prepare} before the file is created, so a failure
 * is an exit-2 or exit-4 message and never a partial zip (review 4.8); the archive is then streamed
 * entry by entry and the log lines are kept in bounded deques, so memory stays flat whatever the
 * log size; a missing optional input yields no entry rather than an error; the log is the file the
 * running process actually writes, named by {@link LogFile#PROPERTY}, not a guess at its location.
 */
public final class SupportBundle {

  static final int LOG_TAIL_LINES = 2000;

  /** Lines of the whole log kept beside the run's own, for what happened around it (#160). */
  static final int LOG_CONTEXT_LINES = 200;

  private final Services services;
  private final Redactor redactor;

  public SupportBundle(Services services) {
    this.services = Objects.requireNonNull(services, "services");
    this.redactor = services.redactor();
  }

  /**
   * Everything that has to be computed before the first byte is written: the live doctor report and
   * the server probe behind it, plus the documents that read the state store (review 4.8).
   */
  public record Prepared(
      RunRecord run, String runJson, String serverJson, String doctorJson, String configYaml) {}

  /** Runs everything that can fail. Throws before any byte of the archive is written. */
  public Prepared prepare(RunRecord run) {
    StateStore store = services.stateStore().get();
    Optional<StoredPlan> plan = run.planId().flatMap(store::loadPlan);
    Map<String, Object> runDoc =
        RunsCommand.showTree(
            run,
            plan.map(p -> RunsCommand.parse(p.planJson())),
            store.transitions(run.runId()),
            store.snapshots(run.runId()),
            services.clock());
    return new Prepared(
        run,
        withRunId(Json.writePretty(runDoc), run.runId()),
        Json.writePretty(serverDocument()),
        JsonOut.write(new DoctorOperation(services).run(DoctorOptions.DEFAULT)),
        ConfigShow.render(services.config()));
  }

  /**
   * The server as the adapter sees it, or {@code reachable:false} with the configured base URL when
   * it cannot be reached: a bundle is wanted most when the server is broken, so this never throws.
   */
  private Map<String, Object> serverDocument() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("baseUrl", services.config().server().baseUrl().map(Object::toString).orElse(""));
    try {
      ServerIdentity identity = services.adapter().get().identity();
      m.put("reachable", true);
      m.put("identity", identity);
    } catch (RuntimeException e) {
      m.put("reachable", false);
      m.put("error", redactor.redact(String.valueOf(e.getMessage())));
    }
    return m;
  }

  public void write(Prepared prepared, OutputStream target) throws IOException {
    RunRecord run = prepared.run();
    StateStore store = services.stateStore().get();
    try (ZipOutputStream zip = new ZipOutputStream(target, StandardCharsets.UTF_8)) {
      text(zip, "run.json", prepared.runJson());
      zip.putNextEntry(new ZipEntry("transitions.jsonl"));
      for (Transition t : store.transitions(run.runId())) {
        line(zip, Json.write(t));
      }
      zip.closeEntry();
      text(zip, "server.json", prepared.serverJson());
      text(zip, "doctor.json", prepared.doctorJson());
      text(zip, "config-redacted.yaml", prepared.configYaml());
      Path log = logFile();
      if (Files.isRegularFile(log)) {
        zip.putNextEntry(new ZipEntry("logs/" + log.getFileName()));
        for (String l : runLines(log, run, LOG_TAIL_LINES)) {
          line(zip, l);
        }
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry("logs/tail-" + log.getFileName()));
        for (String l : tail(log, LOG_CONTEXT_LINES)) {
          line(zip, l);
        }
        zip.closeEntry();
      }
      // review §3.4 (issue #111): the vendor's own troubleshooting files, tail-capped and redacted
      for (VendorLogs.Source located : vendorSources()) {
        Optional<VendorLogs.Source> forRun = forRun(located, run);
        if (forRun.isEmpty()) {
          continue;
        }
        VendorLogs.Source source = forRun.get();
        zip.putNextEntry(new ZipEntry(source.entry()));
        try {
          VendorLogs.tail(
              source.file(),
              VendorLogs.TAIL_BYTES,
              l -> {
                try {
                  line(zip, source.masterProperties() ? VendorLogs.blankPassword(l) : l);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
        } catch (IOException | UncheckedIOException e) {
          // a vendor file that vanishes or cannot be read mid-way is a note, not a broken zip
          line(zip, "... " + source.file() + " could not be read: " + e.getMessage());
        }
        zip.closeEntry();
      }
    }
  }

  /**
   * A plan is stored before its run has an id, so paths in it read {@code snapshots/{runId}/...};
   * the bundle belongs to one run, so it names that run instead (#161).
   */
  static String withRunId(String json, String runId) {
    return json.replace("{runId}", runId);
  }

  /** Slack after a run's end for a vendor log's last write to land (#161). */
  static final Duration VENDOR_LOG_SLACK = Duration.ofMinutes(2);

  /**
   * The vendor file to bundle for {@code run} in place of {@code source} (#161). A buildomatic
   * script log is written by one vendor run, so the run's own is the newest one in that directory
   * last written while the run executed (from its start to {@link #VENDOR_LOG_SLACK} after its end,
   * or up to now for a run still pending); a log from before the run or from a later run is never
   * bundled, and a run that ran no vendor script gets none. The server's own logs, the installer
   * log and the properties are context whatever their age, and are kept as they are.
   */
  static Optional<VendorLogs.Source> forRun(VendorLogs.Source source, RunRecord run) {
    String prefix = VendorLogs.ENTRY_PREFIX + "buildomatic/";
    if (!source.entry().startsWith(prefix)) {
      return Optional.of(source);
    }
    Path dir = source.file().toAbsolutePath().getParent();
    if (dir == null) {
      return Optional.empty();
    }
    Instant to = run.endedAt().map(e -> e.plus(VENDOR_LOG_SLACK)).orElse(Instant.MAX);
    return VendorLogs.newestBetween(dir, VendorLogs.BUILDOMATIC_GLOB, run.startedAt(), to)
        .map(p -> new VendorLogs.Source(prefix + p.getFileName(), p, false));
  }

  /** The vendor files this installation has, from the configuration and the buildomatic lookup. */
  List<VendorLogs.Source> vendorSources() {
    Config.Server server = services.config().server();
    Optional<TomcatLayout> layout =
        server.installDir().flatMap(d -> services.platform().detectTomcat(d));
    Optional<Path> tomcatDir = server.tomcatDir().or(() -> layout.map(TomcatLayout::tomcatDir));
    Optional<Path> webappDir =
        tomcatDir.flatMap(
            t ->
                server
                    .webappName()
                    .map(n -> t.resolve("webapps").resolve(n.yamlValue()))
                    .or(() -> layout.map(TomcatLayout::webappDir)));
    Optional<Path> buildomatic =
        switch (new BuildomaticLocator(services.platform()).resolve(services.config())) {
          case BuildomaticResolution.Found found -> Optional.of(found.buildomatic().dir());
          case BuildomaticResolution.NotFound missing -> Optional.empty();
        };
    return VendorLogs.locate(server.installDir(), tomcatDir, webappDir, buildomatic);
  }

  /**
   * The log this process is writing (review 4.8). {@code Main} always sets the property, from
   * {@code --home}, {@code JRS_UPGRADE_HOME} or the platform default; the home-relative path is
   * only a fallback for a test that runs the CLI in-process without going through {@code Main}.
   */
  Path logFile() {
    Path underHome = services.home().root().resolve("logs").resolve("jrs-upgrade.log");
    String configured = System.getProperty(LogFile.PROPERTY);
    if (configured == null || configured.isBlank()) {
      return underHome;
    }
    // The property is what Main set from --home, so the two are normally the same file. When the
    // property names a file that is not there, the home's log is the better answer: a bundle with
    // no log in it is worse than a bundle with the log the running process has been writing.
    Path named = Path.of(configured);
    return Files.isRegularFile(named) ? named : underHome;
  }

  /**
   * The lines of the JSON log that belong to {@code run}, at most the last {@code limit} of them,
   * read with flat memory (#160): a line whose {@code runId} is the run's, and a line with no
   * {@code runId} written inside the run's time window (another thread, or a build before #160). A
   * line of another run, a line outside the window and a line that is not JSON are left out.
   */
  static Deque<String> runLines(Path file, RunRecord run, int limit) throws IOException {
    Instant from = run.startedAt();
    Instant to = run.endedAt().orElse(Instant.MAX);
    Deque<String> lines = new ArrayDeque<>(limit);
    boolean truncated = false;
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String l;
      while ((l = reader.readLine()) != null) {
        if (!belongsTo(l, run.runId(), from, to)) {
          continue;
        }
        if (lines.size() == limit) {
          lines.removeFirst();
          truncated = true;
        }
        lines.addLast(l);
      }
    }
    if (truncated) {
      lines.addFirst("... earlier lines of " + run.runId() + " omitted; this is the last " + limit);
    }
    return lines;
  }

  private static boolean belongsTo(String line, String runId, Instant from, Instant to) {
    JsonNode node;
    try {
      node = Json.mapper().readTree(line);
    } catch (IOException e) {
      return false;
    }
    if (node == null || !node.isObject()) {
      return false;
    }
    JsonNode id = node.path(Runner.MDC_RUN_ID);
    if (id.isTextual()) {
      return id.asText().equals(runId);
    }
    try {
      Instant ts = OffsetDateTime.parse(node.path("ts").asText()).toInstant();
      return !ts.isBefore(from) && !ts.isAfter(to);
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  /** The last {@code limit} lines of a text file, read with flat memory. */
  private static Deque<String> tail(Path file, int limit) throws IOException {
    Deque<String> lines = new ArrayDeque<>(limit);
    boolean truncated = false;
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String l;
      while ((l = reader.readLine()) != null) {
        if (lines.size() == limit) {
          lines.removeFirst();
          truncated = true;
        }
        lines.addLast(l);
      }
    }
    if (truncated) {
      lines.addFirst("... earlier lines omitted; this is the last " + limit + " of " + file);
    }
    return lines;
  }

  private void text(ZipOutputStream zip, String name, String content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(redactor.redact(content).getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private void line(ZipOutputStream zip, String content) throws IOException {
    zip.write(redactor.redact(content).getBytes(StandardCharsets.UTF_8));
    zip.write('\n');
  }
}
