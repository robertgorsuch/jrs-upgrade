package com.jaspersoft.jrsupgrade.jrs.keystore;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.platform.TomcatLayout;
import com.jaspersoft.jrsupgrade.jrs.api.KeystoreInfo;
import com.jaspersoft.jrsupgrade.jrs.vendor.Buildomatic;
import com.jaspersoft.jrsupgrade.jrs.vendor.BuildomaticLocator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Finds the server keystore ({@code .jrsks}) and its properties ({@code .jrsksp}) (spec §9.3).
 * Invariants: the location is settled in this order and the returned {@code reason} names which
 * source settled it: the running server's own {@code WEB-INF/classes/keystore.init.properties} (the
 * file the server reads at startup; vendor review 1.5), then buildomatic's copy (what the installer
 * wrote), then the home directory of {@code server.runAsUser} by the OS convention of the {@link
 * Platform} (Windows: {@code %SystemDrive%\Users\<user>}; Linux: the home field of {@code
 * /etc/passwd}, falling back to {@code /home/<user>}); when {@code runAsUser} is unset the current
 * user's home is inspected and the reason says so; the fingerprint is the streaming SHA-256 of
 * {@code .jrsks}; a found file that accounts other than its owner can read is named in {@code
 * exposure}; the keystore is never opened or decrypted, and no keystore password is read.
 */
public final class KeystoreInspector {

  public static final String KEYSTORE_FILE = ".jrsks";
  public static final String PROPERTIES_FILE = ".jrsksp";

  /**
   * Roots used to resolve home directories; {@link #system()} describes the real machine, tests
   * point every root at a temporary tree.
   */
  public record Homes(
      Path windowsUsersRoot,
      Path passwdFile,
      Path linuxHomeRoot,
      Path currentUserHome,
      Path windowsSystemRoot) {
    public Homes {
      Objects.requireNonNull(windowsUsersRoot, "windowsUsersRoot");
      Objects.requireNonNull(passwdFile, "passwdFile");
      Objects.requireNonNull(linuxHomeRoot, "linuxHomeRoot");
      Objects.requireNonNull(currentUserHome, "currentUserHome");
      Objects.requireNonNull(windowsSystemRoot, "windowsSystemRoot");
    }

    /** The Windows directory is taken to sit beside the Users directory. */
    public Homes(Path windowsUsersRoot, Path passwdFile, Path linuxHomeRoot, Path currentUserHome) {
      this(
          windowsUsersRoot,
          passwdFile,
          linuxHomeRoot,
          currentUserHome,
          windowsUsersRoot.toAbsolutePath().resolveSibling("Windows"));
    }

    public static Homes system() {
      String drive = System.getenv("SystemDrive");
      String usersRoot = (drive == null || drive.isBlank() ? "C:" : drive.strip()) + "\\Users";
      String systemRoot = System.getenv("SystemRoot");
      return new Homes(
          Path.of(usersRoot),
          Path.of("/etc/passwd"),
          Path.of("/home"),
          Path.of(System.getProperty("user.home", ".")),
          Path.of(systemRoot == null || systemRoot.isBlank() ? "C:\\Windows" : systemRoot));
    }
  }

  /** {@code buildomatic/keystore.init.properties}: where the installer put the keystore. */
  public static final String INIT_PROPERTIES = "keystore.init.properties";

  private final Platform platform;
  private final Config config;
  private final Homes homes;

  public KeystoreInspector(Platform platform, Config config) {
    this(platform, config, Homes.system());
  }

  public KeystoreInspector(Platform platform, Config config, Homes homes) {
    this.platform = Objects.requireNonNull(platform, "platform");
    this.config = Objects.requireNonNull(config, "config");
    this.homes = Objects.requireNonNull(homes, "homes");
  }

  public KeystoreInfo inspect() {
    Optional<KeystoreInfo> fromWebapp = webappInitProperties().flatMap(this::fromInitFile);
    if (fromWebapp.isPresent()) {
      return fromWebapp.get();
    }
    Optional<KeystoreInfo> fromInstall = buildomaticInitProperties().flatMap(this::fromInitFile);
    if (fromInstall.isPresent()) {
      return fromInstall.get();
    }
    Optional<String> user = config.server().runAsUser();
    Path home;
    String note;
    if (user.isPresent()) {
      Optional<Path> resolved = homeOf(user.get());
      if (resolved.isEmpty()) {
        return KeystoreInfo.absent(
            "home directory of server.runAsUser '"
                + user.get()
                + "' not found; check the account exists on this host or correct"
                + " server.runAsUser");
      }
      home = resolved.get();
      note = "resolved from server.runAsUser '" + user.get() + "' to " + home;
    } else {
      home = homes.currentUserHome();
      note = "server.runAsUser is not set; inspected the current user's home " + home;
    }
    Path keystore = home.resolve(KEYSTORE_FILE);
    Path properties = home.resolve(PROPERTIES_FILE);
    if (!Files.isRegularFile(keystore)) {
      return KeystoreInfo.absent(keystore + " not found (" + note + ")");
    }
    String fingerprint;
    try {
      fingerprint = platform.files().sha256(keystore);
    } catch (IOException e) {
      return KeystoreInfo.absent(
          "cannot read " + keystore + ": " + e.getMessage() + " (" + note + ")");
    }
    return found(keystore, properties, fingerprint, note);
  }

  /**
   * The file the running server reads at startup: {@code
   * <tomcat>/webapps/<webappName>/WEB-INF/classes/keystore.init.properties}. The Tomcat directory
   * is {@code server.tomcatDir} or the one detected under {@code server.installDir}; the webapp
   * name is {@code server.webappName}, else the layout's. Empty when none of that resolves or the
   * file is not there (a WAR deployed elsewhere, or a server older than the keystore).
   */
  private Optional<Path> webappInitProperties() {
    Optional<Path> install = config.server().installDir();
    Optional<TomcatLayout> layout = install.flatMap(platform::detectTomcat);
    Optional<Path> tomcat =
        config.server().tomcatDir().or(() -> layout.map(TomcatLayout::tomcatDir));
    Optional<String> webapp =
        config
            .server()
            .webappName()
            .map(Config.WebappName::yamlValue)
            .or(() -> layout.map(TomcatLayout::webappName));
    if (tomcat.isEmpty() || webapp.isEmpty()) {
      return Optional.empty();
    }
    Path file =
        tomcat
            .get()
            .resolve("webapps")
            .resolve(webapp.get())
            .resolve("WEB-INF")
            .resolve("classes")
            .resolve(INIT_PROPERTIES);
    return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
  }

  /**
   * Review finding 2.5: the installer records the keystore location in {@code
   * buildomatic/keystore.init.properties} ({@code ks}, {@code ksp}), which beats any guess from the
   * account name; the real 10.0.0 install on the development machine points both at the installing
   * user's profile. The buildomatic directory is the one {@link BuildomaticLocator#resolve} settles
   * on, so a tree on another volume or a share is read too (ADR-0013). Empty when no directory
   * resolves or the file is not there.
   */
  private Optional<Path> buildomaticInitProperties() {
    return new BuildomaticLocator(platform)
        .resolve(config)
        .located()
        .map(Buildomatic::dir)
        .map(dir -> dir.resolve(INIT_PROPERTIES))
        .filter(Files::isRegularFile);
  }

  /** Reads {@code ks}/{@code ksp} from an init file; empty when it names no location. */
  private Optional<KeystoreInfo> fromInitFile(Path file) {
    Properties props = new Properties();
    try (var in = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
      props.load(in);
    } catch (IOException e) {
      return Optional.empty();
    }
    String ks = props.getProperty("ks", "").strip();
    if (ks.isEmpty()) {
      return Optional.empty();
    }
    String ksp = props.getProperty("ksp", "").strip();
    Path keystore = Path.of(ks).resolve(KEYSTORE_FILE);
    Path properties = Path.of(ksp.isEmpty() ? ks : ksp).resolve(PROPERTIES_FILE);
    String note =
        "resolved from " + file + " (ks=" + ks + (ksp.isEmpty() ? "" : ", ksp=" + ksp) + ")";
    if (!Files.isRegularFile(keystore)) {
      return Optional.of(KeystoreInfo.absent(keystore + " not found (" + note + ")"));
    }
    try {
      return Optional.of(found(keystore, properties, platform.files().sha256(keystore), note));
    } catch (IOException e) {
      return Optional.of(
          KeystoreInfo.absent(
              "cannot read " + keystore + ": " + e.getMessage() + " (" + note + ")"));
    }
  }

  /** A found keystore, with the permission check on it and on its properties when present. */
  private KeystoreInfo found(Path keystore, Path properties, String fingerprint, String note) {
    Optional<Path> props =
        Files.isRegularFile(properties) ? Optional.of(properties) : Optional.empty();
    return new KeystoreInfo(
        true,
        Optional.of(keystore),
        props,
        Optional.of(fingerprint),
        Optional.of(note),
        exposure(keystore).or(() -> props.flatMap(this::exposure)));
  }

  /**
   * Keystore deck p.6: the files should be 600 or 640. Where the platform reports POSIX bits, a
   * file with any bit for "others" is named, since 640 (a group the server shares) is what the
   * vendor allows; elsewhere a file the platform does not consider owner-only is named. A
   * permission read that fails names nothing, since the caller already proved the file readable.
   */
  private Optional<String> exposure(Path file) {
    try {
      Optional<String> posix =
          platform.files().capturePermissions(file).entries().stream()
              .filter(e -> e.startsWith(POSIX_ENTRY))
              .map(e -> e.substring(POSIX_ENTRY.length()))
              .findFirst();
      boolean exposed =
          posix.isPresent()
              ? posix.get().length() >= 9 && !posix.get().substring(6, 9).equals("---")
              : !platform.files().isOwnerOnly(file);
      return exposed
          ? Optional.of(
              file
                  + " is readable by accounts other than its owner"
                  + posix.map(p -> " (" + p + ")").orElse(""))
          : Optional.empty();
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /** The permission entry {@code LinuxFileOps} writes: {@code posix:rw-r-----}. */
  private static final String POSIX_ENTRY = "posix:";

  /**
   * Home directory of an OS account by platform convention; a {@code DOMAIN\\user} or {@code
   * user@domain} spelling is reduced to the bare account name. The Windows service accounts
   * (SYSTEM, LocalService, NetworkService) have no directory under Users; their profiles live under
   * the Windows directory (review finding 2.5). Empty when no directory exists.
   */
  public Optional<Path> homeOf(String user) {
    String account = bareAccount(user);
    return switch (platform.os()) {
      case WINDOWS ->
          serviceProfile(account)
              .or(() -> Optional.of(homes.windowsUsersRoot().resolve(account)))
              .flatMap(KeystoreInspector::existingDir);
      case LINUX ->
          passwdHome(account).or(() -> existingDir(homes.linuxHomeRoot().resolve(account)));
    };
  }

  private Optional<Path> serviceProfile(String account) {
    String a = account.toLowerCase(Locale.ROOT).replace(" ", "");
    Path windows = homes.windowsSystemRoot();
    return switch (a) {
      case "system", "localsystem" ->
          Optional.of(windows.resolve("System32").resolve("config").resolve("systemprofile"));
      case "localservice" ->
          Optional.of(windows.resolve("ServiceProfiles").resolve("LocalService"));
      case "networkservice" ->
          Optional.of(windows.resolve("ServiceProfiles").resolve("NetworkService"));
      default -> Optional.empty();
    };
  }

  private Optional<Path> passwdHome(String account) {
    if (!Files.isRegularFile(homes.passwdFile())) {
      return Optional.empty();
    }
    try (Stream<String> lines = Files.lines(homes.passwdFile(), StandardCharsets.UTF_8)) {
      return lines
          .map(l -> l.split(":", -1))
          .filter(f -> f.length >= 6 && f[0].equals(account) && !f[5].isBlank())
          .map(f -> Path.of(f[5]))
          .findFirst()
          .flatMap(KeystoreInspector::existingDir);
    } catch (IOException | java.io.UncheckedIOException e) {
      return Optional.empty();
    }
  }

  private static Optional<Path> existingDir(Path p) {
    return Files.isDirectory(p) ? Optional.of(p) : Optional.empty();
  }

  static String bareAccount(String user) {
    String u = user.strip();
    int backslash = u.lastIndexOf('\\');
    if (backslash >= 0) {
      u = u.substring(backslash + 1);
    }
    int at = u.indexOf('@');
    if (at > 0) {
      u = u.substring(0, at);
    }
    return u;
  }
}
