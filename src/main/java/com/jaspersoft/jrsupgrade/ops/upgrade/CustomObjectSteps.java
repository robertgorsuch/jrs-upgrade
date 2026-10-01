package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.ops.db.DbObject;
import com.jaspersoft.jrsupgrade.ops.db.JdbcConnector;
import com.jaspersoft.jrsupgrade.ops.db.JdbcException;
import com.jaspersoft.jrsupgrade.ops.db.JdbcSettings;
import com.jaspersoft.jrsupgrade.ops.db.SqlScript;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Issue #3: the customer tables and sequences a newdb hop would drop with the repository database.
 * {@link #scan} finds them for the plan's warning; {@link DumpForeignSchema} writes their structure
 * into the point-B snapshot set before the vendor script runs; {@link ApplyCustomDdl} runs the
 * operator's own DDL ({@code --custom-ddl}) after it, before the server starts. Invariants: the
 * scan and the dump only read the database; the DDL runs each script once per run, its statements
 * in file order; the dump is rebuilt from metadata and says so, so the operator's DDL stays the
 * authority for re-creating the objects; their rows are not carried over.
 */
final class CustomObjectSteps {

  static final String DUMP_FOREIGN_SCHEMA = "dump-foreign-schema";
  static final String APPLY_CUSTOM_DDL = "apply-custom-ddl";
  static final String APPLIED = APPLY_CUSTOM_DDL + ".applied";

  private CustomObjectSteps() {}

  /** What the repository database holds beyond the vendor's tables, or why that is unknown. */
  sealed interface Scan {
    record NotConfigured() implements Scan {}

    record NoVendorDdl(Path dir) implements Scan {}

    record Failed(String reason) implements Scan {}

    record Found(List<DbObject> foreign) implements Scan {}
  }

  static Scan scan(UpgradeRuntime rt, Path installedBuildomatic) {
    Optional<JdbcSettings> settings = JdbcSettings.from(rt.config(), rt.services().platform());
    if (settings.isEmpty()) {
      return new Scan.NotConfigured();
    }
    Path dir = ForeignObjects.ddlDir(installedBuildomatic, settings.get().type());
    Optional<Set<String>> vendor;
    try {
      vendor = ForeignObjects.vendorNames(dir);
    } catch (IOException e) {
      return new Scan.Failed("cannot read the vendor DDL under " + dir + ": " + e.getMessage());
    }
    if (vendor.isEmpty()) {
      return new Scan.NoVendorDdl(dir);
    }
    try (JdbcConnector.Session session = settings.get().open(rt.jdbc(), rt.services().secrets())) {
      return new Scan.Found(ForeignObjects.foreign(session.objects(), vendor.get()));
    } catch (JdbcException | RuntimeException e) {
      return new Scan.Failed("cannot list the repository database's tables: " + e.getMessage());
    }
  }

  /** The plan's sentence about {@code scan} for a newdb hop; empty when there is nothing to say. */
  static Optional<String> warning(Scan scan, boolean customDdl) {
    String drops = "js-upgrade-newdb drops every table in the repository database";
    return switch (scan) {
      case Scan.NotConfigured n ->
          Optional.of(
              drops
                  + ", customer tables included, and no database section is configured, so"
                  + " jrs-upgrade cannot list them: configure database.* to have them named and"
                  + " their structure saved");
      case Scan.NoVendorDdl n ->
          Optional.of(
              drops
                  + "; no vendor DDL under "
                  + n.dir()
                  + " to tell customer tables from the vendor's, so none is named");
      case Scan.Failed f ->
          Optional.of(drops + "; customer tables cannot be listed: " + f.reason());
      case Scan.Found f when f.foreign().isEmpty() -> Optional.empty();
      case Scan.Found f ->
          Optional.of(
              "the repository database holds objects the vendor's DDL does not create ("
                  + ForeignObjects.describe(f.foreign())
                  + "); js-upgrade-newdb drops them with the database. Their structure is saved"
                  + " to the snapshot set before the vendor run"
                  + (customDdl
                      ? ", and --custom-ddl re-creates them after it"
                      : "; pass --custom-ddl <dir> with your DDL to re-create them before the"
                          + " server starts")
                  + ". Their rows are not carried over.");
    };
  }

  /** Writes the foreign objects' structure into the snapshot set (newdb only). */
  static final class DumpForeignSchema implements Step {
    private final UpgradeRuntime rt;
    private final UpgradeInput in;

    DumpForeignSchema(UpgradeRuntime rt, UpgradeInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    @Override
    public String id() {
      return DUMP_FOREIGN_SCHEMA;
    }

    @Override
    public String title() {
      return "save the structure of the customer tables newdb will drop";
    }

    @Override
    public String phase() {
      return Phases.VENDOR_UPGRADE;
    }

    @Override
    public String detail() {
      return "tables and sequences the vendor DDL under "
          + in.installedBuildomatic().resolve("install_resources")
          + " does not create -> "
          + SnapshotSet.FOREIGN_SCHEMA
          + " in the snapshot set (rebuilt from metadata)";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      Scan scan = scan(rt, in.installedBuildomatic());
      if (!(scan instanceof Scan.Found found)) {
        warning(scan, in.options().customDdl().isPresent())
            .ifPresent(w -> Logs.warn(rt, ctx, out, this, w));
        return StepResult.ok();
      }
      if (found.foreign().isEmpty()) {
        Logs.info(rt, ctx, out, this, "no customer tables or sequences in the repository database");
        return StepResult.ok();
      }
      Optional<JdbcSettings> settings = JdbcSettings.from(rt.config(), rt.services().platform());
      StringBuilder dump = new StringBuilder();
      dump.append("-- jrs-upgrade run ")
          .append(ctx.runId())
          .append(": objects js-upgrade-newdb drops that the vendor DDL does not create.\n")
          .append("-- Rebuilt from JDBC metadata (columns, types, nullability, primary key);")
          .append(" defaults, indexes, grants and rows are not here. Check before running.\n\n");
      try (JdbcConnector.Session session =
          settings.orElseThrow().open(rt.jdbc(), rt.services().secrets())) {
        for (DbObject o : found.foreign()) {
          switch (o.kind()) {
            case TABLE -> dump.append(session.tableDdl(o.name())).append(";\n\n");
            case SEQUENCE -> dump.append("CREATE SEQUENCE ").append(o.name()).append(";\n\n");
          }
        }
      } catch (JdbcException | RuntimeException e) {
        return Failures.recoverable(
            "cannot describe the customer tables: " + e.getMessage(),
            "check the database.* settings; the vendor script has not run yet");
      }
      Path file = in.snapshots(ctx).foreignSchema();
      try {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, dump.toString(), StandardCharsets.UTF_8);
        rt.files().atomicReplace(tmp, file);
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot write " + file + ": " + e.getMessage(), "free space under the snapshot set");
      }
      Logs.warn(
          rt,
          ctx,
          out,
          this,
          ForeignObjects.describe(found.foreign())
              + ": structure saved to "
              + file
              + "; js-upgrade-newdb drops them");
      return StepResult.ok();
    }

    /* The dump is a backup artefact in the snapshot set, like the full export: nothing to undo. */
    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return StepResult.ok();
    }
  }

  /** Runs the operator's {@code --custom-ddl} scripts against the database newdb rebuilt. */
  static final class ApplyCustomDdl implements Step {
    private final UpgradeRuntime rt;
    private final UpgradeInput in;
    private final Path dir;

    ApplyCustomDdl(UpgradeRuntime rt, UpgradeInput in, Path dir) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
      this.dir = Objects.requireNonNull(dir, "dir");
    }

    private Path applied(Context ctx) {
      return in.runDir(ctx).resolve(APPLIED);
    }

    @Override
    public String id() {
      return in.scoped(APPLY_CUSTOM_DDL);
    }

    @Override
    public String title() {
      return "re-create the customer tables with --custom-ddl";
    }

    @Override
    public String phase() {
      return Phases.VENDOR_UPGRADE;
    }

    @Override
    public String detail() {
      return "every *.sql in " + dir + " in name order, once per run, before the server starts";
    }

    List<Path> scripts() throws IOException {
      List<Path> out = new ArrayList<>();
      try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.sql")) {
        for (Path f : files) {
          if (Files.isRegularFile(f)) {
            out.add(f);
          }
        }
      }
      out.sort(null);
      return out;
    }

    /*
     * Not irreversible, and no compensation of its own: the statements change the database
     * js-upgrade-newdb has just created, which no step can give back; the vendor step's
     * compensation restores point B and "upgrade rollback --restore-database" rebuilds the old
     * database from the point-B export, which drops these tables with the rest (ADR-0029).
     */
    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      return StepResult.ok();
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (JdbcSettings.from(rt.config(), rt.services().platform()).isEmpty()) {
        return CheckResult.fail(
            "--custom-ddl needs a database connection and database.* is not configured",
            "configure database.type, database.url and the credentials in config.yaml");
      }
      try {
        if (!Files.isDirectory(dir) || scripts().isEmpty()) {
          return CheckResult.fail(
              "--custom-ddl " + dir + " holds no *.sql script", "point it at your DDL directory");
        }
      } catch (IOException e) {
        return CheckResult.fail("cannot read " + dir + ": " + e.getMessage(), "check access");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      Set<String> done = new LinkedHashSet<>();
      try {
        if (Files.isRegularFile(applied(ctx))) {
          done.addAll(Files.readAllLines(applied(ctx), StandardCharsets.UTF_8));
        }
        Files.createDirectories(in.runDir(ctx));
        try (JdbcConnector.Session session =
            JdbcSettings.from(rt.config(), rt.services().platform())
                .orElseThrow()
                .open(rt.jdbc(), rt.services().secrets())) {
          for (Path script : scripts()) {
            ctx.cancel().checkpoint();
            String name = script.getFileName().toString();
            if (done.contains(name)) {
              Logs.info(rt, ctx, out, this, name + " already applied in this run");
              continue;
            }
            for (String statement : SqlScript.read(script)) {
              session.executeStatement(statement);
            }
            Files.writeString(
                applied(ctx),
                name + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
            Logs.info(rt, ctx, out, this, "applied " + script);
          }
        }
        return StepResult.ok();
      } catch (IOException | JdbcException | RuntimeException e) {
        return Failures.recoverable(
            "custom DDL failed: " + Failures.describe(e),
            "fix the script and resume; scripts listed in "
                + applied(ctx)
                + " are not run again, and the structure saved before the vendor run is under the"
                + " snapshot set");
      }
    }
  }
}
