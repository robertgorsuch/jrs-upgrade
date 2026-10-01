package com.jaspersoft.jrsupgrade.app;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.compat.CompatMatrix;
import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.config.ConfigLoader;
import com.jaspersoft.jrsupgrade.core.config.JrsUpgradeHomeResolver;
import com.jaspersoft.jrsupgrade.core.platform.HomeRedirect;
import com.jaspersoft.jrsupgrade.core.platform.NativeTempDir;
import com.jaspersoft.jrsupgrade.core.platform.OperatorPrompt;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.platform.Platforms;
import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.core.secrets.EncryptedSecretStore;
import com.jaspersoft.jrsupgrade.core.secrets.PassphraseSource;
import com.jaspersoft.jrsupgrade.core.secrets.Secret;
import com.jaspersoft.jrsupgrade.core.secrets.SecretException;
import com.jaspersoft.jrsupgrade.core.secrets.SecretRef;
import com.jaspersoft.jrsupgrade.core.secrets.SecretResolver;
import com.jaspersoft.jrsupgrade.core.state.StateStore;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapterFactory;
import com.jaspersoft.jrsupgrade.ops.BuildomaticDefaults;
import com.jaspersoft.jrsupgrade.ops.Lazy;
import com.jaspersoft.jrsupgrade.ops.Services;
import java.io.Console;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the {@link Services} bundle from the global options (spec §5.1, §5.2, §5.5). Invariants:
 * the home is {@code --home}, else {@code JRS_UPGRADE_HOME}, else the platform default;
 * configuration precedence is flag &gt; env &gt; file &gt; default; every {@code env:} and {@code
 * file:} secret reference in the configuration is resolved once here and registered with the global
 * redactor before any command output is written ({@code enc:} references too when a non-interactive
 * passphrase is available, so no command prompts merely to redact); the state store and adapter are
 * opened lazily and both are closed with this object (the adapter logs its session out); a {@link
 * com.jaspersoft.jrsupgrade.core.config.ConfigException} propagates so the command exits 2 without
 * touching anything.
 */
final class Bootstrap implements AutoCloseable {

  static final String PASSPHRASE_ENV = "JRS_UPGRADE_PASSPHRASE";
  private static final Logger LOG = LoggerFactory.getLogger(Bootstrap.class);

  private final Services services;
  private final Lazy<StateStore> store;
  private final Lazy<JrsAdapter> adapter;
  private final EncryptedSecretStore secretStore;
  private final Map<String, java.nio.file.Path> fromBuildomatic;

  private Bootstrap(
      Services services,
      Lazy<StateStore> store,
      Lazy<JrsAdapter> adapter,
      EncryptedSecretStore secretStore,
      Map<String, java.nio.file.Path> fromBuildomatic) {
    this.services = services;
    this.store = store;
    this.adapter = adapter;
    this.secretStore = secretStore;
    this.fromBuildomatic = Map.copyOf(fromBuildomatic);
  }

  static Bootstrap open(GlobalOptions options, Map<String, String> env, Clock clock) {
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(env, "env");
    boolean interactive = !options.nonInteractive() && Terminal.present();
    OperatorPrompt prompt = interactive ? new ConsolePrompt() : OperatorPrompt.nonInteractive();
    Platform detected = Platforms.detect(prompt);
    JrsUpgradeHome home =
        options
            .home()
            .map(h -> new JrsUpgradeHome(HomeRedirect.follow(h)))
            .orElseGet(() -> JrsUpgradeHomeResolver.resolve(env, detected));
    // review 4.1: the home holds secrets.enc, a 1.x console.token, state.db and the snapshots, so a
    // home jrs-upgrade creates is private to its owner from the start. An existing home is left as
    // the
    // operator set it up; it is only read, never re-permissioned.
    createHome(detected, home);
    // review 3.4: the SQLite driver runs its native library from java.io.tmpdir, which a
    // CIS-hardened Linux host mounts noexec; the home is the tool's own writable directory
    NativeTempDir.use(home.nativeTemp());
    Config loaded = new ConfigLoader().load(home, env, options.set());
    // The manual and systemd controllers judge Tomcat by the configured install directory and its
    // server.xml ports; without this they watched every Tomcat on the host (ADR-0014).
    Platform platform = loaded.server().installDir().map(detected::withInstallDir).orElse(detected);
    // #73: database settings config.yaml leaves out come from buildomatic's
    // default_master.properties
    BuildomaticDefaults.Result defaults = BuildomaticDefaults.apply(loaded, platform);
    Config config = defaults.config();

    List<PassphraseSource> sources = new ArrayList<>();
    options
        .passphraseFile()
        .ifPresent(f -> sources.add(new PassphraseSource.FromFile(f, platform.files())));
    sources.add(new PassphraseSource.FromEnv(env));
    if (interactive) {
      sources.add(new PassphraseSource.FromConsole());
    }
    boolean passphraseWithoutPrompt =
        options.passphraseFile().isPresent() || env.containsKey(PASSPHRASE_ENV);
    EncryptedSecretStore secretStore =
        new EncryptedSecretStore(
            home.secretsFile(),
            new PassphraseSource.Chain(sources),
            f -> OwnerOnlyFiles.restrictToOwner(platform, f));
    SecretResolver secrets = new SecretResolver(env, platform.files(), secretStore);
    Redactor redactor = Redactor.global();
    registerSecrets(config, secrets, redactor, passphraseWithoutPrompt);

    Lazy<StateStore> store = Lazy.of(() -> StateStore.open(home, clock));
    Lazy<JrsAdapter> adapter =
        Lazy.of(() -> JrsAdapterFactory.load().connect(config, secrets, redactor, platform));
    Services services =
        new Services(
            home,
            config,
            platform,
            secrets,
            redactor,
            CompatMatrix.load(),
            store,
            adapter,
            clock,
            interactive);
    return new Bootstrap(services, store, adapter, secretStore, defaults.filled());
  }

  /**
   * Creates a missing home directory owner-only on both operating systems (review 4.1, issue #50);
   * an existing one is left alone.
   */
  static void createHome(Platform platform, JrsUpgradeHome home) {
    java.nio.file.Path root = home.root();
    if (java.nio.file.Files.isDirectory(root)) {
      return;
    }
    try {
      if (platform.os() == Platform.OsFamily.LINUX
          && java.nio.file.FileSystems.getDefault()
              .supportedFileAttributeViews()
              .contains("posix")) {
        java.nio.file.Files.createDirectories(
            root,
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
      } else {
        java.nio.file.Files.createDirectories(root);
        if (platform.os() == Platform.OsFamily.WINDOWS) {
          try {
            OwnerOnlyFiles.restrictDirectoryToOwner(platform, root);
          } catch (java.io.IOException e) {
            LOG.warn(
                "created {} but could not make it private to its owner: {}; restrict it with"
                    + " icacls before storing secrets there",
                root,
                e.getMessage());
          }
        }
      }
    } catch (java.io.IOException e) {
      LOG.debug("cannot create {}: {}", root, e.getMessage());
    }
  }

  Services services() {
    return services;
  }

  /**
   * The {@code secrets.enc} store behind every {@code enc:} reference, with the passphrase chain.
   */
  EncryptedSecretStore secretStore() {
    return secretStore;
  }

  /**
   * The configuration keys read from buildomatic's default_master.properties, with the file (#73).
   */
  Map<String, java.nio.file.Path> fromBuildomatic() {
    return fromBuildomatic;
  }

  @Override
  public void close() {
    // #114: end the server session a form login opened; an adapter never built is not built now
    adapter.peek().ifPresent(JrsAdapter::close);
    store.peek().ifPresent(StateStore::close);
  }

  /** Registers every resolvable configured secret so no output stream can leak it. */
  static void registerSecrets(
      Config config, SecretResolver secrets, Redactor redactor, boolean encWithoutPrompt) {
    for (SecretRef ref : config.secretRefs()) {
      if (ref instanceof SecretRef.Enc && !encWithoutPrompt) {
        continue;
      }
      try (Secret secret = secrets.resolve(ref)) {
        redactor.register(secret);
      } catch (SecretException e) {
        LOG.debug("secret {} not resolvable at startup: {}", ref.render(), e.getMessage());
      }
    }
  }

  /** Console-backed operator prompt: prints the instruction and waits for Enter. */
  static final class ConsolePrompt implements OperatorPrompt {
    @Override
    public void instruct(String message) {
      Console console =
          Terminal.console()
              .orElseThrow(
                  () -> new IllegalStateException("no console for operator prompt: " + message));
      console.printf("%s%n", Redactor.global().redact(message));
      console.printf("Press Enter when done... ");
      console.readLine();
    }

    @Override
    public boolean interactive() {
      return true;
    }
  }
}
