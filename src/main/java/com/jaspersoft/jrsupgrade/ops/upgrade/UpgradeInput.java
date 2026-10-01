package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.jrs.api.ServerIdentity;
import com.jaspersoft.jrsupgrade.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsupgrade.jrs.vendor.BuildomaticLocator;
import com.jaspersoft.jrsupgrade.jrs.vendor.BuildomaticResolution;
import com.jaspersoft.jrsupgrade.ops.hotfix.HotfixPaths;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations.UpgradeOptions;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything the upgrade steps need that was resolved at planning time: the operator's options, the
 * installation layout, the inspected target package and the server identity as seen when the plan
 * was built (empty when the server was unreachable). Invariants: paths are absolute and normalised;
 * {@code masterOverrides} never contains a key whose name contains "pass" (spec §7.4 copies the
 * database settings minus passwords); {@code installedBuildomatic} is the tree the locator settled
 * on at planning time, which may be on another volume or a share (ADR-0013); {@code hop} says which
 * hop of a route this input describes (issue #1, ADR-0002): a transit hop's steps carry the hop's
 * version in their ids and keep their run files in a directory of their own, so two hops of one
 * plan never share a journal row or a marker, while a single hop and the final hop of a route keep
 * the ids and files a one-hop upgrade always had; the record is immutable.
 */
record UpgradeInput(
    UpgradeOptions options,
    HotfixPaths paths,
    String webappName,
    TargetPackage target,
    Optional<ServerIdentity> identity,
    Map<String, String> masterOverrides,
    Path installedBuildomatic,
    Hop hop) {

  static final String BUILDOMATIC = "buildomatic";

  /**
   * One hop of an upgrade route. A transit hop (ADR-0002) runs the vendor script of an intermediate
   * package against the repository database and deploys nothing: buildomatic is told {@code
   * appServerType=skipAppServerCheck} and pointed at a scratch Tomcat directory, never the live
   * one; only the final hop deploys and starts. {@code from} is the version the hop starts from
   * when that is not the server's own (every hop of a route but the first).
   */
  record Hop(int number, int of, boolean transit, Optional<String> from) {
    static final Hop SINGLE = new Hop(1, 1, false, Optional.empty());

    Hop {
      Objects.requireNonNull(from, "from");
      if (number < 1 || number > of) {
        throw new IllegalArgumentException("hop " + number + " of " + of);
      }
      if (transit && number == of) {
        throw new IllegalArgumentException("the last hop of a route is never a transit hop");
      }
    }
  }

  /** The input of a one-hop upgrade. */
  UpgradeInput(
      UpgradeOptions options,
      HotfixPaths paths,
      String webappName,
      TargetPackage target,
      Optional<ServerIdentity> identity,
      Map<String, String> masterOverrides,
      Path installedBuildomatic) {
    this(
        options,
        paths,
        webappName,
        target,
        identity,
        masterOverrides,
        installedBuildomatic,
        Hop.SINGLE);
  }

  boolean transit() {
    return hop.transit();
  }

  /** {@code id} for this hop: unchanged for a single or final hop, {@code id@<version>} else. */
  String scoped(String id) {
    return hop.transit() ? id + "@" + options.toVersion() : id;
  }

  /** Where this hop's steps keep their markers and backups for run {@code ctx.runId()}. */
  Path runDir(Context ctx) {
    Path run = ctx.home().runDir(ctx.runId());
    return hop.transit() ? run.resolve("transit-" + options.toVersion()) : run;
  }

  UpgradeInput {
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(paths, "paths");
    Objects.requireNonNull(webappName, "webappName");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(hop, "hop");
    masterOverrides = Map.copyOf(masterOverrides);
    installedBuildomatic =
        Objects.requireNonNull(installedBuildomatic, "installedBuildomatic")
            .toAbsolutePath()
            .normalize();
  }

  /**
   * The installed buildomatic directory for {@code config}. A tree nobody could find keeps the
   * historical {@code <installDir>/buildomatic}, so the backup precheck names the missing tree; an
   * unreachable {@code server.buildomaticDir} or an ambiguous search refuses the plan instead,
   * because backing up or restoring a guessed tree is worse than not starting.
   */
  static Path resolveInstalledBuildomatic(
      BuildomaticLocator locator, Config config, HotfixPaths paths) {
    return switch (locator.resolve(config)) {
      case BuildomaticResolution.Found found -> found.buildomatic().dir();
      case BuildomaticResolution.NotFound missing ->
          switch (missing.reason()) {
            case NOT_FOUND, NOT_CONFIGURED -> paths.installDir().resolve(BUILDOMATIC);
            case CONFIGURED_UNREACHABLE, AMBIGUOUS ->
                throw new UpgradeException(
                    UpgradeException.PRECHECK, missing.detail(), missing.remediation());
          };
    };
  }

  Path installDir() {
    return paths.installDir();
  }

  Path tomcatDir() {
    return paths.tomcatDir();
  }

  /**
   * The Tomcat the upgraded webapp runs in: {@code --tomcat-dir} when given, else the current one.
   */
  Path hostTomcatDir() {
    return options.tomcatDir().map(p -> p.toAbsolutePath().normalize()).orElse(tomcatDir());
  }

  Path webappDir() {
    return tomcatDir().resolve("webapps").resolve(webappName);
  }

  Optional<Path> targetBuildomatic() {
    return target.buildomatic().map(b -> b.dir());
  }

  /**
   * The target buildomatic's {@code default_master.properties} as it is right now, minus password
   * keys, or empty when there is none: read afresh so a precheck sees a file the operator edited
   * after the plan was built. This is the file {@code write-master-properties} appends the
   * installed keys to.
   */
  Map<String, String> targetMasterProperties(BuildomaticLocator locator) {
    Path dir = targetBuildomatic().orElse(target.dir().resolve(BUILDOMATIC));
    return locator.at(dir).map(Buildomatic::masterProperties).orElse(Map.of());
  }

  Optional<String> currentVersion() {
    return identity.map(ServerIdentity::version);
  }

  SnapshotSet snapshots(Context ctx) {
    return SnapshotSet.of(ctx.home(), ctx.runId(), ctx.platform().os());
  }

  /** Configuration files that exist right now and belong in the point-B backup. */
  List<Path> configFiles() {
    List<Path> files = new ArrayList<>();
    add(files, installedBuildomatic().resolve("default_master.properties"));
    Path localhost = tomcatDir().resolve("conf").resolve("Catalina").resolve("localhost");
    if (Files.isDirectory(localhost)) {
      try (DirectoryStream<Path> xmls = Files.newDirectoryStream(localhost, "*.xml")) {
        List<Path> sorted = new ArrayList<>();
        for (Path xml : xmls) {
          if (Files.isRegularFile(xml)) {
            sorted.add(xml);
          }
        }
        sorted.sort(null);
        files.addAll(sorted);
      } catch (IOException e) {
        // an unreadable context directory is reported by the backup step itself
      }
    }
    add(files, webappDir().resolve("META-INF").resolve("context.xml"));
    add(files, webappDir().resolve("WEB-INF").resolve("js.jdbc.properties"));
    return List.copyOf(files);
  }

  private static void add(List<Path> files, Path file) {
    if (Files.isRegularFile(file)) {
      files.add(file.toAbsolutePath().normalize());
    }
  }
}
