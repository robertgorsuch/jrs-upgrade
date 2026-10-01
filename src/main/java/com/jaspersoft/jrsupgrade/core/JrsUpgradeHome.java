package com.jaspersoft.jrsupgrade.core;

import java.nio.file.Path;

/**
 * Layout of {@code $JRS_UPGRADE_HOME} (spec §5.1). Invariant: every path is derived from {@link
 * #root()} so relocating the home moves everything; nothing in the tool writes outside this tree
 * except the server installation it is asked to change, run-scoped temp files under {@link
 * #runs()}, and the files the operator asks for by path: export archives with their {@code
 * .jrs-upgrade.json} sidecar, built bundles and generated keys.
 */
public record JrsUpgradeHome(Path root) {

  /**
   * The configuration file in use: {@code jrs-upgrade.properties} when it exists (#74), otherwise
   * {@code config.yaml}, which is also where a new configuration is written by default.
   */
  public Path configFile() {
    Path properties = propertiesConfigFile();
    return java.nio.file.Files.isRegularFile(properties) ? properties : yamlConfigFile();
  }

  /** {@code config.yaml}, the default configuration format. */
  public Path yamlConfigFile() {
    return root.resolve("config.yaml");
  }

  /** {@code jrs-upgrade.properties}, the optional properties format (#74, ADR-0022). */
  public Path propertiesConfigFile() {
    return root.resolve("jrs-upgrade.properties");
  }

  public Path stateDb() {
    return root.resolve("state.db");
  }

  public Path runLock() {
    return root.resolve("runs.lock");
  }

  public Path snapshots() {
    return root.resolve("snapshots");
  }

  public Path runs() {
    return root.resolve("runs");
  }

  /**
   * Where native libraries are unpacked and run. Its own directory rather than {@link #runs()} so
   * nothing that walks run directories meets a stray shared library (review 3.4).
   */
  public Path nativeTemp() {
    return root.resolve("tmp");
  }

  public Path runDir(String runId) {
    return runs().resolve(runId);
  }

  public Path stagingDir(String runId) {
    return runDir(runId).resolve("staging");
  }

  public Path keys() {
    return root.resolve("keys");
  }

  public Path trustedKeys() {
    return keys().resolve("trusted");
  }

  public Path secretsFile() {
    return root.resolve("secrets.enc");
  }
}
