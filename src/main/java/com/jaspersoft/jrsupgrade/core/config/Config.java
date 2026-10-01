package com.jaspersoft.jrsupgrade.core.config;

import com.jaspersoft.jrsupgrade.core.platform.ServiceConfig;
import com.jaspersoft.jrsupgrade.core.secrets.SecretRef;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The effective configuration, mirroring {@code config.yaml} key for key (spec §5.1). Invariants:
 * every value the schema gives a default for is always present (auth mode, network mode, backup
 * retention, service stop timeout); everything else is an {@link Optional} so callers must decide
 * what an absent key means; paths are {@link Path}s and secret references are parsed {@link
 * SecretRef}s, so an instance never holds an unvalidated string. The tree is immutable; {@link
 * ConfigLoader} builds it and {@link ConfigWriter} serialises it back so that {@code init} output
 * round-trips. There is no {@code console} block: ADR-0038 removed the web console; {@link
 * ConfigLoader} tolerates one still sitting in a 1.x file for this release only.
 */
public record Config(
    Server server,
    Service service,
    Database database,
    Vendor vendor,
    Network network,
    Backups backups,
    Smoke smoke) {

  public Config {
    Objects.requireNonNull(server, "server");
    Objects.requireNonNull(service, "service");
    Objects.requireNonNull(database, "database");
    Objects.requireNonNull(vendor, "vendor");
    Objects.requireNonNull(network, "network");
    Objects.requireNonNull(backups, "backups");
    Objects.requireNonNull(smoke, "smoke");
  }

  /** A configuration holding nothing but the schema defaults. */
  public static Config defaults() {
    return new Config(
        Server.empty(),
        Service.empty(),
        Database.empty(),
        Vendor.empty(),
        Network.defaults(),
        Backups.defaults(),
        Smoke.empty());
  }

  /** Every configured secret reference: server, database, proxy, trust store. */
  public List<SecretRef> secretRefs() {
    List<SecretRef> refs = new ArrayList<>();
    server().auth().passwordRef().ifPresent(refs::add);
    database().passwordRef().ifPresent(refs::add);
    network().proxy().passwordRef().ifPresent(refs::add);
    network().trustStore().passwordRef().ifPresent(refs::add);
    return List.copyOf(refs);
  }

  /** Variable names of the {@code env:} references among {@link #secretRefs()} (issue #47). */
  public Set<String> envSecretNames() {
    Set<String> names = new LinkedHashSet<>();
    for (SecretRef ref : secretRefs()) {
      switch (ref) {
        case SecretRef.Env env -> names.add(env.name());
        case SecretRef.File unusedFile -> {}
        case SecretRef.Enc unusedEnc -> {}
      }
    }
    return Set.copyOf(names);
  }

  /**
   * The {@code service:} block as the platform layer wants it.
   *
   * @throws ConfigException when {@code service.kind} is not set
   */
  public ServiceConfig toServiceConfig() {
    ServiceConfig.Kind kind =
        service
            .kind()
            .orElseThrow(
                () ->
                    new ConfigException(
                        "service.kind is not set",
                        "run jrs-upgrade init or set service.kind in config.yaml"));
    if (service.forceStopAfterSeconds().isPresent()) {
      if (kind != ServiceConfig.Kind.CATALINA && kind != ServiceConfig.Kind.CTLSCRIPT) {
        throw new ConfigException(
            "service.forceStopAfterSeconds applies to the catalina and ctlscript kinds only"
                + " (service.kind is "
                + Service.kindToYaml(kind)
                + ")",
            "remove service.forceStopAfterSeconds, or set service.kind to catalina or ctlscript");
      }
      if (service.forceStopAfterSeconds().get() >= service.stopTimeoutSeconds()) {
        throw new ConfigException(
            "service.forceStopAfterSeconds ("
                + service.forceStopAfterSeconds().get()
                + ") must be below service.stopTimeoutSeconds ("
                + service.stopTimeoutSeconds()
                + ")",
            "lower service.forceStopAfterSeconds or raise service.stopTimeoutSeconds, so the"
                + " forced stop has time to take effect");
      }
    }
    return new ServiceConfig(
        kind,
        service.name(),
        service.scriptPath(),
        Duration.ofSeconds(service.stopTimeoutSeconds()),
        service.forceStopAfterSeconds().map(Duration::ofSeconds));
  }

  /** {@code server.auth.mode}. */
  public enum AuthMode implements YamlValued {
    BASIC,
    FORM,
    TOKEN;

    public static final AuthMode DEFAULT = BASIC;

    @Override
    public String yamlValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * {@code server.auth.tokenLocation}: where token mode sends the pre-authentication token, as the
   * {@code pp} query parameter or the {@code pp} request header (issue #45, ADR-0018).
   */
  public enum TokenLocation implements YamlValued {
    QUERY,
    HEADER;

    public static final TokenLocation DEFAULT = QUERY;

    @Override
    public String yamlValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** {@code server.webappName}; explicit, never derived from the URL. */
  public enum WebappName implements YamlValued {
    JASPERSERVER("jasperserver"),
    JASPERSERVER_PRO("jasperserver-pro");

    private final String yaml;

    WebappName(String yaml) {
      this.yaml = yaml;
    }

    @Override
    public String yamlValue() {
      return yaml;
    }
  }

  /** {@code database.type}. */
  public enum DatabaseType implements YamlValued {
    POSTGRESQL,
    MYSQL,
    ORACLE,
    MSSQL,
    DB2;

    @Override
    public String yamlValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** {@code network.mode}; isolated refuses every host but the server's. */
  public enum NetworkMode implements YamlValued {
    ISOLATED,
    PUBLIC;

    public static final NetworkMode DEFAULT = ISOLATED;

    @Override
    public String yamlValue() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * The {@code server:} block. {@code buildomaticDir} names the installed vendor tree when it does
   * not sit under {@code installDir} (another volume, a mount, a network share); when set it is
   * authoritative and never replaced by a discovered directory (ADR-0013).
   */
  public record Server(
      Optional<URI> baseUrl,
      Optional<WebappName> webappName,
      Optional<Path> installDir,
      Optional<Path> tomcatDir,
      Optional<Path> buildomaticDir,
      Optional<String> runAsUser,
      Auth auth) {

    public Server {
      Objects.requireNonNull(baseUrl, "baseUrl");
      Objects.requireNonNull(webappName, "webappName");
      Objects.requireNonNull(installDir, "installDir");
      Objects.requireNonNull(tomcatDir, "tomcatDir");
      Objects.requireNonNull(buildomaticDir, "buildomaticDir");
      Objects.requireNonNull(runAsUser, "runAsUser");
      Objects.requireNonNull(auth, "auth");
    }

    /**
     * True when the configuration names any part of a JasperReports Server installation on this
     * machine; false for a jrs-upgrade that reaches the server over REST only (#68).
     */
    public boolean namesLocalInstallation() {
      return installDir.isPresent() || tomcatDir.isPresent() || buildomaticDir.isPresent();
    }

    public static Server empty() {
      return new Server(
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Optional.empty(),
          Auth.defaults());
    }
  }

  /** The {@code server.auth:} block. */
  public record Auth(
      AuthMode mode,
      Optional<String> username,
      Optional<SecretRef> passwordRef,
      TokenLocation tokenLocation) {

    public Auth {
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(username, "username");
      Objects.requireNonNull(passwordRef, "passwordRef");
      Objects.requireNonNull(tokenLocation, "tokenLocation");
    }

    /** The block without {@code tokenLocation}, which then takes its default. */
    public Auth(AuthMode mode, Optional<String> username, Optional<SecretRef> passwordRef) {
      this(mode, username, passwordRef, TokenLocation.DEFAULT);
    }

    public static Auth defaults() {
      return new Auth(AuthMode.DEFAULT, Optional.empty(), Optional.empty());
    }
  }

  /**
   * The {@code service:} block; {@code kind} spellings follow the YAML ({@code windows-service}).
   * {@code forceStopAfterSeconds} is off when absent; whether it fits the kind and the stop timeout
   * is checked by {@link Config#toServiceConfig()}, where the other service rules live (ADR-0016).
   */
  public record Service(
      Optional<ServiceConfig.Kind> kind,
      Optional<String> name,
      Optional<Path> scriptPath,
      int stopTimeoutSeconds,
      Optional<Integer> forceStopAfterSeconds) {

    public static final int DEFAULT_STOP_TIMEOUT_SECONDS = 180;

    public Service {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(name, "name");
      Objects.requireNonNull(scriptPath, "scriptPath");
      Objects.requireNonNull(forceStopAfterSeconds, "forceStopAfterSeconds");
      if (stopTimeoutSeconds < 1) {
        throw new IllegalArgumentException("stopTimeoutSeconds must be >= 1");
      }
      if (forceStopAfterSeconds.isPresent() && forceStopAfterSeconds.get() < 1) {
        throw new IllegalArgumentException("forceStopAfterSeconds must be >= 1");
      }
    }

    /** A service block without {@code forceStopAfterSeconds}. */
    public Service(
        Optional<ServiceConfig.Kind> kind,
        Optional<String> name,
        Optional<Path> scriptPath,
        int stopTimeoutSeconds) {
      this(kind, name, scriptPath, stopTimeoutSeconds, Optional.empty());
    }

    public static Service empty() {
      return new Service(
          Optional.empty(), Optional.empty(), Optional.empty(), DEFAULT_STOP_TIMEOUT_SECONDS);
    }

    /** YAML spelling of a service kind, e.g. {@code windows-service}. */
    public static String kindToYaml(ServiceConfig.Kind kind) {
      return kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Inverse of {@link #kindToYaml}; empty for an unknown spelling. */
    public static Optional<ServiceConfig.Kind> kindFromYaml(String yaml) {
      for (ServiceConfig.Kind k : ServiceConfig.Kind.values()) {
        if (kindToYaml(k).equals(yaml)) {
          return Optional.of(k);
        }
      }
      return Optional.empty();
    }
  }

  /** The {@code database:} block; only needed for hotfixes that carry SQL. */
  public record Database(
      Optional<DatabaseType> type,
      Optional<String> url,
      Optional<String> username,
      Optional<SecretRef> passwordRef,
      Optional<Path> driverDir) {

    public Database {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(url, "url");
      Objects.requireNonNull(username, "username");
      Objects.requireNonNull(passwordRef, "passwordRef");
      Objects.requireNonNull(driverDir, "driverDir");
    }

    public static Database empty() {
      return new Database(
          Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
  }

  /** The {@code vendor:} block: the JDK buildomatic runs with, never jrs-upgrade's own runtime. */
  public record Vendor(Optional<Path> javaHome) {

    public Vendor {
      Objects.requireNonNull(javaHome, "javaHome");
    }

    public static Vendor empty() {
      return new Vendor(Optional.empty());
    }
  }

  /** The {@code network:} block. */
  public record Network(NetworkMode mode, Proxy proxy, TrustStore trustStore) {

    public Network {
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(proxy, "proxy");
      Objects.requireNonNull(trustStore, "trustStore");
    }

    public static Network defaults() {
      return new Network(NetworkMode.DEFAULT, Proxy.empty(), TrustStore.empty());
    }
  }

  /**
   * The {@code network.proxy:} block. {@code noProxy} lists hosts that bypass the proxy: a bare
   * host name matches exactly, a {@code .suffix} matches every host under it; loopback always
   * bypasses.
   */
  public record Proxy(
      Optional<String> host,
      Optional<Integer> port,
      Optional<String> username,
      Optional<SecretRef> passwordRef,
      List<String> noProxy) {

    public Proxy {
      Objects.requireNonNull(host, "host");
      Objects.requireNonNull(port, "port");
      Objects.requireNonNull(username, "username");
      Objects.requireNonNull(passwordRef, "passwordRef");
      noProxy = List.copyOf(Objects.requireNonNull(noProxy, "noProxy"));
    }

    public Proxy(
        Optional<String> host,
        Optional<Integer> port,
        Optional<String> username,
        Optional<SecretRef> passwordRef) {
      this(host, port, username, passwordRef, List.of());
    }

    public static Proxy empty() {
      return new Proxy(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
  }

  /** The {@code network.trustStore:} block. */
  public record TrustStore(Optional<Path> path, Optional<SecretRef> passwordRef) {

    public TrustStore {
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(passwordRef, "passwordRef");
    }

    public static TrustStore empty() {
      return new TrustStore(Optional.empty(), Optional.empty());
    }
  }

  /** The {@code backups:} block. */
  public record Backups(int retentionDays, int maxSnapshots) {

    public static final int DEFAULT_RETENTION_DAYS = 30;
    public static final int DEFAULT_MAX_SNAPSHOTS = 20;

    public Backups {
      if (retentionDays < 0) {
        throw new IllegalArgumentException("backups.retentionDays must be >= 0");
      }
      if (maxSnapshots < 1) {
        throw new IllegalArgumentException("backups.maxSnapshots must be >= 1");
      }
    }

    public static Backups defaults() {
      return new Backups(DEFAULT_RETENTION_DAYS, DEFAULT_MAX_SNAPSHOTS);
    }
  }

  /** The {@code smoke:} block. */
  public record Smoke(Optional<String> reportUri) {

    public Smoke {
      Objects.requireNonNull(reportUri, "reportUri");
    }

    public static Smoke empty() {
      return new Smoke(Optional.empty());
    }
  }

  /** An enumeration with a fixed YAML spelling. */
  public interface YamlValued {
    String yamlValue();

    /** The constant whose {@link #yamlValue()} equals {@code text}, or empty. */
    static <E extends Enum<E> & YamlValued> Optional<E> fromYaml(Class<E> type, String text) {
      for (E e : type.getEnumConstants()) {
        if (e.yamlValue().equals(text)) {
          return Optional.of(e);
        }
      }
      return Optional.empty();
    }
  }
}
