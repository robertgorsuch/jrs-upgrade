package com.jaspersoft.jrsupgrade.jrs.strategy;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.core.json.Json;
import com.jaspersoft.jrsupgrade.core.secrets.SecretResolver;
import com.jaspersoft.jrsupgrade.jrs.api.ImportRequest;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsupgrade.jrs.api.KeystoreInfo;
import com.jaspersoft.jrsupgrade.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsupgrade.jrs.vendor.BuildomaticResolution;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Backs up the server's {@code .jrsks} and {@code .jrsksp} before an import that names a source
 * keystore (spec §9.3), so a rollback can put them back. Invariants: the backup is taken once per
 * run and never overwritten; nothing here touches the vendor tools. The keystore options themselves
 * ({@code --keystore}, {@code --storepass}) are options of the archive import and ride on {@code
 * RunJsImport} (review finding 2.6): the 10.0.0 importer documents {@code keystore} as "import the
 * key from a java keystore" and has no keystore-only command, so the separate invocation this step
 * used to run could never succeed.
 */
final class ImportSourceKeystore implements Step {

  static final String ID = "import.source-keystore";
  static final String MANIFEST = "keystore-backup.json";
  static final String BACKUP_DIR = "keystore-backup";

  /**
   * One server keystore file and where its copy went; {@code backup} empty when it did not exist.
   */
  record Entry(Path original, Optional<Path> backup) {}

  record Manifest(List<Entry> entries) {
    Manifest {
      entries = List.copyOf(entries);
    }
  }

  private final String phase;
  private final ImportRequest request;
  private final Path sourceKeystore;
  private final VendorAccess vendor;

  ImportSourceKeystore(String phase, ImportRequest request, VendorAccess vendor) {
    this.phase = Objects.requireNonNull(phase, "phase");
    this.request = Objects.requireNonNull(request, "request");
    this.sourceKeystore =
        request
            .sourceKeystore()
            .orElseThrow(() -> new IllegalArgumentException("request has no source keystore"));
    this.vendor = Objects.requireNonNull(vendor, "vendor");
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Import source keystore";
  }

  @Override
  public String phase() {
    return phase;
  }

  @Override
  public String detail() {
    return sourceKeystore.toString();
  }

  @Override
  public CheckResult precheck(Context ctx) {
    if (!Files.isRegularFile(sourceKeystore)) {
      return CheckResult.fail(
          "source keystore " + sourceKeystore + " does not exist",
          "pass the path of the source server's .jrsks file with --source-keystore");
    }
    if (request.sourceKeystorePassword().isPresent() && !ctx.has(SecretResolver.class)) {
      return CheckResult.fail(
          "no SecretResolver registered in the run context",
          "the ops layer must register a SecretResolver so --source-keystore-password-ref can"
              + " be resolved");
    }
    Config config = ctx.service(Config.class);
    BuildomaticResolution resolved = vendor.resolve(ctx);
    if (resolved instanceof BuildomaticResolution.NotFound missing) {
      return CheckResult.fail(missing.detail(), missing.remediation());
    }
    Optional<Buildomatic> b = resolved.located();
    if (b.get().scriptFor(Buildomatic.IMPORT_SCRIPT).isEmpty()) {
      return CheckResult.fail(
          "js-import script missing in " + b.get().dir(), "check the installation is complete");
    }
    if (config.vendor().javaHome().isEmpty()) {
      return CheckResult.fail(
          "vendor.javaHome is not set", "set vendor.javaHome to the JDK buildomatic should use");
    }
    try {
      KeystoreInfo ks = ctx.service(JrsAdapter.class).keystore();
      if (ks.keystoreFile().isEmpty()) {
        return CheckResult.warn(
            "server keystore location unknown ("
                + ks.reason().orElse("")
                + "); no backup of the current keystore can be taken");
      }
    } catch (JrsUnreachableException e) {
      return CheckResult.warn("server unreachable, keystore location not verified");
    }
    return CheckResult.pass();
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    Path manifestFile = RunFiles.in(ctx, MANIFEST);
    try {
      if (RunFiles.read(manifestFile).isEmpty()) {
        backup(ctx, manifestFile);
      } else {
        Logs.info(out, ctx, this, "keystore backup already taken in " + manifestFile.getParent());
      }
    } catch (IOException e) {
      return Failures.recoverable(
          "cannot back up the server keystore: " + e.getMessage(),
          List.of(manifestFile),
          "check permissions on the run directory and the keystore files");
    }
    // Review finding 2.6: the keystore options ride on the archive import itself (js-import
    // with --input-zip plus --keystore and --storepass); the importer has no keystore-only
    // command, so the second invocation this step used to run could never succeed.
    Logs.info(
        out,
        ctx,
        this,
        "source keystore "
            + sourceKeystore
            + " backed up; its options are passed on the archive import");
    return StepResult.ok();
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    Path manifestFile = RunFiles.in(ctx, MANIFEST);
    try {
      Optional<String> json = RunFiles.read(manifestFile);
      if (json.isEmpty()) {
        return StepResult.ok();
      }
      Manifest manifest = Json.read(json.get(), Manifest.class);
      for (Entry e : manifest.entries()) {
        if (e.backup().isPresent()) {
          Files.copy(e.backup().get(), e.original(), StandardCopyOption.REPLACE_EXISTING);
          Logs.info(out, ctx, this, "restored " + e.original());
        } else if (Files.deleteIfExists(e.original())) {
          Logs.info(out, ctx, this, "removed " + e.original() + " (did not exist before)");
        }
      }
      return StepResult.ok();
    } catch (IOException | IllegalArgumentException e) {
      return Failures.recoverableWithBackups(
          "cannot restore the server keystore: " + e.getMessage(),
          List.of(),
          backups(ctx),
          "copy the files from the backup directory back by hand");
    }
  }

  private void backup(Context ctx, Path manifestFile) throws IOException {
    KeystoreInfo ks;
    try {
      ks = ctx.service(JrsAdapter.class).keystore();
    } catch (JrsUnreachableException e) {
      ks = KeystoreInfo.absent("server unreachable");
    }
    Path dir = RunFiles.in(ctx, BACKUP_DIR);
    Files.createDirectories(dir);
    List<Entry> entries = new ArrayList<>();
    for (Optional<Path> p : List.of(ks.keystoreFile(), ks.propertiesFile())) {
      if (p.isEmpty()) {
        continue;
      }
      Path original = p.get();
      if (Files.isRegularFile(original)) {
        Path copy = dir.resolve(original.getFileName().toString());
        Files.copy(original, copy, StandardCopyOption.REPLACE_EXISTING);
        entries.add(new Entry(original, Optional.of(copy)));
      } else {
        entries.add(new Entry(original, Optional.empty()));
      }
    }
    Files.createDirectories(manifestFile.getParent());
    Path tmp = manifestFile.resolveSibling(manifestFile.getFileName() + ".tmp");
    Files.writeString(tmp, Json.write(new Manifest(entries)), StandardCharsets.UTF_8);
    RunFiles.replace(tmp, manifestFile);
  }

  private List<Path> backups(Context ctx) {
    Path dir = RunFiles.in(ctx, BACKUP_DIR);
    return Files.isDirectory(dir) ? List.of(dir) : List.of();
  }
}
