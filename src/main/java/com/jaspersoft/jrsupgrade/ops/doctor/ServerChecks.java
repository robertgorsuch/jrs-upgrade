package com.jaspersoft.jrsupgrade.ops.doctor;

import com.jaspersoft.jrsupgrade.core.compat.CompatMatrix;
import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.platform.TomcatLayout;
import com.jaspersoft.jrsupgrade.core.secrets.Secret;
import com.jaspersoft.jrsupgrade.core.secrets.SecretException;
import com.jaspersoft.jrsupgrade.core.secrets.SecretRef;
import com.jaspersoft.jrsupgrade.jrs.api.Capability;
import com.jaspersoft.jrsupgrade.jrs.api.Credentials;
import com.jaspersoft.jrsupgrade.jrs.api.KeystoreInfo;
import com.jaspersoft.jrsupgrade.jrs.api.ServerIdentity;
import com.jaspersoft.jrsupgrade.jrs.api.Session;
import com.jaspersoft.jrsupgrade.ops.ReportItem;
import com.jaspersoft.jrsupgrade.ops.Services;
import com.jaspersoft.jrsupgrade.ops.TomcatJavaOpts;
import com.jaspersoft.jrsupgrade.ops.TomcatVersion;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The {@code doctor} checks that need a reachable server (spec §12.1, §5.7, §9.3). Invariants:
 * every method is read-only against the server; the login secret is resolved, handed to the adapter
 * and closed without appearing in any item; compat is judged only from the matrix, never from
 * version-string branching in this class.
 */
final class ServerChecks {

  static final String APP_SERVER = "tomcat";

  private ServerChecks() {}

  static ReportItem auth(Services s, ServerProbe.Connected c) {
    Config.Auth auth = s.config().server().auth();
    Optional<String> username = auth.username();
    Optional<SecretRef> ref = auth.passwordRef();
    if (username.isEmpty() || ref.isEmpty()) {
      return ReportItem.fail(
          "auth",
          "server.auth.username or server.auth.passwordRef is not configured",
          "set both in config.yaml (run jrs-upgrade init)");
    }
    // REST API reference p.24 (issue #113): basic authentication with non-ASCII credentials
    // "will always return an error"; the login form carries them
    boolean basic = auth.mode() == Config.AuthMode.BASIC;
    boolean nonAsciiBasic = false;
    try (Secret password = s.secrets().resolve(ref.get())) {
      nonAsciiBasic = basic && (hasNonAscii(username.get()) || hasNonAscii(password.chars()));
      Session session =
          c.adapter().login(new Credentials(username.get(), password, Optional.empty()));
      // issue #112: the REST reference documents pp as a URL parameter only; the header is
      // jrs-upgrade's own choice (ADR-0018), which a reader of the vendor docs should not go
      // looking for
      String tokenNote =
          auth.mode() == Config.AuthMode.TOKEN
                  && auth.tokenLocation() == Config.TokenLocation.HEADER
              ? "; the token travels in the pp header, jrs-upgrade's choice (ADR-0018), where the"
                  + " REST reference documents pp as a URL parameter only"
              : "";
      if (nonAsciiBasic) {
        return ReportItem.warn(
            "auth",
            "logged in as "
                + username.get()
                + " ("
                + session.mode()
                + ") with non-ASCII credentials, which the REST reference says basic"
                + " authentication always rejects (REST API reference p.24); this login happened"
                + " to work",
            FORM_REMEDIATION);
      }
      return ReportItem.pass(
          "auth", "logged in as " + username.get() + " (" + session.mode() + ")" + tokenNote);
    } catch (SecretException e) {
      return ReportItem.fail("auth", e.getMessage(), "fix " + ref.get().render());
    } catch (RuntimeException e) {
      if (nonAsciiBasic) {
        return ReportItem.fail(
            "auth",
            "login as "
                + username.get()
                + " failed: "
                + e.getMessage()
                + "; the credentials contain non-ASCII characters, which basic authentication"
                + " always rejects (REST API reference p.24)",
            FORM_REMEDIATION + ", then check the credentials behind " + ref.get().render());
      }
      return ReportItem.fail(
          "auth",
          "login as " + username.get() + " failed: " + e.getMessage(),
          "check the credentials behind " + ref.get().render() + " and server.auth.mode");
    }
  }

  static final String FORM_REMEDIATION =
      "set server.auth.mode to form (jrs-upgrade config set server.auth.mode form) so the login form"
          + " carries the credentials";

  static boolean hasNonAscii(CharSequence text) {
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) > 0x7F) {
        return true;
      }
    }
    return false;
  }

  static boolean hasNonAscii(char[] chars) {
    for (char c : chars) {
      if (c > 0x7F) {
        return true;
      }
    }
    return false;
  }

  static ReportItem identity(ServerProbe.Connected c) {
    ServerIdentity id = c.identity();
    return ReportItem.pass(
        "identity",
        id.version()
            + " "
            + id.edition()
            + " "
            + id.tenancy()
            + " build "
            + id.build()
            + " features "
            + new TreeSet<>(id.features()));
  }

  /** FAIL on an unsupported combination, or WARN when {@code allowUnsupported}. */
  static ReportItem compat(Services s, ServerProbe.Connected c, boolean allowUnsupported) {
    CompatMatrix matrix = s.matrix();
    ServerIdentity id = c.identity();
    String edition = id.edition().name();
    Optional<String> database = s.config().database().type().map(Config.DatabaseType::yamlValue);
    Optional<CompatMatrix.Entry> entry = matrix.find(id.version());
    boolean supported =
        entry
            .filter(e -> e.editions().contains(edition))
            .filter(e -> e.appServers().contains(APP_SERVER))
            .filter(e -> database.map(d -> e.databases().contains(d)).orElse(true))
            .isPresent();
    String combo =
        id.version()
            + " "
            + edition
            + " on "
            + APP_SERVER
            + database.map(d -> " with " + d).orElse("");
    if (supported) {
      return ReportItem.pass(DoctorReport.COMPAT, combo + " (" + entry.get().label() + ")");
    }
    String detail =
        entry.isEmpty()
            ? combo + ": version not in the compatibility matrix"
            : combo + ": combination not listed under " + entry.get().label();
    if (allowUnsupported) {
      return ReportItem.warn(
          DoctorReport.COMPAT,
          detail + "; continuing because --allow-unsupported was given",
          "expect no support for this combination");
    }
    return ReportItem.fail(
        DoctorReport.COMPAT,
        detail,
        "use a supported JasperReports Server version or pass --allow-unsupported (audited)");
  }

  static ReportItem capabilities(Services s, ServerProbe.Connected c) {
    ServerIdentity id = c.identity();
    Set<String> probed =
        c.adapter().capabilities().stream()
            // CLUSTERING is the licence's, not the release line's (issue #112): its own item
            .filter(cap -> cap != Capability.CLUSTERING)
            .map(Enum::name)
            .collect(Collectors.toCollection(TreeSet::new));
    if (s.matrix().find(id.version()).isEmpty()) {
      return ReportItem.skip(
          "capabilities",
          "probed " + probed + "; no matrix entry for " + id.version() + " to compare with",
          "fix the compat check first");
    }
    Set<String> expected =
        new TreeSet<>(s.matrix().expectedCapabilities(id.version(), id.edition().name()));
    Set<String> missing = new TreeSet<>(expected);
    missing.removeAll(probed);
    Set<String> extra = new TreeSet<>(probed);
    extra.removeAll(expected);
    if (missing.isEmpty() && extra.isEmpty()) {
      return ReportItem.pass("capabilities", "probed " + probed + " as expected");
    }
    return ReportItem.warn(
        "capabilities",
        "probed " + probed + "; missing " + missing + "; unexpected " + extra,
        "the server may be partially configured (REST login, keystore, organizations); check its"
            + " configuration or the matrix entry");
  }

  /**
   * Review §3.2 (issue #112): the licence's clustering flag. A WARN, not a FAIL, and on purpose
   * also on a single-node commercial server whose licence merely permits clustering (the vendor's
   * own bundled installer ships {@code cl: true} and {@code org.quartz.jobStore.isClustered=true}
   * on one node), because jrs-upgrade cannot tell one node from many and the cost of the warning is
   * one line.
   */
  static ReportItem cluster(ServerProbe.Connected c) {
    boolean clustered = c.adapter().capabilities().contains(Capability.CLUSTERING);
    if (!clustered) {
      return ReportItem.pass(
          "cluster", "licence without clustering (licenseFeatures cl is false or absent)");
    }
    return ReportItem.warn(
        "cluster",
        "the licence includes clustering (licenseFeatures cl=true); jrs-upgrade changes this node only",
        "if this server is one node of a cluster, apply every hotfix and upgrade on each node"
            + " before the load balancer sends it traffic, and keep .jrsks and .jrsksp identical"
            + " across nodes; a single-node server can ignore this");
  }

  static ReportItem keystore(Services s, ServerProbe.Connected c) {
    ServerIdentity id = c.identity();
    boolean expected =
        c.adapter().capabilities().contains(Capability.KEYSTORE_ENCRYPTION)
            || s.matrix()
                .expectedCapabilities(id.version(), id.edition().name())
                .contains(Capability.KEYSTORE_ENCRYPTION.name());
    KeystoreInfo info = c.adapter().keystore();
    if (info.present()) {
      String where =
          info.keystoreFile().map(Path::toString).orElse("keystore")
              + info.fingerprint().map(f -> " sha256 " + shortHash(f)).orElse("");
      if (info.exposure().isPresent()) {
        // keystore deck p.6: the keystore and its properties should be 600 (or 640 for a group
        // the server shares); anyone who can read both can decrypt every stored secret
        return ReportItem.warn(
            "keystore",
            where + "; " + info.exposure().get(),
            "restrict .jrsks and .jrsksp to the account that runs the server (chmod 600, or 640"
                + " for its group; on Windows remove the inherited ACL entries)");
      }
      return ReportItem.pass("keystore", where);
    }
    String reason = info.reason().orElse("keystore not found");
    if (expected) {
      return ReportItem.fail(
          "keystore",
          reason,
          "the server names its keystore in WEB-INF/classes/keystore.init.properties (ks, ksp);"
              + " make that location readable to jrs-upgrade, or set server.runAsUser to the account"
              + " that runs Tomcat so its ~/.jrsks and ~/.jrsksp are inspected");
    }
    return ReportItem.pass("keystore", "not applicable: " + reason);
  }

  static ReportItem vendorJava(Services s, ServerProbe.Connected c) {
    Optional<Path> javaHome = s.config().vendor().javaHome();
    Optional<CompatMatrix.Entry> entry = s.matrix().find(c.identity().version());
    if (entry.isEmpty()) {
      return ReportItem.skip(
          "vendor-java",
          "no matrix entry for " + c.identity().version() + "; required Java unknown",
          "fix the compat check first");
    }
    // review §1.2: the platform sheets list more than one JDK for most release lines
    Set<Integer> allowed = entry.get().javaForBuildomatic();
    String need = CompatMatrix.describeJava(allowed);
    if (javaHome.isEmpty()) {
      return ReportItem.skip(
          "vendor-java",
          "vendor.javaHome is not configured",
          "set vendor.javaHome to a "
              + need
              + " JDK for buildomatic (needed by upgrade"
              + " and vendor export/import)");
    }
    JavaVersion.Probe probe = JavaVersion.probe(s.platform().processes(), javaHome.get());
    if (probe.feature().isEmpty()) {
      return ReportItem.fail(
          "vendor-java", probe.detail(), "point vendor.javaHome at a working " + need + " JDK");
    }
    int found = probe.feature().get();
    if (!allowed.contains(found)) {
      return ReportItem.fail(
          "vendor-java",
          javaHome.get()
              + " is Java "
              + found
              + "; JRS "
              + c.identity().version()
              + " needs "
              + need,
          "set vendor.javaHome to a " + need + " JDK");
    }
    return ReportItem.pass(
        "vendor-java",
        javaHome.get()
            + " is Java "
            + found
            + (allowed.size() == 1 ? " as required" : ", one of " + need));
  }

  /**
   * Review §2.1: the running Tomcat against what the platform sheet certifies for the running
   * server (10.0 moved to Jakarta EE and runs only on Tomcat 10.1.24+ or 11.0.11+).
   */
  static ReportItem tomcat(Services s, ServerProbe.Connected c, Optional<TomcatLayout> layout) {
    Optional<Path> tomcatDir =
        s.config().server().tomcatDir().or(() -> layout.map(TomcatLayout::tomcatDir));
    if (tomcatDir.isEmpty()) {
      return ReportItem.skip(
          "tomcat", "no Tomcat directory is configured or detected", "run jrs-upgrade init");
    }
    Optional<CompatMatrix.Entry> entry = s.matrix().find(c.identity().version());
    if (entry.isEmpty()) {
      return ReportItem.skip(
          "tomcat",
          "no matrix entry for " + c.identity().version() + "; certified Tomcat unknown",
          "fix the compat check first");
    }
    String ranges =
        entry.get().tomcat().isEmpty() ? "any" : String.join(" or ", entry.get().tomcat());
    Optional<String> version = TomcatVersion.detect(tomcatDir.get());
    if (version.isEmpty()) {
      return ReportItem.skip(
          "tomcat",
          "no lib/catalina.jar or RELEASE-NOTES under " + tomcatDir.get() + "; version unknown",
          "check server.tomcatDir; JRS " + c.identity().version() + " needs Tomcat " + ranges);
    }
    if (!s.matrix().tomcatSupported(c.identity().version(), version.get())) {
      return ReportItem.fail(
          "tomcat",
          "Tomcat "
              + version.get()
              + " at "
              + tomcatDir.get()
              + " is not certified for JRS "
              + c.identity().version()
              + " (needs "
              + ranges
              + ")",
          "run the server on a certified Tomcat; an upgrade to a new generation takes"
              + " --tomcat-dir");
    }
    String certified =
        "Tomcat "
            + version.get()
            + " at "
            + tomcatDir.get()
            + ", certified for JRS "
            + c.identity().version();
    // installation guide 10.1 pp.84-86 (issue #109): advice, since the vendor's own bundled
    // installer starts a Tomcat 10.1 on Java 17 without the options
    Optional<Path> setenv = TomcatJavaOpts.missingAddOpens(tomcatDir.get(), s.platform().os());
    if (setenv.isPresent()) {
      return ReportItem.warn(
          "tomcat",
          certified + "; " + setenv.get() + " carries no --add-opens",
          "add the --add-opens java.base/... options the "
              + TomcatJavaOpts.GUIDE
              + " lists for Java 17 and 21 to JAVA_OPTS in "
              + setenv.get()
              + " if the server needs them (the bundled installer starts without them)");
    }
    return ReportItem.pass("tomcat", certified);
  }

  private static String shortHash(String hash) {
    String h = hash.toLowerCase(Locale.ROOT);
    return h.length() > 12 ? h.substring(0, 12) : h;
  }
}
