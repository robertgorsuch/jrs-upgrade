package com.jaspersoft.jrsupgrade.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.json.Json;
import com.jaspersoft.jrsupgrade.core.secrets.SecretRef;
import com.jaspersoft.jrsupgrade.jrs.api.BrokenDependencies;
import com.jaspersoft.jrsupgrade.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsupgrade.ops.exim.ExportImportOperations;
import com.jaspersoft.jrsupgrade.ops.upgrade.UpgradeOperations;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Maps an operation name plus its JSON arguments back to a {@link Plan} builder so {@code runs
 * recover} can rebuild the plan of a pending run from {@code StoredPlan.argsJson()} (spec §6.6).
 * Invariants: the argument documents written by {@link #upgradeArgs}, {@link #exportArgs} and
 * {@link #importArgs} are exactly what {@link #rebuild} reads, so a plan stored by any mutating
 * command always rebuilds; {@code uris, usersRoles, accessEvents, fullServer, strategy, out} and
 * {@code archive, update, skipUserUpdate, sourceKeystore, sourceKeystorePassword, strategy,
 * snapshotStopsService, noSnapshot} are the field names the export and import argument documents
 * use; an unknown operation fails with a message naming the known ones; the operations are obtained
 * lazily so listing the registry never touches the server.
 */
public final class PlanRegistry {

  public static final String EXPORT = "export";
  public static final String IMPORT = "import";

  public static final String UPGRADE = UpgradeOperations.UPGRADE_OPERATION;
  public static final String UPGRADE_ROLLBACK = UpgradeOperations.ROLLBACK_OPERATION;
  public static final String UPGRADE_TEST = UpgradeOperations.TEST_OPERATION;

  private final Map<String, Function<JsonNode, Plan>> builders = new LinkedHashMap<>();

  public PlanRegistry(Supplier<ExportImportOperations> exim, Supplier<UpgradeOperations> upgrade) {
    Objects.requireNonNull(exim, "exim");
    Objects.requireNonNull(upgrade, "upgrade");
    builders.put(UPGRADE, args -> upgrade.get().planUpgrade(upgradeOptions(args)));
    builders.put(UPGRADE_TEST, args -> upgrade.get().planTest(upgradeOptions(args)));
    builders.put(
        UPGRADE_ROLLBACK,
        args ->
            upgrade
                .get()
                .planRollback(
                    required(args, "runId"),
                    new UpgradeOperations.RollbackOptions(
                        UpgradeOperations.RollbackPoint.valueOf(required(args, "point")),
                        args.path("restoreDatabase").asBoolean(false))));
    builders.put(EXPORT, args -> exim.get().planExport(exportOptions(args)));
    builders.put(IMPORT, args -> exim.get().planImport(importOptions(args)));
  }

  public Plan rebuild(String operation, String argsJson) {
    Function<JsonNode, Plan> builder = builders.get(operation);
    if (builder == null) {
      throw new IllegalArgumentException(
          "unknown operation '"
              + operation
              + "'; this build can rebuild plans for: "
              + String.join(", ", builders.keySet()));
    }
    JsonNode args;
    try {
      args = Json.mapper().readTree(argsJson);
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("stored arguments of " + operation + " are not JSON", e);
    }
    return builder.apply(args);
  }

  public static String exportArgs(ExportImportOperations.ExportOptions o) {
    ObjectNode node = Json.mapper().createObjectNode();
    ArrayNode uris = node.putArray("uris");
    o.uris().stream().sorted().forEach(uris::add);
    node.put("usersRoles", o.usersRoles());
    node.put("accessEvents", o.accessEvents());
    node.put("auditEvents", o.auditEvents());
    node.put("monitoring", o.monitoring());
    node.put("settings", o.settings());
    node.put("fullServer", o.fullServer());
    node.put("stopService", o.stopService());
    putStrategy(node, o.strategy());
    node.put("out", o.out().toAbsolutePath().normalize().toString());
    putText(node, "keyAlias", o.keyAlias());
    putText(node, "organization", o.organization());
    node.put("skipDependentResources", o.skipDependentResources());
    node.put("skipFavoriteResources", o.skipFavoriteResources());
    return Json.write(node);
  }

  public static String importArgs(ExportImportOperations.ImportOptions o) {
    ObjectNode node = Json.mapper().createObjectNode();
    node.put("archive", o.archive().toAbsolutePath().normalize().toString());
    node.put("update", o.update());
    node.put("skipUserUpdate", o.skipUserUpdate());
    node.put("accessEvents", o.accessEvents());
    node.put("auditEvents", o.auditEvents());
    node.put("monitoring", o.monitoring());
    node.put("settings", o.settings());
    node.put("skipThemes", o.skipThemes());
    node.put("brokenDependencies", o.brokenDependencies().wire());
    if (o.sourceKeystore().isPresent()) {
      node.put("sourceKeystore", o.sourceKeystore().get().toAbsolutePath().normalize().toString());
    } else {
      node.putNull("sourceKeystore");
    }
    if (o.sourceKeystorePassword().isPresent()) {
      node.put("sourceKeystorePassword", o.sourceKeystorePassword().get().render());
    } else {
      node.putNull("sourceKeystorePassword");
    }
    putStrategy(node, o.strategy());
    putText(node, "keyAlias", o.keyAlias());
    putText(node, "organization", o.organization());
    node.put("mergeOrganization", o.mergeOrganization());
    node.put("forceVersion", o.forceVersion());
    node.put("keepThemes", o.keepThemes());
    node.put("snapshotStopsService", o.snapshotStopsService());
    node.put("noSnapshot", o.noSnapshot());
    return Json.write(node);
  }

  private static void putText(ObjectNode node, String field, Optional<String> value) {
    if (value.isPresent()) {
      node.put(field, value.get());
    } else {
      node.putNull(field);
    }
  }

  public static ExportImportOperations.ExportOptions exportOptions(JsonNode args) {
    Set<String> uris = new LinkedHashSet<>();
    for (JsonNode u : args.path("uris")) {
      if (u.isTextual() && !u.asText().isBlank()) {
        uris.add(u.asText());
      }
    }
    return new ExportImportOperations.ExportOptions(
        uris,
        args.path("usersRoles").asBoolean(false),
        args.path("accessEvents").asBoolean(false),
        args.path("auditEvents").asBoolean(false),
        args.path("monitoring").asBoolean(false),
        args.path("settings").asBoolean(false),
        args.path("fullServer").asBoolean(false),
        Path.of(required(args, "out")),
        strategy(args),
        // #67: arguments stored before the flag existed describe a plan that stopped the service
        args.path("stopService").asBoolean(true),
        text(args, "keyAlias"),
        text(args, "organization"),
        // arguments stored before the option existed describe an export made with neither
        args.path("skipDependentResources").asBoolean(false),
        args.path("skipFavoriteResources").asBoolean(false));
  }

  public static ExportImportOperations.ImportOptions importOptions(JsonNode args) {
    return new ExportImportOperations.ImportOptions(
        Path.of(required(args, "archive")),
        args.path("update").asBoolean(false),
        args.path("skipUserUpdate").asBoolean(false),
        args.path("accessEvents").asBoolean(false),
        args.path("auditEvents").asBoolean(false),
        args.path("monitoring").asBoolean(false),
        args.path("settings").asBoolean(false),
        args.path("skipThemes").asBoolean(false),
        text(args, "sourceKeystore").map(Path::of),
        text(args, "sourceKeystorePassword").map(SecretRef::parse),
        strategy(args),
        // arguments stored before the option existed describe a plan that used the server default
        text(args, "brokenDependencies")
            .map(BrokenDependencies::parse)
            .orElse(BrokenDependencies.FAIL),
        text(args, "keyAlias"),
        text(args, "organization"),
        args.path("mergeOrganization").asBoolean(false),
        // arguments stored before the option existed describe an import that was not forced
        args.path("forceVersion").asBoolean(false),
        // arguments stored before the option existed describe an import with the themes default
        args.path("keepThemes").asBoolean(false),
        // ADR-0040: arguments stored before the key existed describe a plan whose vendor snapshot
        // stopped and started the service, so a journaled run rebuilds the steps it recorded
        args.path("snapshotStopsService").asBoolean(true),
        // and one that took the pre-import snapshot
        args.path("noSnapshot").asBoolean(false));
  }

  private static void putStrategy(ObjectNode node, Optional<ExportImportStrategy.Kind> kind) {
    if (kind.isPresent()) {
      node.put("strategy", StrategyFlag.render(kind.get()));
    } else {
      node.putNull("strategy");
    }
  }

  private static Optional<ExportImportStrategy.Kind> strategy(JsonNode args) {
    return text(args, "strategy").flatMap(StrategyFlag::parse);
  }

  public static UpgradeOperations.UpgradeOptions upgradeOptions(JsonNode args) {
    return new UpgradeOperations.UpgradeOptions(
        required(args, "to"),
        Path.of(required(args, "package")),
        UpgradeOperations.Mode.valueOf(required(args, "mode")),
        args.path("dbBackupConfirmed").asBoolean(false),
        // arguments stored before the option existed describe an upgrade in the same Tomcat
        text(args, "tomcatDir").map(Path::of),
        // and one that takes its own export with the server's own key (ADR-0028)
        text(args, "export").map(Path::of),
        text(args, "keyAlias"),
        text(args, "keyPasswordRef").map(SecretRef::parse),
        // and one that leaves the events where the vendor script leaves them (issue #106)
        args.path("includeEvents").asBoolean(false),
        // and one that leaves the stored passwords as they are (issue #108)
        args.path("migratePasswords").asBoolean(false));
  }

  public static String upgradeArgs(UpgradeOperations.UpgradeOptions options) {
    ObjectNode node = Json.mapper().createObjectNode();
    node.put("to", options.toVersion());
    node.put("package", options.packageDir().toString());
    node.put("mode", options.mode().name());
    node.put("dbBackupConfirmed", options.dbBackupConfirmed());
    node.put("includeEvents", options.includeEvents());
    node.put("migratePasswords", options.migratePasswords());
    if (options.tomcatDir().isPresent()) {
      node.put("tomcatDir", options.tomcatDir().get().toAbsolutePath().normalize().toString());
    } else {
      node.putNull("tomcatDir");
    }
    if (options.existingExport().isPresent()) {
      node.put("export", options.existingExport().get().toString());
    } else {
      node.putNull("export");
    }
    if (options.keyAlias().isPresent()) {
      node.put("keyAlias", options.keyAlias().get());
    } else {
      node.putNull("keyAlias");
    }
    if (options.keyPassword().isPresent()) {
      node.put("keyPasswordRef", options.keyPassword().get().render());
    } else {
      node.putNull("keyPasswordRef");
    }
    return Json.write(node);
  }

  public static String upgradeRollbackArgs(String runId, UpgradeOperations.RollbackPoint point) {
    return upgradeRollbackArgs(runId, new UpgradeOperations.RollbackOptions(point, false));
  }

  public static String upgradeRollbackArgs(
      String runId, UpgradeOperations.RollbackOptions options) {
    ObjectNode node = Json.mapper().createObjectNode();
    node.put("runId", runId);
    node.put("point", options.toPoint().name());
    node.put("restoreDatabase", options.restoreDatabase());
    return Json.write(node);
  }

  private static Optional<String> text(JsonNode args, String field) {
    JsonNode v = args.get(field);
    if (v == null || !v.isTextual() || v.asText().isBlank()) {
      return Optional.empty();
    }
    return Optional.of(v.asText());
  }

  private static String required(JsonNode args, String field) {
    return text(args, field)
        .orElseThrow(
            () -> new IllegalArgumentException("stored plan arguments lack '" + field + "'"));
  }
}
