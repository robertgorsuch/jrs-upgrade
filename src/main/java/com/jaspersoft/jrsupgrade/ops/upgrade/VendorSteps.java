package com.jaspersoft.jrsupgrade.ops.upgrade;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.core.platform.Durability;
import com.jaspersoft.jrsupgrade.core.secrets.Secret;
import com.jaspersoft.jrsupgrade.core.secrets.SecretException;
import com.jaspersoft.jrsupgrade.core.secrets.SecretRef;
import com.jaspersoft.jrsupgrade.core.snapshot.Snapshot;
import com.jaspersoft.jrsupgrade.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsupgrade.jrs.api.KeystoreInfo;
import com.jaspersoft.jrsupgrade.jrs.api.ServerIdentity;
import com.jaspersoft.jrsupgrade.jrs.keystore.KeystoreInspector;
import com.jaspersoft.jrsupgrade.jrs.rest.RestException;
import com.jaspersoft.jrsupgrade.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsupgrade.jrs.vendor.MasterProperties;
import com.jaspersoft.jrsupgrade.jrs.vendor.VendorRun;
import com.jaspersoft.jrsupgrade.jrs.vendor.VendorTools;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Phase C of spec §10.2: stage {@code default_master.properties} into the target package's
 * buildomatic directory (spec §7.4) and run the vendor upgrade there. Invariants: the staged
 * properties never contain a password (spec §7.4, Q5); the vendor script runs with {@code
 * JAVA_HOME=vendor.javaHome}, its output streamed as redacted log events; the compensation of the
 * vendor run is the point-B restore of spec §10.1. Two markers in the run directory keep the script
 * from running twice: an attempt marker written and forced to disk <em>before</em> the script is
 * launched, and a done marker written after it reports success. A resume that finds the attempt
 * marker without the done marker cannot know whether the vendor script already changed the
 * repository database ({@code js-upgrade-samedb} migrates it in place, {@code js-upgrade-newdb}
 * drops and recreates it from the point-B full export; ADR-0012), which jrs-upgrade cannot undo
 * (spec §10.1), so it refuses rather than guess. {@code js-upgrade-newdb} is always given the
 * point-B full export as its argument: the vendor wrapper refuses to run without one.
 */
final class VendorSteps {

  static final String WRITE_MASTER_PROPERTIES = "write-master-properties";
  static final String STAGE_KEYSTORE_INIT = "stage-keystore-init";
  static final String RUN_VENDOR_UPGRADE = "run-vendor-upgrade";
  static final String STOP_SERVICE = "stop-service";
  static final String START_SERVICE = "start-service";
  static final String WAIT_FOR_SERVER = "wait-for-server";
  static final String DONE_MARKER = RUN_VENDOR_UPGRADE + ".done";
  static final String ATTEMPT_MARKER = RUN_VENDOR_UPGRADE + ".attempted";
  static final String APP_SERVER_DIR = "appServerDir";
  static final String APP_SERVER_TYPE = "appServerType";
  static final String TOMCAT = "tomcat";

  /** Vendor script names of spec §10.2 step 10, tried first when the package ships them. */
  static final String SCRIPT_PREFIX = "js-upgrade-";

  /**
   * Ant target the vendor wrapper itself selects ({@code bin/do-js-upgrade}: {@code
   * upgrade-minimal-<ce|pro>} with {@code -Dstrategy=standard|inDatabase}), used through {@code
   * js-ant} when a package ships no wrapper script.
   */
  static final String ANT_TARGET_PREFIX = "upgrade-minimal-";

  /** The {@code -Dstrategy} value {@code do-js-upgrade} passes for each mode. */
  static final String STRATEGY_NEWDB = "standard";

  static final String STRATEGY_SAMEDB = "inDatabase";

  private VendorSteps() {}

  static Map<String, String> masterOverrides(Map<String, String> installed, Path tomcatDir) {
    return masterOverrides(installed, tomcatDir, Optional.empty());
  }

  /**
   * The buildomatic property naming the key an import decrypts with: {@code bin/import-export.xml}
   * passes it as {@code --keyalias} to every import, so an export taken on another server and
   * encrypted with a shared alias (jrs-upgrade's {@code export --portable}) can be imported by the
   * newdb upgrade (ADR-0028; the script itself takes no key argument).
   */
  static final String KEY_ALIAS_PROPERTY = "deprecatedImportExportEncSecret.keyalias";

  /**
   * Its password, when the alias has one; the one password key jrs-upgrade writes itself
   * (ADR-0028).
   */
  static final String KEY_PASS_PROPERTY = "deprecatedImportExportEncSecret.keypass";

  static Map<String, String> masterOverrides(
      Map<String, String> installed, Path tomcatDir, Optional<String> keyAlias) {
    Map<String, String> overrides = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : installed.entrySet()) {
      if (!MasterProperties.isPasswordKey(e.getKey())) {
        overrides.put(e.getKey(), e.getValue());
      }
    }
    overrides.put(APP_SERVER_TYPE, TOMCAT);
    overrides.put(APP_SERVER_DIR, tomcatDir.toAbsolutePath().normalize().toString());
    keyAlias.ifPresent(alias -> overrides.put(KEY_ALIAS_PROPERTY, alias));
    return Map.copyOf(overrides);
  }

  /**
   * Review §1.4: buildomatic resolves the server keystore through {@code keystore.init.properties}
   * ({@code ks}, {@code ksp}) in the buildomatic directory it runs from, then the home of the
   * account running it (security guide 10.1 pp.11-13). A freshly unpacked target package has no
   * such file, and jrs-upgrade runs the vendor script as its own account, so without this step
   * {@code setup.xml}'s {@code create-ks} makes a new keystore, silently when {@code
   * BUILDOMATIC_MODE} is not {@code interactive}, and every password the repository holds becomes
   * undecryptable. The step writes the file into the target buildomatic: a verbatim copy of the
   * installation's own when it has one, else {@code ks}/{@code ksp} from where the adapter found
   * {@code .jrsks} and {@code .jrsksp}. Invariants: a file already there is kept under the run
   * directory and put back by compensation; a file this step wrote where none was is removed by
   * compensation; both are idempotent; when no location is known the precheck refuses, because the
   * vendor script would then create a keystore.
   */
  static final class StageKeystoreInit implements Step {

    static final String BACKUP_SUFFIX = ".bak";
    static final String WRITTEN_MARKER = STAGE_KEYSTORE_INIT + ".written";

    private final UpgradeRuntime rt;
    private final UpgradeInput in;

    StageKeystoreInit(UpgradeRuntime rt, UpgradeInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    private Path buildomaticDir() {
      return in.targetBuildomatic().orElse(in.target().dir().resolve(UpgradeInput.BUILDOMATIC));
    }

    private Path target() {
      return buildomaticDir().resolve(KeystoreInspector.INIT_PROPERTIES);
    }

    private Path installedFile() {
      return in.installedBuildomatic().resolve(KeystoreInspector.INIT_PROPERTIES);
    }

    private Path backup(Context ctx) {
      return ctx.home()
          .runDir(ctx.runId())
          .resolve(KeystoreInspector.INIT_PROPERTIES + BACKUP_SUFFIX);
    }

    private Path marker(Context ctx) {
      return ctx.home().runDir(ctx.runId()).resolve(WRITTEN_MARKER);
    }

    @Override
    public String id() {
      return STAGE_KEYSTORE_INIT;
    }

    @Override
    public String title() {
      return "point the target buildomatic at the server's keystore";
    }

    @Override
    public String phase() {
      return Phases.VENDOR_UPGRADE;
    }

    @Override
    public String detail() {
      return target()
          + (Files.isRegularFile(installedFile())
              ? " (copy of " + installedFile() + ")"
              : " (ks/ksp from the keystore the server uses)");
    }

    /** Where the file's content comes from; empty when no keystore location is known. */
    private Optional<Source> source() {
      if (Files.isRegularFile(installedFile())) {
        return Optional.of(new Source.Copy(installedFile()));
      }
      KeystoreInfo info;
      try {
        info = rt.services().adapter().get().keystore();
      } catch (JrsUnreachableException | RestException e) {
        return Optional.empty();
      }
      if (!info.present() || info.keystoreFile().isEmpty()) {
        return Optional.empty();
      }
      Path ks = info.keystoreFile().get().toAbsolutePath().normalize().getParent();
      Path ksp =
          info.propertiesFile().map(p -> p.toAbsolutePath().normalize().getParent()).orElse(ks);
      return Optional.of(new Source.Locations(ks, ksp));
    }

    private sealed interface Source permits Source.Copy, Source.Locations {
      record Copy(Path file) implements Source {}

      record Locations(Path ks, Path ksp) implements Source {}
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (!Files.isDirectory(buildomaticDir())) {
        return CheckResult.fail(
            "target buildomatic " + buildomaticDir() + " does not exist",
            "point --package at the unpacked distribution");
      }
      if (!rt.files().isWritable(buildomaticDir())) {
        return CheckResult.fail(
            buildomaticDir() + " is not writable",
            "grant jrs-upgrade write access to the target package");
      }
      if (source().isEmpty()) {
        return CheckResult.fail(
            "no keystore location is known: neither "
                + installedFile()
                + " nor the server's .jrsks could be found, so the vendor upgrade script would"
                + " create a new keystore and the repository's passwords would become"
                + " undecryptable",
            "set server.runAsUser to the account that installed the server, or write ks= and"
                + " ksp= into "
                + installedFile()
                + " (security guide: keystore.init.properties)");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      Optional<Source> source = source();
      if (source.isEmpty()) {
        return Failures.recoverable(
            "no keystore location is known; the vendor script would create a new keystore",
            "set server.runAsUser to the installing account or write " + installedFile());
      }
      Path target = target();
      Path backup = backup(ctx);
      try {
        Files.createDirectories(backup.getParent());
        if (Files.isRegularFile(target)
            && !Files.exists(backup)
            && !Files.isRegularFile(marker(ctx))) {
          Files.copy(target, backup, StandardCopyOption.COPY_ATTRIBUTES);
        }
        Path tmp = target.resolveSibling(KeystoreInspector.INIT_PROPERTIES + ".jrs-upgrade-tmp");
        switch (source.get()) {
          case Source.Copy c -> Files.copy(c.file(), tmp, StandardCopyOption.REPLACE_EXISTING);
          case Source.Locations l -> {
            // Properties.store escapes the paths (backslashes, colons) but also stamps the
            // current time as a comment, which would make every re-execution write different
            // bytes; only the key lines are kept
            Properties p = new Properties();
            p.setProperty("ks", l.ks().toString());
            p.setProperty("ksp", l.ksp().toString());
            StringWriter buffer = new StringWriter();
            p.store(buffer, null);
            String content =
                buffer
                    .toString()
                    .lines()
                    .filter(line -> !line.startsWith("#"))
                    .sorted()
                    .collect(Collectors.joining("\n", "", "\n"));
            Files.writeString(tmp, content, StandardCharsets.ISO_8859_1);
          }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(marker(ctx), "written", StandardCharsets.UTF_8);
        Logs.info(
            rt,
            ctx,
            out,
            this,
            switch (source.get()) {
              case Source.Copy c -> "copied " + c.file() + " to " + target;
              case Source.Locations l ->
                  "wrote " + target + " (ks=" + l.ks() + ", ksp=" + l.ksp() + ")";
            });
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot write " + target + ": " + e.getMessage(),
            "check permissions on the target package");
      }
    }

    @Override
    public CheckResult postcheck(Context ctx) {
      Path target = target();
      if (!Files.isRegularFile(target)) {
        return CheckResult.fail(target + " was not written", "run again");
      }
      Properties p = new Properties();
      try (var reader = Files.newBufferedReader(target, StandardCharsets.ISO_8859_1)) {
        p.load(reader);
      } catch (IOException e) {
        return CheckResult.fail("cannot read " + target + ": " + e.getMessage(), "run again");
      }
      return p.getProperty("ks", "").isBlank()
          ? CheckResult.fail(target + " names no ks location", "run again")
          : CheckResult.pass();
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      Path target = target();
      Path backup = backup(ctx);
      try {
        if (Files.isRegularFile(backup)) {
          Files.copy(
              backup,
              target,
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.COPY_ATTRIBUTES);
          Files.deleteIfExists(backup);
          Files.deleteIfExists(marker(ctx));
          Logs.info(rt, ctx, out, this, "restored the package's own " + target);
        } else if (Files.isRegularFile(marker(ctx))) {
          Files.deleteIfExists(target);
          Files.deleteIfExists(marker(ctx));
          Logs.info(rt, ctx, out, this, "removed " + target);
        }
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot restore " + target + ": " + e.getMessage(),
            "check permissions on the target package");
      }
    }
  }

  static final class WriteMasterProperties implements Step {
    private final UpgradeRuntime rt;
    private final UpgradeInput in;

    WriteMasterProperties(UpgradeRuntime rt, UpgradeInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    private Path buildomaticDir() {
      return in.targetBuildomatic().orElse(in.target().dir().resolve(UpgradeInput.BUILDOMATIC));
    }

    @Override
    public String id() {
      return WRITE_MASTER_PROPERTIES;
    }

    @Override
    public String title() {
      return "write default_master.properties into the target buildomatic";
    }

    @Override
    public String phase() {
      return Phases.VENDOR_UPGRADE;
    }

    @Override
    public String detail() {
      return buildomaticDir().resolve(Buildomatic.MASTER_PROPERTIES)
          + " ("
          + in.masterOverrides().size()
          + " keys, no passwords; existing file snapshotted)";
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (!Files.isDirectory(buildomaticDir())) {
        return CheckResult.fail(
            "target buildomatic " + buildomaticDir() + " does not exist",
            "point --package at the unpacked distribution");
      }
      if (!rt.files().isWritable(buildomaticDir())) {
        return CheckResult.fail(
            buildomaticDir() + " is not writable",
            "grant jrs-upgrade write access to the target package");
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      Path runDir = ctx.home().runDir(ctx.runId());
      Map<String, String> overrides = new LinkedHashMap<>(in.masterOverrides());
      Optional<SecretRef> keyPassword = in.options().keyPassword();
      if (keyPassword.isPresent()) {
        // ADR-0028: the key's password is the one password key jrs-upgrade writes itself, resolved
        // here rather than at plan time, and registered with the redactor first
        try (Secret secret = rt.services().secrets().resolve(keyPassword.get())) {
          rt.services().redactor().register(secret);
          char[] chars = secret.chars();
          try {
            overrides.put(KEY_PASS_PROPERTY, new String(chars));
          } finally {
            Arrays.fill(chars, '\0');
          }
        } catch (SecretException e) {
          return Failures.recoverable(
              "cannot resolve the key password: " + e.getMessage(),
              "fix " + keyPassword.get().render());
        }
      }
      try {
        MasterProperties.Staged staged =
            MasterProperties.stage(buildomaticDir(), overrides, runDir);
        Logs.info(
            rt,
            ctx,
            out,
            this,
            "wrote "
                + staged.file()
                + staged.backup().map(b -> " (previous copy kept at " + b + ")").orElse(""));
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot write "
                + buildomaticDir().resolve(Buildomatic.MASTER_PROPERTIES)
                + ": "
                + e.getMessage(),
            "check permissions on the target package");
      }
    }

    @Override
    public CheckResult postcheck(Context ctx) {
      Path file = buildomaticDir().resolve(Buildomatic.MASTER_PROPERTIES);
      return Files.isRegularFile(file)
          ? CheckResult.pass()
          : CheckResult.fail(file + " was not written", "run again");
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      try {
        MasterProperties.restore(buildomaticDir(), ctx.home().runDir(ctx.runId()));
        return StepResult.ok();
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot restore "
                + buildomaticDir().resolve(Buildomatic.MASTER_PROPERTIES)
                + ": "
                + e.getMessage(),
            "restore the file by hand from "
                + MasterProperties.backupFor(ctx.home().runDir(ctx.runId())));
      }
    }
  }

  /** {@code ce} or {@code pro}: from the server identity, else from the webapp name. */
  static String edition(UpgradeInput in) {
    ServerIdentity.Edition edition =
        in.identity().map(ServerIdentity::edition).orElse(ServerIdentity.Edition.UNKNOWN);
    return switch (edition) {
      case CE -> "ce";
      case PRO -> "pro";
      case UNKNOWN -> in.webappName().endsWith("-pro") ? "pro" : "ce";
    };
  }

  /** The {@code -Dstrategy} value the vendor wrapper passes for the mode. */
  static String strategy(UpgradeOperations.Mode mode) {
    return switch (mode) {
      case NEWDB -> STRATEGY_NEWDB;
      case SAMEDB -> STRATEGY_SAMEDB;
    };
  }

  static final class RunVendorUpgrade implements Step {
    private final UpgradeRuntime rt;
    private final UpgradeInput in;

    RunVendorUpgrade(UpgradeRuntime rt, UpgradeInput in) {
      this.rt = Objects.requireNonNull(rt, "rt");
      this.in = Objects.requireNonNull(in, "in");
    }

    String scriptName() {
      return SCRIPT_PREFIX + in.options().mode().vendorSuffix();
    }

    String antTarget() {
      return ANT_TARGET_PREFIX + edition();
    }

    String edition() {
      return VendorSteps.edition(in);
    }

    String strategy() {
      return VendorSteps.strategy(in.options().mode());
    }

    /**
     * The point-B full export, the one argument {@code js-upgrade-newdb} requires (ADR-0012): the
     * export this run took, or the one {@code adopt-full-export} recorded (ADR-0028).
     */
    private Path fullExport(Context ctx) {
      return in.snapshots(ctx).resolveFullExport().toAbsolutePath().normalize();
    }

    /** How the plan names the export before the run id is known. */
    private String exportPlaceholder() {
      return in.options().existingExport().map(Path::toString).orElse("<point-B full export>");
    }

    /** Arguments for the vendor wrapper: the full export for newdb, nothing for samedb. */
    List<String> wrapperArgs(Context ctx) {
      return switch (in.options().mode()) {
        case NEWDB -> List.of(fullExport(ctx).toString());
        case SAMEDB -> List.of();
      };
    }

    /** What the wrapper would pass to {@code js-ant}, for a package that ships no wrapper. */
    List<String> antArgs(Context ctx) {
      return switch (in.options().mode()) {
        case NEWDB -> List.of("-Dstrategy=" + STRATEGY_NEWDB, "-DimportFile=" + fullExport(ctx));
        case SAMEDB -> List.of("-Dstrategy=" + STRATEGY_SAMEDB);
      };
    }

    private Path marker(Context ctx) {
      return ctx.home().runDir(ctx.runId()).resolve(DONE_MARKER);
    }

    private Path attemptMarker(Context ctx) {
      return ctx.home().runDir(ctx.runId()).resolve(ATTEMPT_MARKER);
    }

    @Override
    public String id() {
      return RUN_VENDOR_UPGRADE;
    }

    @Override
    public String title() {
      return "run the vendor upgrade (" + scriptName() + ")";
    }

    @Override
    public String phase() {
      return Phases.VENDOR_UPGRADE;
    }

    @Override
    public String detail() {
      boolean newdb = in.options().mode() == UpgradeOperations.Mode.NEWDB;
      return scriptName()
          + (newdb ? " " + exportPlaceholder() : "")
          + " if shipped, else js-ant "
          + antTarget()
          + " -Dstrategy="
          + strategy()
          + (newdb ? " -DimportFile=" + exportPlaceholder() : "")
          + "; JAVA_HOME="
          + rt.config().vendor().javaHome().map(Path::toString).orElse("<unset>")
          + "; timeout "
          + rt.tools().timeout().toMinutes()
          + "m";
    }

    /*
     * Not irreversible: the vendor script cannot be undone in place, but spec §10.1 defines its
     * undo as the restore of rollback point B, and that is exactly what compensate() performs.
     * The repository database change stays in both modes (samedb migrates it, newdb drops and
     * recreates it; ADR-0012); the plan summary says so in plain text and the run does not start
     * without --db-backup-confirmed.
     */
    @Override
    public boolean irreversible() {
      return false;
    }

    @Override
    public CheckResult precheck(Context ctx) {
      if (in.targetBuildomatic().isEmpty()) {
        return CheckResult.fail(
            "no buildomatic directory in " + in.target().dir(), "check the target package");
      }
      if (rt.config().vendor().javaHome().isEmpty()) {
        return CheckResult.fail(
            "vendor.javaHome is not set", "set vendor.javaHome to the JDK the target needs");
      }
      if (in.options().mode() == UpgradeOperations.Mode.NEWDB
          && !Files.isRegularFile(fullExport(ctx))) {
        return CheckResult.fail(
            "the point-B full export "
                + fullExport(ctx)
                + " is missing; js-upgrade-newdb rebuilds the repository database from it",
            "resume the run so the backup phase writes it, or start the upgrade again");
      }
      if (in.options().mode() == UpgradeOperations.Mode.NEWDB) {
        // an adopted export (ADR-0028) sits outside the home: check it is still the file that
        // was adopted before the vendor script drops the database and imports it
        try {
          Optional<SnapshotSet.ExternalExport> external = in.snapshots(ctx).externalExport();
          if (external.isPresent()
              && !rt.files().sha256(external.get().path()).equals(external.get().sha256())) {
            return CheckResult.fail(
                external.get().path() + " changed since it was adopted",
                "run the upgrade again so the export is adopted afresh");
          }
        } catch (IOException e) {
          return CheckResult.fail(
              "cannot verify the adopted export: " + e.getMessage(),
              "check read access to the export archive");
        }
      }
      return CheckResult.pass();
    }

    @Override
    public StepResult execute(Context ctx, EventSink out) {
      if (Files.isRegularFile(marker(ctx))) {
        Logs.info(rt, ctx, out, this, "vendor upgrade already completed in this run; skipping");
        return StepResult.ok();
      }
      if (Files.isRegularFile(attemptMarker(ctx))) {
        return interrupted(ctx);
      }
      try {
        recordAttempt(ctx);
      } catch (IOException e) {
        return Failures.recoverable(
            "cannot write " + attemptMarker(ctx) + ": " + e.getMessage(),
            "free space in the run directory and re-run; without this marker a crash during the"
                + " vendor upgrade cannot be told apart from one before it");
      }
      Buildomatic b = in.target().buildomatic().orElseThrow();
      Optional<Path> javaHome = rt.config().vendor().javaHome();
      String ext = rt.locator().scriptExtension();
      Path wrapper = b.dir().resolve(scriptName() + ext);
      VendorRun run;
      if (Files.isRegularFile(wrapper)) {
        Map<String, Path> scripts = new LinkedHashMap<>(b.scripts());
        scripts.put(scriptName(), wrapper);
        Buildomatic withWrapper =
            new Buildomatic(b.dir(), scripts, b.masterPropertiesFile(), b.masterProperties());
        run =
            rt.tools()
                .run(
                    new VendorTools.Invocation(
                        withWrapper, scriptName(), wrapperArgs(ctx), javaHome),
                    out,
                    Logs.scope(ctx, this));
      } else {
        List<String> antArgs = antArgs(ctx);
        Logs.info(
            rt,
            ctx,
            out,
            this,
            "no "
                + wrapper.getFileName()
                + " in the package; using js-ant "
                + antTarget()
                + " "
                + String.join(" ", antArgs));
        run = rt.tools().ant(b, antTarget(), antArgs, javaHome, out, Logs.scope(ctx, this));
      }
      return switch (run) {
        case VendorRun.Completed c -> c.ok() ? done(ctx, out) : failed(ctx, c);
        case VendorRun.TimedOut t ->
            Failures.recoverable(
                "vendor upgrade did not finish within " + t.timeout().toMinutes() + " minutes",
                "check for a hung buildomatic process; the run is rolled back to point B",
                List.of(in.webappDir()),
                List.of(in.snapshots(ctx).dir()));
        case VendorRun.NotStarted n -> notStarted(ctx, out, n);
      };
    }

    /**
     * Writes and forces the marker that says the script is about to run. Forced, because the whole
     * point is to survive the power cut that a resume has to reason about.
     */
    private void recordAttempt(Context ctx) throws IOException {
      Path file = attemptMarker(ctx);
      Files.createDirectories(file.getParent());
      Files.writeString(
          file,
          scriptName() + " started at " + rt.clock().instant() + System.lineSeparator(),
          UTF_8);
      Durability.sync(file);
      Durability.syncDirectory(file.getParent());
      // ADR-0029: the snapshot set keeps the fact that the script was launched; the compensation
      // of this step erases the attempt marker but never this, and a database rollback refuses to
      // rebuild a database the script never touched
      SnapshotSet set = in.snapshots(ctx);
      Files.createDirectories(set.dir());
      Files.writeString(
          set.vendorStarted(),
          scriptName() + " started at " + rt.clock().instant() + System.lineSeparator(),
          UTF_8);
      Durability.sync(set.vendorStarted());
    }

    /**
     * Refuses a resume that cannot tell whether the database change ran. {@code samedb} rewrites
     * the repository schema in place and {@code newdb} drops and recreates the database; neither is
     * idempotent, so the operator has to look at the buildomatic log and say which side of the
     * crash they are on.
     */
    private StepResult interrupted(Context ctx) {
      return Failures.recoverable(
          scriptName()
              + " was started in run "
              + ctx.runId()
              + " and never reported back; whether the repository database was already changed"
              + " (migrated by samedb, dropped and recreated by newdb) is unknown",
          "read the buildomatic log under "
              + in.target().dir()
              + "; if the upgrade did not run, delete "
              + attemptMarker(ctx)
              + " and resume; if it did, roll back with jrs-upgrade upgrade rollback "
              + ctx.runId()
              + " --to-point B",
          List.of(in.webappDir()),
          List.of(in.snapshots(ctx).dir()));
    }

    /** The launch itself failed, so nothing ran and the attempt marker must not outlive it. */
    private StepResult notStarted(Context ctx, EventSink out, VendorRun.NotStarted n) {
      try {
        Files.deleteIfExists(attemptMarker(ctx));
      } catch (IOException e) {
        Logs.warn(
            rt, ctx, out, this, "cannot remove " + attemptMarker(ctx) + ": " + e.getMessage());
      }
      return Failures.recoverable(n.reason(), n.remediation());
    }

    private StepResult done(Context ctx, EventSink out) {
      try {
        Files.createDirectories(marker(ctx).getParent());
        Files.writeString(marker(ctx), "done", UTF_8);
      } catch (IOException e) {
        Logs.warn(rt, ctx, out, this, "cannot write " + marker(ctx) + ": " + e.getMessage());
      }
      Logs.info(rt, ctx, out, this, "vendor upgrade finished");
      return StepResult.ok();
    }

    private StepResult failed(Context ctx, VendorRun.Completed c) {
      return Failures.recoverable(
          "vendor upgrade " + c.summary() + ": " + String.join(" | ", c.tail()),
          c.reported() == VendorRun.Reported.CREATED_KEYSTORE
              ? "the run is rolled back to point B, which puts the saved .jrsks and .jrsksp back;"
                  + " before running again make sure the target buildomatic's"
                  + " keystore.init.properties names the keystore the server uses"
              : "read the buildomatic log; the run is rolled back to point B",
          List.of(in.webappDir()),
          List.of(in.snapshots(ctx).dir()));
    }

    @Override
    public StepResult compensate(Context ctx, EventSink out) {
      SnapshotSet set = in.snapshots(ctx);
      Path aside = ctx.home().runDir(ctx.runId()).resolve(PointB.ASIDE_DIR);
      List<String> problems = new ArrayList<>();
      try {
        if (Files.isRegularFile(set.webappArchive())) {
          PointB.verifyArchive(set.webappArchive(), rt.files().sha256(set.webappArchive()));
          PointB.restoreDir(
              set.os(), set.webappArchive(), in.webappDir(), aside.resolve("webapp"), ctx.cancel());
          Logs.info(rt, ctx, out, this, "webapp restored from " + set.webappArchive());
        } else {
          problems.add("no webapp archive at " + set.webappArchive());
        }
      } catch (IOException e) {
        problems.add("webapp: " + e.getMessage());
      }
      try {
        if (Files.isRegularFile(set.buildomaticArchive())) {
          PointB.verifyArchive(
              set.buildomaticArchive(), rt.files().sha256(set.buildomaticArchive()));
          PointB.restoreDir(
              set.os(),
              set.buildomaticArchive(),
              in.installedBuildomatic(),
              aside.resolve("buildomatic"),
              ctx.cancel());
          Logs.info(rt, ctx, out, this, "buildomatic restored from " + set.buildomaticArchive());
        }
      } catch (IOException e) {
        problems.add("buildomatic: " + e.getMessage());
      }
      for (String stepId : List.of(SnapshotSet.CONFIG_STEP, SnapshotSet.KEYSTORE_STEP)) {
        try {
          Optional<Snapshot> snapshot = rt.snapshots().find(ctx.runId(), stepId);
          if (snapshot.isPresent()) {
            rt.snapshots().restore(snapshot.get());
            Logs.info(rt, ctx, out, this, stepId + " restored from " + snapshot.get().dir());
          }
        } catch (IOException | RuntimeException e) {
          problems.add(stepId + ": " + Failures.describe(e));
        }
      }
      for (Path file : List.of(marker(ctx), attemptMarker(ctx))) {
        try {
          Files.deleteIfExists(file);
        } catch (IOException e) {
          problems.add(file.getFileName() + ": " + e.getMessage());
        }
      }
      if (!problems.isEmpty()) {
        return Failures.recoverable(
            "point B restore incomplete: " + String.join("; ", problems),
            "restore by hand from "
                + set.dir()
                + " or run jrs-upgrade upgrade rollback "
                + ctx.runId()
                + " --to-point B",
            List.of(in.webappDir(), in.installedBuildomatic()),
            List.of(set.dir()));
      }
      return StepResult.ok();
    }
  }
}
