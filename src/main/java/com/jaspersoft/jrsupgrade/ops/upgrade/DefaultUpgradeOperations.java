package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.compat.CompatMatrix;
import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.config.ConfigException;
import com.jaspersoft.jrsupgrade.core.config.ConfigWriter;
import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.PlanFingerprint;
import com.jaspersoft.jrsupgrade.core.engine.PlanSummary;
import com.jaspersoft.jrsupgrade.core.engine.RunIds;
import com.jaspersoft.jrsupgrade.core.engine.RunRecord;
import com.jaspersoft.jrsupgrade.core.engine.Sleeper;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.json.Json;
import com.jaspersoft.jrsupgrade.core.platform.DiskSpace;
import com.jaspersoft.jrsupgrade.core.platform.ServiceConfig;
import com.jaspersoft.jrsupgrade.core.platform.TomcatLayout;
import com.jaspersoft.jrsupgrade.core.platform.UserPaths;
import com.jaspersoft.jrsupgrade.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsupgrade.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsupgrade.jrs.api.ServerIdentity;
import com.jaspersoft.jrsupgrade.jrs.rest.RestException;
import com.jaspersoft.jrsupgrade.jrs.service.ServiceSteps;
import com.jaspersoft.jrsupgrade.jrs.strategy.Sidecar;
import com.jaspersoft.jrsupgrade.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsupgrade.jrs.vendor.VendorTools;
import com.jaspersoft.jrsupgrade.ops.ClusterNotice;
import com.jaspersoft.jrsupgrade.ops.Services;
import com.jaspersoft.jrsupgrade.ops.TomcatJavaOpts;
import com.jaspersoft.jrsupgrade.ops.TomcatVersion;
import com.jaspersoft.jrsupgrade.ops.db.DefaultJdbcConnector;
import com.jaspersoft.jrsupgrade.ops.hotfix.HotfixException;
import com.jaspersoft.jrsupgrade.ops.hotfix.HotfixPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds the upgrade and rollback plans of spec §10. Invariants: planning reads the installation,
 * the target package and the state store but mutates neither the server nor the installation; the
 * upgrade plan always carries the five phases in spec order, the {@code confirm-db-backup} step and
 * the files-only rollback sentence of spec §10.1 in both modes (ADR-0012: {@code newdb} drops and
 * recreates the repository database, so neither mode's database change can be undone by
 * jrs-upgrade); an upgrade path the compat matrix does not list is refused at planning time with
 * exit code 6 whenever the server is reachable; the fingerprint covers the server identity, the
 * target package contents, the resolved configuration and the target version and mode.
 */
public final class DefaultUpgradeOperations implements UpgradeOperations {

  static final String STRATEGY = "vendor-cli";

  /** Spec §10.1, quoted verbatim; carried by every upgrade and rollback plan (ADR-0012). */
  public static final String FILES_ONLY_WARNING =
      "Rollback restores files only. Restore the database from your own backup before running"
          + " rollback.";

  public static final String SAMEDB_WARNING =
      "js-upgrade-samedb migrates the existing repository database schema in place; jrs-upgrade cannot"
          + " undo that migration.";

  public static final String NEWDB_WARNING =
      "js-upgrade-newdb drops and recreates the repository database named in"
          + " default_master.properties, then imports the point-B full export into it; upgrade"
          + " rollback --restore-database rebuilds the old database from the same export"
          + " (ADR-0012, ADR-0029).";

  /** ADR-0029: why newdb asks for no database backup, in the plan's own words. */
  public static final String NEWDB_ROLLBACK_WARNING =
      "jrs-upgrade backs up the files, the keystore and a full export of the repository;"
          + " js-upgrade-newdb drops and recreates the database from that export, and upgrade"
          + " rollback --restore-database rebuilds it from the same export.";

  /** ADR-0029: what {@code --restore-database} does; {@code %s} is the export. */
  public static final String DATABASE_RESTORE_WARNING =
      "This rollback rebuilds the repository database from %s with the restored buildomatic:"
          + " js-ant init-js-db drops the database the upgrade created and initialises the old"
          + " schema, then js-import reloads the export. Nothing changed in the repository after"
          + " that export survives.";

  /**
   * Review §1.6, ADR-0025: nothing may change in the repository after the export newdb rebuilds it
   * from.
   */
  public static final String NEWDB_STAYS_STOPPED_WARNING =
      "The full export is taken after the service is stopped and the service stays stopped until"
          + " the vendor upgrade has run: a repository change made after that export (a scheduled"
          + " report, an edited user) would be lost when js-upgrade-newdb rebuilds the database"
          + " from it (ADR-0025).";

  /**
   * Upgrade guide 10.1 p.80 (issue #106): the vendor's newdb script does not carry the events over,
   * and the plan says so unless the operator asked for them.
   */
  public static final String EVENTS_LEFT_BEHIND_WARNING =
      "js-upgrade-newdb does not import the access, audit and monitoring events (since 7.9): the"
          + " full export holds them, but the rebuilt database will not. Pass --include-events to"
          + " import them after the vendor run with the new version's js-import, or run js-import"
          + " --include-access-events --include-audit-events --include-monitoring-events by hand.";

  /** Issue #108: the password migration is the samedb step; newdb rebuilds the database instead. */
  static final String MIGRATE_PASSWORDS_NEWDB_WARNING =
      "--migrate-passwords is ignored in newdb mode: js-upgrade-newdb rebuilds the database from"
          + " the full export; run js-ant migrate-passwords by hand afterwards if the new version's"
          + " js.password-storage-config.properties asks for the modern format.";

  /**
   * The home of the user running jrs-upgrade (HOME, USERPROFILE, else user.home), for the licence.
   */
  static Path defaultUserHome() {
    return Path.of(UserPaths.expand("~", System.getenv())).toAbsolutePath().normalize();
  }

  /** Review §2.1, ADR-0026: what the operator still owns when the webapp moves to a new Tomcat. */
  static final String TOMCAT_DIR_WARNING =
      "The webapp is copied into %s before the vendor run and the upgraded server starts there;"
          + " that Tomcat must listen on the same port as server.baseUrl, carry the JAVA_OPTS the"
          + " vendor requires for it (installation guide: --add-opens on Tomcat 11), and be"
          + " registered as the service afterwards. The old Tomcat is left as it was.";

  /** ADR-0026 amendment (issue #109): the service switch stays the operator's, with the steps. */
  static final String SERVICE_SWITCH_WARNING = "Service switch, after the upgrade: ";

  /**
   * Installation guide 10.1 pp.84-86 (issue #109): the Java 17/21 options for a Tomcat 10 or 11.
   */
  static final String ADD_OPENS_WARNING =
      "%s carries no --add-opens: the installation guide 10.1 (pp.84-86) lists the --add-opens"
          + " java.base/... options for JAVA_OPTS on Java 17 and 21; add them to that file before"
          + " the first start if the server needs them (the vendor's bundled installer starts"
          + " without them).";

  private String reregistration(ServiceConfig.Kind kind, UpgradeInput in) {
    Config.Service service = rt.config().service();
    return ServiceSwitch.reregistration(
        kind,
        service.name(),
        service.scriptPath(),
        in.tomcatDir(),
        in.hostTomcatDir(),
        rt.services().platform().os());
  }

  static String tomcatRanges(CompatMatrix matrix, String version) {
    return matrix
        .find(version)
        .map(e -> e.tomcat().isEmpty() ? "(any)" : String.join(" or ", e.tomcat()))
        .orElse("(unknown version)");
  }

  static final String PASSWORD_WARNING =
      "database passwords are not copied into the target default_master.properties (spec §7.4);"
          + " add them there before running if the vendor scripts need them";

  private final UpgradeRuntime rt;
  private final Path userHome;

  public DefaultUpgradeOperations(Services services) {
    this(
        new UpgradeRuntime(
            services,
            new SnapshotStore(services.home(), services.platform().files(), services.clock()),
            s ->
                new VendorTools(
                    s.platform().processes(),
                    s.platform().files(),
                    s.redactor(),
                    VendorTools.DEFAULT_TIMEOUT,
                    s.config().envSecretNames()),
            new DefaultJdbcConnector(),
            Sleeper.system()));
  }

  DefaultUpgradeOperations(UpgradeRuntime rt) {
    this(rt, defaultUserHome());
  }

  /** {@code userHome} is where the vendor scripts look for the licence file (issue #108). */
  DefaultUpgradeOperations(UpgradeRuntime rt, Path userHome) {
    this.rt = Objects.requireNonNull(rt, "rt");
    this.userHome = Objects.requireNonNull(userHome, "userHome").toAbsolutePath().normalize();
  }

  /** {@code %s} is the adopted export (ADR-0028). */
  public static final String EXISTING_EXPORT_WARNING =
      "js-upgrade-newdb rebuilds the repository from %s: every change made in the repository"
          + " after that export was taken is discarded";

  /**
   * ADR-0028: {@code --export} feeds the newdb script and nothing else; a key without an export
   * names nothing. Usage errors, since the operator asked for a combination that means nothing.
   */
  private static void refuseInconsistentOptions(UpgradeOptions options) {
    if (options.existingExport().isPresent() && options.mode() == Mode.SAMEDB) {
      throw new UpgradeException(
          UpgradeException.USAGE,
          "--export is only used by a newdb upgrade; samedb migrates the database in place and"
              + " imports nothing",
          "leave --export out, or use --mode newdb");
    }
    if ((options.keyAlias().isPresent() || options.keyPassword().isPresent())
        && options.existingExport().isEmpty()) {
      throw new UpgradeException(
          UpgradeException.USAGE,
          "--key-alias and --key-password-ref describe an export taken elsewhere; there is none",
          "pass the export with --export <file>");
    }
    if (options.keyPassword().isPresent() && options.keyAlias().isEmpty()) {
      throw new UpgradeException(
          UpgradeException.USAGE,
          "--key-password-ref needs --key-alias",
          "pass --key-alias <alias> with it");
    }
  }

  /**
   * What the operator must know about an adopted export: the vendor script discards every later
   * change, and, when the export's sidecar says so, it came from another server or version, which
   * is allowed (field test 2: an upgrade is not always linear) but worth a line.
   */
  private static List<String> existingExportWarnings(
      Path export, Optional<ServerIdentity> identity) {
    List<String> out = new ArrayList<>();
    out.add(EXISTING_EXPORT_WARNING.formatted(export));
    try {
      Optional<Sidecar> sidecar = Sidecar.read(Sidecar.pathFor(export));
      if (sidecar.isEmpty()) {
        out.add(
            "no "
                + Sidecar.pathFor(export).getFileName()
                + " beside "
                + export
                + ": where it was exported from cannot be checked; an export from another server"
                + " needs --key-alias when it was encrypted with the Legacy key");
      } else if (identity.isPresent()
          && (!sidecar.get().serverIdentity().equals(identity.get().fingerprintInput())
              || !sidecar.get().serverVersion().equals(identity.get().version()))) {
        out.add(
            export
                + " was exported from "
                + sidecar.get().serverIdentity()
                + " (version "
                + sidecar.get().serverVersion()
                + "); this server is "
                + identity.get().fingerprintInput()
                + " (version "
                + identity.get().version()
                + "): pass --key-alias when the export was encrypted with the Legacy key, since"
                + " the import otherwise decrypts it with this server's keystore");
      }
    } catch (IOException | IllegalArgumentException e) {
      out.add("sidecar beside " + export + " is unreadable (" + e.getMessage() + ")");
    }
    return out;
  }

  /**
   * Where the run's backups and export land and how to move them (field test 2, U3: the tester
   * could not find either), with the volume's free space now.
   */
  private String backupsLine() {
    String free;
    try {
      free = DiskSpace.human(rt.files().freeSpaceBytes(rt.home().snapshots()));
    } catch (IOException e) {
      free = "an unknown amount";
    }
    return "backups and the full export go under "
        + rt.home().snapshots()
        + " ("
        + free
        + " free); to put them on another volume run with --home <dir> or JRS_UPGRADE_HOME";
  }

  /**
   * What both the upgrade and its rehearsal are planned from; {@code in} is the last of {@code
   * hops}.
   */
  private record Prepared(
      UpgradeInput in,
      List<UpgradeInput> hops,
      TargetPackage target,
      Optional<ServerIdentity> identity,
      List<String> warnings) {

    boolean route() {
      return hops.size() > 1;
    }

    Mode firstMode() {
      return hops.get(0).options().mode();
    }

    Mode lastMode() {
      return in.options().mode();
    }
  }

  /** Issue #1, ADR-0002: what a route plan says about itself, with the route in place of %s. */
  public static final String ROUTE_WARNING =
      "No single documented upgrade covers this pair; the plan follows the vendor's route %s."
          + " Every hop but the last is a transit hop: its vendor script runs against the"
          + " repository database with appServerType=skipAppServerCheck and a scratch Tomcat"
          + " directory, so nothing is deployed and nothing starts on an intermediate version. The"
          + " service stays stopped from the first hop to the last, and a failure in any hop"
          + " restores point B: a rollback never lands on an intermediate version.";

  /** "8.2.0 -> 10.0.0 (newdb, transit) -> 10.1.0 (samedb)". */
  static String describeRoute(List<CompatMatrix.RouteHop> route) {
    StringBuilder out = new StringBuilder(route.get(0).from());
    for (int i = 0; i < route.size(); i++) {
      CompatMatrix.RouteHop hop = route.get(i);
      out.append(" -> ")
          .append(hop.to())
          .append(" (")
          .append(hop.mode())
          .append(i < route.size() - 1 ? ", transit" : "")
          .append(')');
    }
    return out.toString();
  }

  /**
   * The hops from {@code current} to the target: the one documented pair when the matrix lists it,
   * else the matrix's route (issue #1). Refuses with exit 6 what the matrix covers neither way, and
   * a pair offered only in the other mode, as it always did.
   */
  private List<CompatMatrix.RouteHop> route(String current, UpgradeOptions options) {
    CompatMatrix matrix = rt.services().matrix();
    String to = options.toVersion();
    Optional<String> problem = UpgradePaths.problem(matrix, current, to, options.mode());
    if (problem.isEmpty()) {
      return List.of(new CompatMatrix.RouteHop(current, to, UpgradePaths.wire(options.mode())));
    }
    if (!matrix.upgradeModes(current, to).isEmpty()) {
      throw new UpgradeException(
          UpgradeException.UNSUPPORTED, problem.get(), "choose a supported target version or mode");
    }
    Optional<List<CompatMatrix.RouteHop>> route =
        matrix.route(current, to, UpgradePaths.wire(options.mode()));
    if (route.isPresent()) {
      return route.get();
    }
    Mode other = options.mode() == Mode.NEWDB ? Mode.SAMEDB : Mode.NEWDB;
    Optional<List<CompatMatrix.RouteHop>> otherRoute =
        matrix.route(current, to, UpgradePaths.wire(other));
    throw new UpgradeException(
        UpgradeException.UNSUPPORTED,
        problem.get()
            + otherRoute
                .map(
                    r ->
                        "; the documented route "
                            + describeRoute(r)
                            + " starts with a "
                            + UpgradePaths.wire(other)
                            + " hop")
                .orElse(", and no documented route through its releases leads there either"),
        otherRoute
            .map(r -> "pass --mode " + UpgradePaths.wire(other))
            .orElse("choose a supported target version or mode"));
  }

  /**
   * The package of every hop, by the version each package states (issue #1): the last hop takes
   * {@code --package}'s first entry when that states no version. Refuses a route whose stops lack a
   * package (exit 2, naming the route and the packages) and a package no hop uses (exit 1).
   */
  private List<Path> packagesFor(List<CompatMatrix.RouteHop> route, UpgradeOptions options) {
    List<Path> given = new ArrayList<>();
    given.add(options.packageDir());
    given.addAll(options.transitPackages());
    if (route.size() == 1) {
      if (!options.transitPackages().isEmpty()) {
        throw new UpgradeException(
            UpgradeException.USAGE,
            route.get(0).from()
                + " -> "
                + route.get(0).to()
                + " is one documented upgrade and needs one package; "
                + given.size()
                + " were given",
            "pass the " + route.get(0).to() + " package alone with --package");
      }
      return List.of(options.packageDir());
    }
    Map<Path, Optional<String>> stated = new LinkedHashMap<>();
    for (Path p : given) {
      stated.put(p, TargetPackage.inspect(p, rt.locator()).discoveredVersion());
    }
    List<Path> out = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    for (int i = 0; i < route.size(); i++) {
      String version = route.get(i).to();
      Optional<Path> match =
          given.stream().filter(p -> stated.get(p).filter(version::equals).isPresent()).findFirst();
      if (match.isEmpty() && i == route.size() - 1 && stated.get(options.packageDir()).isEmpty()) {
        match = Optional.of(options.packageDir());
      }
      match.ifPresentOrElse(out::add, () -> missing.add(version));
    }
    String described = describeRoute(route);
    if (!missing.isEmpty()) {
      throw new UpgradeException(
          UpgradeException.PRECHECK,
          route.get(0).from()
              + " -> "
              + options.toVersion()
              + " is not one documented upgrade; the documented route is "
              + described
              + ", and no package given states "
              + String.join(" or ", missing),
          "pass one unpacked package per hop: "
              + route.stream()
                  .map(h -> "--package <" + h.to() + " package>")
                  .collect(Collectors.joining(" ")));
    }
    for (Path p : given) {
      if (!out.contains(p)) {
        throw new UpgradeException(
            UpgradeException.USAGE,
            p
                + " states version "
                + stated.get(p).orElse("(none)")
                + ", which is no hop of the route "
                + described,
            "pass only the packages the route names");
      }
    }
    return out;
  }

  /**
   * The preflight every upgrade plan shares: the option consistency, the upgrade path or route in
   * the matrix, the packages of its hops, the installed buildomatic, the staged properties'
   * invariants and the Tomcat; it throws for what cannot be planned and collects the warnings for
   * what can.
   */
  private Prepared prepare(UpgradeOptions options) {
    Objects.requireNonNull(options, "options");
    Config config = rt.config();
    HotfixPaths paths = paths(config);
    String webappName = webappName(config, paths);
    refuseInconsistentOptions(options);
    Optional<ServerIdentity> identity = identity();
    List<String> warnings = new ArrayList<>();
    List<CompatMatrix.RouteHop> route;
    if (identity.isPresent()) {
      route = route(identity.get().version(), options);
    } else {
      if (!options.transitPackages().isEmpty()) {
        throw new UpgradeException(
            UpgradeException.PRECHECK,
            "server unreachable at plan time: a route through several packages is planned from"
                + " the version the server reports",
            "start the server and plan again");
      }
      route =
          List.of(
              new CompatMatrix.RouteHop(
                  "?", options.toVersion(), UpgradePaths.wire(options.mode())));
      warnings.add(
          "server unreachable at plan time; the upgrade path is verified by "
              + PreflightSteps.VERIFY_TARGET_PACKAGE);
    }
    List<Path> packages = packagesFor(route, options);
    Path installedBuildomatic =
        UpgradeInput.resolveInstalledBuildomatic(rt.locator(), config, paths);
    for (Path pkg : packages) {
      if (installedBuildomatic.startsWith(pkg)) {
        throw new UpgradeException(
            UpgradeException.PRECHECK,
            "the installed buildomatic directory "
                + installedBuildomatic
                + " lies inside the target package "
                + pkg,
            "set server.buildomaticDir to the buildomatic directory of the running installation,"
                + " not the one of the package being installed");
      }
    }
    Map<String, String> installedMaster =
        rt.locator().at(installedBuildomatic).map(Buildomatic::masterProperties).orElse(Map.of());
    Map<String, String> finalOverrides =
        VendorSteps.masterOverrides(
            installedMaster,
            options.tomcatDir().map(p -> p.toAbsolutePath().normalize()).orElse(paths.tomcatDir()),
            options.keyAlias());
    List<UpgradeInput> hops = new ArrayList<>();
    for (int i = 0; i < route.size(); i++) {
      CompatMatrix.RouteHop hop = route.get(i);
      boolean transit = i < route.size() - 1;
      UpgradeOptions hopOptions =
          route.size() == 1
              ? options
              : options.forHop(
                  hop.to(), packages.get(i), Mode.valueOf(hop.mode().toUpperCase(Locale.ROOT)));
      UpgradeInput hopInput =
          new UpgradeInput(
              hopOptions,
              paths,
              webappName,
              TargetPackage.inspect(packages.get(i), rt.locator()),
              identity,
              transit
                  ? VendorSteps.transitOverrides(finalOverrides, transitAppServer(hop.to()))
                  : finalOverrides,
              installedBuildomatic,
              new UpgradeInput.Hop(
                  i + 1,
                  route.size(),
                  transit,
                  i == 0 ? Optional.empty() : Optional.of(hop.from())));
      // review §1.3: Compact and Split never cross in one upgrade; the target package's own
      // default_master.properties must not turn the installation into the other kind
      Optional<String> crossing =
          MasterInvariants.installTypeProblem(
              hopInput.masterOverrides(), hopInput.targetMasterProperties(rt.locator()));
      if (crossing.isPresent()) {
        throw new UpgradeException(
            UpgradeException.UNSUPPORTED,
            crossing.get(),
            "make installType and the audit.* keys in the target buildomatic's"
                + " default_master.properties match the installed ones, or remove them there");
      }
      hops.add(hopInput);
    }
    UpgradeInput in = hops.get(hops.size() - 1);
    TargetPackage target = in.target();
    if (hops.size() > 1) {
      warnings.add(ROUTE_WARNING.formatted(describeRoute(route)));
    }
    warnings.add(backupsLine());
    // review §3.2 (issue #112): an upgrade run here upgrades this node's webapp only
    ClusterNotice.warning(rt.services()).ifPresent(warnings::add);
    options
        .existingExport()
        .ifPresent(export -> warnings.addAll(existingExportWarnings(export, identity)));
    if (options.tomcatDir().isPresent()) {
      // review §2.1, ADR-0026: a service registered for the old Tomcat would start the old
      // server after the vendor run; only an operator-started Tomcat can be switched in one run.
      // jrs-upgrade does not re-register services (ADR-0026 amendment, issue #109): the refusal and
      // the summary carry the platform's own steps instead.
      Optional<ServiceConfig.Kind> kind = config.service().kind();
      if (kind.isEmpty() || kind.get() != ServiceConfig.Kind.MANUAL) {
        throw new UpgradeException(
            UpgradeException.PRECHECK,
            "--tomcat-dir needs service.kind manual: a "
                + kind.map(k -> k.name().toLowerCase(java.util.Locale.ROOT)).orElse("configured")
                + " service starts the Tomcat it was registered for, not "
                + in.hostTomcatDir(),
            "set service.kind to manual for this upgrade (jrs-upgrade asks you to stop and start"
                + " Tomcat), then re-register the service for the new Tomcat afterwards: "
                + kind.map(k -> reregistration(k, in)).orElse("see the operator guide"));
      }
      warnings.add(TOMCAT_DIR_WARNING.formatted(in.hostTomcatDir()));
      warnings.add(SERVICE_SWITCH_WARNING + reregistration(ServiceConfig.Kind.MANUAL, in));
    }
    // installation guide 10.1 pp.84-86 (issue #109): the --add-opens list for Java 17/21 is the
    // operator's; the vendor's own bundled installer omits it, so this is advice, not a refusal
    TomcatJavaOpts.missingAddOpens(in.hostTomcatDir(), rt.services().platform().os())
        .ifPresent(setenv -> warnings.add(ADD_OPENS_WARNING.formatted(setenv)));
    Optional<String> hostTomcat = TomcatVersion.detect(in.hostTomcatDir());
    if (hostTomcat.isEmpty()) {
      warnings.add(
          "the Tomcat version at "
              + in.hostTomcatDir()
              + " could not be read (no lib/catalina.jar or RELEASE-NOTES); JasperReports Server "
              + options.toVersion()
              + " needs Tomcat "
              + tomcatRanges(rt.services().matrix(), options.toVersion()));
    }
    for (UpgradeInput hop : hops) {
      List<String> problems = hop.target().problems(rt.locator().scriptExtension());
      if (!problems.isEmpty()) {
        warnings.add(
            "target package"
                + (hop.transit() ? " for " + hop.options().toVersion() : "")
                + ": "
                + String.join("; ", problems)
                + " ("
                + hop.scoped(PreflightSteps.VERIFY_TARGET_PACKAGE)
                + " will refuse)");
      }
    }
    return new Prepared(in, List.copyOf(hops), target, identity, warnings);
  }

  /** ADR-0002: the scratch Tomcat directory a transit hop's buildomatic is pointed at. */
  private Path transitAppServer(String version) {
    return rt.home().root().resolve("transit").resolve(version).resolve("tomcat");
  }

  @Override
  public Plan planUpgrade(UpgradeOptions options) {
    Prepared prepared = prepare(options);
    UpgradeInput in = prepared.in();
    TargetPackage target = prepared.target();
    Optional<ServerIdentity> identity = prepared.identity();
    List<String> warnings = prepared.warnings();
    Mode firstMode = prepared.firstMode();
    Mode lastMode = prepared.lastMode();
    warnings.add(
        switch (firstMode) {
          case SAMEDB -> SAMEDB_WARNING;
          case NEWDB -> NEWDB_WARNING;
        });
    if (firstMode == Mode.NEWDB) {
      warnings.add(NEWDB_STAYS_STOPPED_WARNING);
      if (!options.includeEvents()) {
        warnings.add(EVENTS_LEFT_BEHIND_WARNING);
      }
    }
    if (options.migratePasswords()) {
      // installation guide 10.1 pp.194-199 (issue #108): the migration utility ships with 10.1
      if (!VendorPreconditions.atLeast(
          options.toVersion(), VendorPreconditions.PASSWORD_MIGRATION_FROM)) {
        throw new UpgradeException(
            UpgradeException.PRECHECK,
            "--migrate-passwords needs a target of 10.1.0 or later; "
                + options.toVersion()
                + " has no js-ant migrate-passwords",
            "drop --migrate-passwords, or upgrade to 10.1.0 or later");
      }
      if (lastMode == Mode.NEWDB) {
        warnings.add(MIGRATE_PASSWORDS_NEWDB_WARNING);
      }
    }
    warnings.add(firstMode == Mode.SAMEDB ? FILES_ONLY_WARNING : NEWDB_ROLLBACK_WARNING);
    warnings.add(PASSWORD_WARNING);

    List<Step> steps = new ArrayList<>();
    steps.add(new PreflightSteps.Doctor(rt, in));
    for (UpgradeInput hop : prepared.hops()) {
      steps.add(new PreflightSteps.VerifyTargetPackage(rt, hop));
    }
    // review §2.5 (issue #108): the vendor's own preconditions, before anything is stopped
    for (UpgradeInput hop : prepared.hops()) {
      steps.add(new VendorPreconditionSteps.Verify(rt, hop, userHome));
    }
    if (firstMode == Mode.SAMEDB) {
      // newdb's own full export is the backup its rollback rebuilds the database from (ADR-0029);
      // samedb migrates the schema in place, which no export undoes, so it still asks
      steps.add(new PreflightSteps.ConfirmDbBackup(rt, in));
      // samedb migrates the database in place and the export is only a rollback aid, so the
      // server may serve again between the export and the vendor run
      steps.add(ServiceSteps.stop(rt, Phases.BACKUP, BackupSteps.FULL_EXPORT + "-stop-service"));
      steps.add(new BackupSteps.FullExport(rt, in));
      steps.add(ServiceSteps.start(rt, Phases.BACKUP, BackupSteps.FULL_EXPORT + "-start-service"));
      steps.add(
          ServiceSteps.waitForServer(
              rt, Phases.BACKUP, BackupSteps.FULL_EXPORT + "-wait-for-server"));
    }
    steps.add(new BackupSteps.BackupKeystore(rt, in));
    steps.add(new BackupSteps.BackupWebapp(rt, in));
    steps.add(new BackupSteps.BackupConfig(rt, in));
    for (UpgradeInput hop : prepared.hops()) {
      steps.add(new VendorSteps.WriteMasterProperties(rt, hop));
      steps.add(new VendorSteps.StageKeystoreInit(rt, hop));
    }
    steps.add(ServiceSteps.stop(rt, Phases.VENDOR_UPGRADE, VendorSteps.STOP_SERVICE));
    if (firstMode == Mode.NEWDB) {
      // newdb rebuilds the database from this export, so it is taken once the service is down
      // and nothing restarts the server before the vendor run; the vendor's own order
      // (review §1.6, ADR-0025). A vendor-phase rollback restarts the service through the stop
      // step's compensation.
      if (options.existingExport().isPresent()) {
        steps.add(new BackupSteps.AdoptFullExport(rt, in));
      } else {
        steps.add(new BackupSteps.FullExport(rt, in, Phases.VENDOR_UPGRADE));
      }
    }
    if (options.tomcatDir().isPresent()) {
      steps.add(new TomcatSteps.CopyWebappToTomcat(rt, in));
    }
    for (UpgradeInput hop : prepared.hops()) {
      steps.add(new VendorSteps.RunVendorUpgrade(rt, hop));
      if (hop.hop().number() == 1 && firstMode == Mode.NEWDB && options.includeEvents()) {
        // the events js-upgrade-newdb leaves behind, imported while the server is still down
        // (upgrade guide 10.1 p.80, installation guide p.256; issue #106), with the js-import of
        // the version the newdb hop built, before any later hop migrates the database
        steps.add(new EventSteps.ImportEvents(rt, hop));
      }
    }
    if (lastMode == Mode.SAMEDB && options.migratePasswords()) {
      // the vendor's password migration, while the server is still down (installation guide
      // 10.1 pp.194-199; issue #108); refused for a target below 10.1 above
      steps.add(new PasswordSteps.MigratePasswords(rt, in));
    }
    // the vendor's "Additional tasks", done while the server is still down (review §2.2)
    steps.add(new PostUpgradeSteps.ClearTomcatCaches(rt, in));
    steps.add(new PostUpgradeSteps.ClearRepositoryCache(rt));
    steps.add(ServiceSteps.start(rt, Phases.VENDOR_UPGRADE, VendorSteps.START_SERVICE));
    steps.add(ServiceSteps.waitForServer(rt, Phases.VENDOR_UPGRADE, VendorSteps.WAIT_FOR_SERVER));
    // A customization under WEB-INF/lib or WEB-INF/classes is re-applied with the service stopped,
    // the rule every change under WEB-INF obeys (spec §5.3); anything else is re-applied live.
    boolean stopForCustomizations =
        rt.store().customizations().stream()
            .anyMatch(c -> HotfixPaths.requiresServiceStop(c.path().toString()));
    String reapply = ReconcileSteps.PLAN_CUSTOMIZATION_REAPPLY;
    if (stopForCustomizations) {
      warnings.add(
          "a registered customization lives under WEB-INF; the service is stopped again while it"
              + " is re-applied");
      steps.add(ServiceSteps.stop(rt, Phases.RECONCILE, reapply + "-stop-service"));
    }
    steps.add(new ReconcileSteps.PlanCustomizationReapply(rt, in));
    if (stopForCustomizations) {
      steps.add(ServiceSteps.start(rt, Phases.RECONCILE, reapply + "-start-service"));
      steps.add(ServiceSteps.waitForServer(rt, Phases.RECONCILE, reapply + "-wait-for-server"));
    }
    if (AnalyticsJndiSteps.applies(options.toVersion(), rt.store().customizations())) {
      // release notes 9.0 p.15 (issue #108): the two analytics JNDI resources, a WARN when missing
      steps.add(new AnalyticsJndiSteps.Check(rt, in));
    }
    steps.add(new VerifySteps.Smoke(rt, in));
    steps.add(new VerifySteps.RecordUpgrade(rt, in, runFacts(prepared)));
    steps.add(new JrsUpgradeConfigSteps.PointConfigAtTarget(rt, in));

    Path snapshotDir = SnapshotSet.placeholder(rt.home());
    Map<String, String> rollbackPoints = new LinkedHashMap<>();
    rollbackPoints.put(Phases.PREFLIGHT, "nothing mutated");
    rollbackPoints.put(
        Phases.BACKUP, "point B: backups written under " + snapshotDir + ", server untouched");
    rollbackPoints.put(
        Phases.VENDOR_UPGRADE,
        "point C = restore point B (webapp, buildomatic, configuration, keystore)"
            + (firstMode == Mode.NEWDB
                ? "; the full export is taken here, after the stop, and kept under " + snapshotDir
                : "")
            + (prepared.route()
                ? "; a failure in any hop, transit or last, restores point B, never an"
                    + " intermediate version"
                : ""));
    rollbackPoints.put(Phases.RECONCILE, "restore point B");
    rollbackPoints.put(
        Phases.VERIFY,
        "smoke failure offers rollback to point B: " + VerifySteps.rollbackCommand("{runId}"));
    List<Path> touched = new ArrayList<>();
    touched.add(in.webappDir());
    touched.add(in.installedBuildomatic());
    for (UpgradeInput hop : prepared.hops()) {
      hop.targetBuildomatic().ifPresent(b -> touched.add(b.resolve(Buildomatic.MASTER_PROPERTIES)));
    }
    touched.addAll(in.configFiles());
    PlanSummary summary =
        new PlanSummary(
            UPGRADE_OPERATION,
            title(prepared, identity),
            touched,
            List.of(),
            true,
            List.of(snapshotDir),
            rollbackPoints,
            STRATEGY,
            warnings);
    Map<String, String> inputs = fingerprintInputs(prepared, identity, target);
    inputs.put("includeEvents", Boolean.toString(options.includeEvents()));
    return new Plan(
        "upgrade-" + RunIds.next(rt.clock()), steps, summary, PlanFingerprint.of(inputs));
  }

  /** "8.2.0 -> 9.0.0 (newdb)" or, for a route, the route. */
  private static String title(Prepared prepared, Optional<ServerIdentity> identity) {
    if (prepared.route()) {
      List<CompatMatrix.RouteHop> hops = new ArrayList<>();
      String from = identity.map(ServerIdentity::version).orElse("?");
      for (UpgradeInput hop : prepared.hops()) {
        hops.add(
            new CompatMatrix.RouteHop(
                hop.hop().from().orElse(from),
                hop.options().toVersion(),
                UpgradePaths.wire(hop.options().mode())));
      }
      return describeRoute(hops);
    }
    return identity.map(ServerIdentity::version).orElse("?")
        + " -> "
        + prepared.in().options().toVersion()
        + " ("
        + UpgradePaths.wire(prepared.in().options().mode())
        + ")";
  }

  /** What the plan's fingerprint covers: every package of the route too. */
  private Map<String, String> fingerprintInputs(
      Prepared prepared, Optional<ServerIdentity> identity, TargetPackage target) {
    UpgradeOptions options = prepared.in().options();
    Map<String, String> inputs = new LinkedHashMap<>();
    inputs.put("server", identity.map(ServerIdentity::fingerprintInput).orElse("unreachable"));
    inputs.put("package", target.contentHash());
    inputs.put("config", configHash(rt.config()));
    inputs.put("to", options.toVersion());
    inputs.put("mode", prepared.firstMode().name());
    if (prepared.route()) {
      inputs.put("route", title(prepared, identity));
      for (UpgradeInput hop : prepared.hops()) {
        if (hop.transit()) {
          inputs.put("package@" + hop.options().toVersion(), hop.target().contentHash());
        }
      }
    }
    return inputs;
  }

  /**
   * Issue #1: what the point-B manifest records beyond the input: the route and the package of each
   * hop, next to the package's checksum. {@code mode} is the first hop's, the one that touched the
   * old database, which is what a rollback asks about (ADR-0029).
   */
  private static Map<String, Object> runFacts(Prepared prepared) {
    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put("mode", prepared.firstMode().name());
    facts.put("package", prepared.in().target().dir().toString());
    facts.put("packageContentHash", prepared.in().target().contentHash());
    if (prepared.route()) {
      List<String> route = new ArrayList<>();
      for (UpgradeInput hop : prepared.hops()) {
        route.add(
            hop.options().toVersion()
                + " "
                + UpgradePaths.wire(hop.options().mode())
                + (hop.transit() ? " transit" : "")
                + " "
                + hop.target().dir());
      }
      facts.put("route", route);
    }
    return facts;
  }

  /** Spec §10.2 "Rehearsal": what the plan says about itself, in one line. */
  public static final String REHEARSAL_WARNING =
      "Rehearsal: the vendor's own validation of the staged properties, the database connection"
          + " and the package. The service is not stopped, nothing is backed up and nothing is"
          + " changed; the staged files are removed at the end.";

  @Override
  public Plan planTest(UpgradeOptions options) {
    Prepared prepared = prepare(options);
    UpgradeInput in = prepared.in();
    TargetPackage target = prepared.target();
    Optional<ServerIdentity> identity = prepared.identity();
    List<String> warnings = new ArrayList<>();
    warnings.add(REHEARSAL_WARNING);
    for (String w : prepared.warnings()) {
      // the upgrade's own warnings about what it will do to the server do not apply; the ones
      // about what was found (server unreachable, package problems, Tomcat, the route) do
      if (w.startsWith("server unreachable")
          || w.startsWith("target package")
          || w.startsWith("the Tomcat version")
          || w.startsWith("No single documented upgrade")) {
        warnings.add(w);
      }
    }
    List<Step> steps = new ArrayList<>();
    steps.add(new PreflightSteps.Doctor(rt, in));
    for (UpgradeInput hop : prepared.hops()) {
      steps.add(new PreflightSteps.VerifyTargetPackage(rt, hop));
    }
    // review §2.5 (issue #108): the vendor's own preconditions, before anything is stopped
    for (UpgradeInput hop : prepared.hops()) {
      steps.add(new VendorPreconditionSteps.Verify(rt, hop, userHome));
    }
    // issue #1: the vendor's validation for every hop of a route, each package put back after
    for (UpgradeInput hop : prepared.hops()) {
      VendorSteps.WriteMasterProperties master = new VendorSteps.WriteMasterProperties(rt, hop);
      VendorSteps.StageKeystoreInit keystore = new VendorSteps.StageKeystoreInit(rt, hop);
      steps.add(master);
      steps.add(keystore);
      steps.add(new RehearsalSteps.RunVendorTest(rt, hop));
      steps.add(new RehearsalSteps.UnstageTargetPackage(rt, master, keystore));
    }
    Map<String, String> rollbackPoints = new LinkedHashMap<>();
    rollbackPoints.put(Phases.PREFLIGHT, "nothing mutated");
    rollbackPoints.put(
        Phases.VENDOR_UPGRADE,
        "only the target package's buildomatic is touched (staged files), and it is put back as"
            + " it was at the end or on failure");
    List<Path> touched = new ArrayList<>();
    for (UpgradeInput hop : prepared.hops()) {
      hop.targetBuildomatic().ifPresent(b -> touched.add(b.resolve(Buildomatic.MASTER_PROPERTIES)));
    }
    PlanSummary summary =
        new PlanSummary(
            TEST_OPERATION,
            "rehearsal of " + title(prepared, identity),
            touched,
            List.of(),
            false,
            List.of(),
            rollbackPoints,
            STRATEGY,
            warnings);
    Map<String, String> inputs = fingerprintInputs(prepared, identity, target);
    inputs.put("test", "true");
    return new Plan(
        "upgrade-test-" + RunIds.next(rt.clock()), steps, summary, PlanFingerprint.of(inputs));
  }

  @Override
  public Plan planRollback(String runId, RollbackOptions options) {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(options, "options");
    RollbackPoint point = options.toPoint();
    RunRecord run =
        rt.store()
            .run(runId)
            .orElseThrow(
                () ->
                    new UpgradeException(
                        UpgradeException.PRECHECK,
                        "unknown run " + runId,
                        "run jrs-upgrade runs list to find the upgrade run id"));
    if (!run.operation().equals(UPGRADE_OPERATION)) {
      throw new UpgradeException(
          UpgradeException.PRECHECK,
          runId + " is a " + run.operation() + " run, not an upgrade",
          "pass the id of an upgrade run");
    }
    SnapshotSet set = SnapshotSet.of(rt.home(), runId, rt.services().platform().os());
    PointBIntegrity.Report pointB =
        PointBIntegrity.check(rt.store(), rt.snapshots(), rt.files(), set, runId);
    if (!pointB.whole()) {
      throw new UpgradeException(
          UpgradeException.PRECHECK,
          "rollback point B of run "
              + runId
              + " is incomplete: "
              + String.join("; ", pointB.problems()),
          "restore "
              + set.dir()
              + " from a backup, or roll this installation back by hand;"
              + " a partial restore would leave the old webapp against the new database");
    }
    Optional<String> started =
        vendorScriptStarted(set)
            .or(
                () ->
                    readMode(set).map(m -> VendorSteps.SCRIPT_PREFIX + m.toLowerCase(Locale.ROOT)));
    if (options.restoreDatabase()) {
      refuseDatabaseRestore(runId, started);
    }
    Config config = rt.config();
    HotfixPaths paths = paths(config);
    Path installedBuildomatic =
        recordedBuildomatic(set)
            .orElseGet(() -> UpgradeInput.resolveInstalledBuildomatic(rt.locator(), config, paths));
    RestoreSteps.Input in =
        new RestoreSteps.Input(
            runId, point, set, paths, webappName(config, paths), installedBuildomatic);
    List<Step> steps = new ArrayList<>();
    steps.add(ServiceSteps.stop(rt, Phases.ROLLBACK, RestoreSteps.STOP_SERVICE));
    steps.add(new RestoreSteps.RestoreWebapp(rt, in));
    steps.add(new RestoreSteps.RestoreBuildomatic(rt, in));
    steps.add(new RestoreSteps.RestoreConfig(rt, in));
    steps.add(new RestoreSteps.RestoreKeystore(rt, in));
    steps.add(new JrsUpgradeConfigSteps.RestoreJrsUpgradeConfig(in));
    if (options.restoreDatabase()) {
      steps.add(new DatabaseRestoreSteps.RebuildDatabase(rt, in));
      steps.add(new DatabaseRestoreSteps.ReimportFullExport(rt, in));
    }
    steps.add(ServiceSteps.start(rt, Phases.ROLLBACK, RestoreSteps.START_SERVICE));
    steps.add(ServiceSteps.waitForServer(rt, Phases.ROLLBACK, RestoreSteps.WAIT_FOR_SERVER));
    steps.add(new RestoreSteps.RecordRollback(rt, in));
    List<String> warnings = new ArrayList<>();
    if (options.restoreDatabase()) {
      warnings.add(DATABASE_RESTORE_WARNING.formatted(set.resolveFullExport()));
    } else {
      warnings.add(FILES_ONLY_WARNING);
    }
    warnings.add(
        "point C restores the same point-B artefacts (spec §10.2: rollback point C = restore B)");
    Optional<String> mode =
        readMode(set).or(() -> started.map(DefaultUpgradeOperations::modeOfScript));
    mode.ifPresent(
        m ->
            warnings.add(
                "the upgrade ran in "
                    + m
                    + " mode; "
                    + (!m.equals(Mode.NEWDB.name())
                        ? "the vendor script migrated the repository database in place, which"
                            + " this rollback does not restore"
                        : options.restoreDatabase()
                            ? "the vendor script dropped and recreated the repository database;"
                                + " this rollback rebuilds the old one from "
                                + set.resolveFullExport()
                            : "the vendor script dropped and recreated the repository database,"
                                + " which this rollback does not restore: re-run with"
                                + " --restore-database to rebuild it from "
                                + set.resolveFullExport()
                                + ", or restore it from your own backup")));
    Map<String, String> rollbackPoints = new LinkedHashMap<>();
    rollbackPoints.put(
        Phases.ROLLBACK,
        "replaced trees are kept under runs/{runId}/"
            + PointB.ASIDE_DIR
            + " and put back by compensation");
    Optional<ServerIdentity> identity = identity();
    PlanSummary summary =
        new PlanSummary(
            ROLLBACK_OPERATION,
            "run "
                + runId
                + " to point "
                + point
                + (options.restoreDatabase() ? " with the database" : ""),
            List.of(in.webappDir(), in.installedBuildomatic()),
            List.of(),
            true,
            List.of(set.dir()),
            rollbackPoints,
            STRATEGY,
            warnings);
    Map<String, String> inputs = new LinkedHashMap<>();
    inputs.put("server", identity.map(ServerIdentity::fingerprintInput).orElse("unreachable"));
    inputs.put("config", configHash(config));
    inputs.put("run", runId);
    inputs.put("point", point.name());
    inputs.put("restoreDatabase", String.valueOf(options.restoreDatabase()));
    try {
      inputs.put("webappArchive", PointB.readSha(set.webappArchive()).orElse("unrecorded"));
    } catch (IOException e) {
      inputs.put("webappArchive", "unreadable");
    }
    return new Plan(
        "upgrade-rollback-" + RunIds.next(rt.clock()), steps, summary, PlanFingerprint.of(inputs));
  }

  private static Optional<String> vendorScriptStarted(SnapshotSet set) {
    try {
      return set.vendorScriptStarted();
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  private static String modeOfScript(String script) {
    return script.endsWith(Mode.NEWDB.vendorSuffix()) ? Mode.NEWDB.name() : Mode.SAMEDB.name();
  }

  /**
   * ADR-0029: the database is rebuilt only for a newdb run whose script actually started. An
   * untouched database must never be dropped, and a samedb migration is not undone by an export.
   */
  private static void refuseDatabaseRestore(String runId, Optional<String> started) {
    if (started.isEmpty()) {
      throw new UpgradeException(
          UpgradeException.PRECHECK,
          "the vendor script never started in run "
              + runId
              + ", so the repository database is still the old one and there is nothing to"
              + " rebuild",
          "run the rollback without --restore-database");
    }
    if (!started.get().endsWith(Mode.NEWDB.vendorSuffix())) {
      throw new UpgradeException(
          UpgradeException.UNSUPPORTED,
          "run "
              + runId
              + " was a samedb upgrade: "
              + started.get()
              + " migrated the schema in place, and an export cannot undo that",
          "restore the database from your own backup, then run the rollback without"
              + " --restore-database");
    }
  }

  private static Optional<String> readMode(SnapshotSet set) {
    if (!Files.isRegularFile(set.manifest())) {
      return Optional.empty();
    }
    try {
      for (String line : Files.readAllLines(set.manifest(), StandardCharsets.UTF_8)) {
        String t = line.strip();
        if (t.startsWith("\"mode\"")) {
          int q = t.indexOf(':');
          return Optional.of(t.substring(q + 1).replace("\"", "").replace(",", "").strip());
        }
      }
    } catch (IOException e) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  /**
   * The buildomatic directory the upgrade run backed up, as {@code record-upgrade} wrote it into
   * the point-B manifest; empty for a run that never got that far or a manifest that cannot be
   * read.
   */
  static Optional<Path> recordedBuildomatic(SnapshotSet set) {
    if (!Files.isRegularFile(set.manifest())) {
      return Optional.empty();
    }
    try {
      Map<?, ?> manifest =
          Json.read(Files.readString(set.manifest(), StandardCharsets.UTF_8), Map.class);
      if (manifest.get("buildomatic") instanceof String recorded && !recorded.isBlank()) {
        return Optional.of(Path.of(recorded));
      }
    } catch (IOException | RuntimeException e) {
      // an unreadable manifest falls back to resolving the directory from the configuration
    }
    return Optional.empty();
  }

  private HotfixPaths paths(Config config) {
    try {
      return HotfixPaths.from(config, rt.services().platform());
    } catch (HotfixException e) {
      throw new UpgradeException(UpgradeException.PRECHECK, e.getMessage(), e.remediation(), e);
    }
  }

  private String webappName(Config config, HotfixPaths paths) {
    return config
        .server()
        .webappName()
        .map(Config.WebappName::yamlValue)
        .or(
            () ->
                rt.services()
                    .platform()
                    .detectTomcat(paths.installDir())
                    .map(TomcatLayout::webappName))
        .orElseThrow(
            () ->
                new UpgradeException(
                    UpgradeException.PRECHECK,
                    "server.webappName is not set and no webapp was detected under "
                        + paths.installDir(),
                    "set server.webappName in config.yaml"));
  }

  private Optional<ServerIdentity> identity() {
    try {
      return Optional.of(rt.identity());
    } catch (JrsUnreachableException | RestException | ConfigException e) {
      return Optional.empty();
    }
  }

  static String configHash(Config config) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(md.digest(ConfigWriter.render(config).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
