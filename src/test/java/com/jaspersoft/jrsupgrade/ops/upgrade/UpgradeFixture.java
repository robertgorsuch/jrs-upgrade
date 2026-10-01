package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.engine.CancellationToken;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.RunOptions;
import com.jaspersoft.jrsupgrade.core.engine.RunOutcome;
import com.jaspersoft.jrsupgrade.core.engine.Runner;
import com.jaspersoft.jrsupgrade.core.engine.Sleeper;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.event.Event;
import com.jaspersoft.jrsupgrade.core.platform.DefaultProcessRunner;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.platform.Platforms;
import com.jaspersoft.jrsupgrade.core.snapshot.SnapshotStore;
import com.jaspersoft.jrsupgrade.core.state.StateStore;
import com.jaspersoft.jrsupgrade.jrs.api.KeystoreInfo;
import com.jaspersoft.jrsupgrade.jrs.vendor.VendorTools;
import com.jaspersoft.jrsupgrade.ops.FakePlatform;
import com.jaspersoft.jrsupgrade.ops.FakeServices;
import com.jaspersoft.jrsupgrade.ops.Services;
import com.jaspersoft.jrsupgrade.ops.db.FakeJdbcConnector;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A fake JRS 8.2.0 installation, a fake 9.0.0 target package whose {@code js-ant} really runs
 * (through {@link DefaultProcessRunner}) and copies {@code webapp-new/} over the webapp, a fake
 * keystore, and a {@link FakeServices} whose scripted process runner answers the vendor Java probe.
 * The host OS decides between {@code .bat} and {@code .sh} scripts; both are written.
 */
public final class UpgradeFixture implements AutoCloseable {

  public static final String OLD_VERSION = "8.2.0";
  public static final String NEW_VERSION = "9.0.0";
  public static final String OLD_SCRIPT = "console.log('old');\n";
  public static final String NEW_SCRIPT = "console.log('new');\n";

  /** Marker file in the package: the fake js-ant copies the new webapp, then exits 3. */
  static final String FAIL_AFTER_COPY = "fail-after-copy";

  /** Marker file in the package: the fake vendor validation ({@code test}) reports a failure. */
  static final String FAIL_TEST = "fail-test";

  static final String CREATE_KEYSTORE = "create-keystore";

  /** What buildomatic's setup.xml prints before create-ks when it found no keystore. */
  public static final String KEYSTORE_WARNING =
      "WARNING: A new encryption key and a new keystore are about to be created.";

  public final FakeServices fake;
  public Services services;
  public final Path root;
  public final Path installDir;
  public final Path tomcatDir;
  public final Path webappDir;
  public final Path packageDir;

  /** The home the vendor scripts would look for the licence in (issue #108). */
  public final Path userHome;

  public final Path javaHome;
  public final Path keystoreDir;
  public final Path vendorLog;

  /**
   * An unpacked second Tomcat (certified for the fixture's 9.0.0 target) for {@code --tomcat-dir}.
   */
  public final Path newTomcatDir;

  /** What the repository-cache step sends; the connector never touches a real database. */
  public final FakeJdbcConnector jdbc = new FakeJdbcConnector();

  public final List<Event> events = new ArrayList<>();
  public final Platform.OsFamily os;
  public String javaBanner = "openjdk version \"17.0.2\" 2022-01-18";

  private UpgradeFixture(Path root, boolean withDatabase, boolean manualService)
      throws IOException {
    this.root = root;
    this.os = Platforms.osFamily(System.getProperty("os.name", "")).orElseThrow();
    this.installDir = Files.createDirectories(root.resolve("jrs"));
    this.tomcatDir = installDir.resolve("apache-tomcat");
    this.webappDir = tomcatDir.resolve("webapps").resolve("jasperserver-pro");
    this.packageDir = Files.createDirectories(root.resolve("pkg-9.0.0"));
    this.userHome = Files.createDirectories(root.resolve("user-home"));
    this.javaHome = Files.createDirectories(root.resolve("jdk17"));
    this.keystoreDir = Files.createDirectories(root.resolve("jrs-home"));
    this.vendorLog = packageDir.resolve("js-ant.log");
    this.newTomcatDir = Files.createDirectories(root.resolve("tomcat-new"));
    Files.createDirectories(newTomcatDir.resolve("webapps"));
    tomcatVersion(newTomcatDir, "9.0.90");
    layout();
    targetPackage();
    Files.createDirectories(javaHome.resolve("bin"));
    write(javaHome.resolve("bin").resolve("java"), "");
    write(javaHome.resolve("bin").resolve("java.exe"), "");
    write(keystoreDir.resolve(".jrsks"), "keystore-bytes");
    write(keystoreDir.resolve(".jrsksp"), "keystore-properties");
    this.fake = FakeServices.in(root.resolve("home"), os);
    fake.platform.realFiles = true;
    for (String exe : List.of("java", "java.exe")) {
      fake.platform.on(
          List.of(javaHome.resolve("bin").resolve(exe).toString(), "-version"),
          new FakePlatform.Response(0, List.of(javaBanner)));
    }
    fake.adapter.keystore =
        new KeystoreInfo(
            true,
            Optional.of(keystoreDir.resolve(".jrsks")),
            Optional.of(keystoreDir.resolve(".jrsksp")),
            Optional.of("abc"),
            Optional.empty());
    fake.yaml(
        """
        server:
          baseUrl: http://localhost:8080/jasperserver-pro
          webappName: jasperserver-pro
          installDir: %s
          tomcatDir: %s
          auth:
            mode: basic
            username: jasperadmin
            passwordRef: env:JRS_PASSWORD
        service:
          kind: %s
          name: jasperreports
          stopTimeoutSeconds: 30
        vendor:
          javaHome: %s
        network:
          mode: public
        """
                .formatted(
                    slashes(installDir),
                    slashes(tomcatDir),
                    manualService ? "manual" : "systemd",
                    slashes(javaHome))
            + (withDatabase ? databaseYaml(root) : ""));
    if (withDatabase) {
      Files.createDirectories(root.resolve("drivers"));
    }
    this.services = fake.build();
  }

  public static UpgradeFixture create(Path root) throws IOException {
    return new UpgradeFixture(root, false, false);
  }

  /**
   * As {@link #create}, with {@code service.kind: manual}, the only kind an upgrade with {@code
   * --tomcat-dir} accepts (ADR-0026); the fake controller still answers stop and start.
   */
  public static UpgradeFixture createWithManualService(Path root) throws IOException {
    return new UpgradeFixture(root, false, true);
  }

  /** Writes the Tomcat {@code RELEASE-NOTES} line jrs-upgrade reads the version from. */
  public static void tomcatVersion(Path tomcatDir, String version) throws IOException {
    write(
        tomcatDir.resolve("RELEASE-NOTES"),
        "================================\nApache Tomcat Version " + version + "\n");
  }

  /**
   * A database section that matches the fixture's default_master.properties and names no password
   * reference, so the doctor's database item is SKIP rather than a connection attempt, while {@code
   * JdbcSettings.from} still resolves and the JDBC steps run against {@link #jdbc}.
   */
  static String databaseYaml(Path root) {
    return """
        database:
          type: postgresql
          url: jdbc:postgresql://localhost:5432/jasperserver
          username: jasperdb
          driverDir: %s
        """
        .formatted(slashes(root.resolve("drivers")));
  }

  /** As {@link #create}, with a database section so JDBC steps run against {@link #jdbc}. */
  public static UpgradeFixture createWithDatabase(Path root) throws IOException {
    return new UpgradeFixture(root, true, false);
  }

  /**
   * Makes the fake vendor upgrade fail part-way: it still copies the new webapp over the old one,
   * then exits 3, the shape of a migration that dies after touching the files.
   */
  public void failVendorScriptAfterCopy() throws IOException {
    write(packageDir.resolve(FAIL_AFTER_COPY), "");
  }

  public void failVendorTest() throws IOException {
    write(packageDir.resolve(FAIL_TEST), "");
  }

  /**
   * Makes the fake vendor upgrade announce a new keystore the way setup.xml does when it finds
   * none, and still exit 0 (review §1.4).
   */
  public void vendorScriptCreatesKeystore() throws IOException {
    write(packageDir.resolve(CREATE_KEYSTORE), "");
  }

  /** A JasperReports Server export archive (index.xml plus a resource) outside the home. */
  public Path fakeExport(String name) throws IOException {
    Path zip = Files.createDirectories(root.resolve("exports")).resolve(name);
    try (java.io.OutputStream out = Files.newOutputStream(zip);
        java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(out)) {
      zos.putNextEntry(new java.util.zip.ZipEntry("index.xml"));
      zos.write("<export/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      zos.closeEntry();
      zos.putNextEntry(new java.util.zip.ZipEntry("resources/public/x.xml"));
      zos.write("<x/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      zos.closeEntry();
    }
    return zip;
  }

  /** As {@link #fakeExport(String)}, with a sidecar naming the exporting server and version. */
  public Path fakeExport(String name, String serverIdentity, String version) throws IOException {
    Path zip = fakeExport(name);
    com.jaspersoft.jrsupgrade.jrs.strategy.Sidecar.write(
        com.jaspersoft.jrsupgrade.jrs.strategy.Sidecar.pathFor(zip),
        new com.jaspersoft.jrsupgrade.jrs.strategy.Sidecar(
            java.time.Instant.EPOCH,
            serverIdentity,
            version,
            Optional.empty(),
            new com.jaspersoft.jrsupgrade.jrs.strategy.Sidecar.Flags(
                com.jaspersoft.jrsupgrade.jrs.api.ExportRequest.Scope.EVERYTHING,
                List.of(),
                true,
                true,
                true,
                true,
                true,
                true),
            services.platform().files().sha256(zip),
            com.jaspersoft.jrsupgrade.jrs.api.ExportImportStrategy.Kind.REST));
    return zip;
  }

  /** The target buildomatic's staged default_master.properties, every key including passwords. */
  public java.util.Map<String, String> stagedMasterProperties() throws IOException {
    java.util.Properties p = new java.util.Properties();
    try (java.io.InputStream in =
        Files.newInputStream(
            packageDir.resolve("buildomatic").resolve("default_master.properties"))) {
      p.load(in);
    }
    java.util.Map<String, String> out = new java.util.TreeMap<>();
    for (String k : p.stringPropertyNames()) {
      out.put(k, p.getProperty(k));
    }
    return out;
  }

  public String vendorLogText() throws IOException {
    return Files.exists(vendorLog) ? Files.readString(vendorLog) : "";
  }

  /** Replaces the installed buildomatic's {@code default_master.properties}. */
  public void installedMasterProperties(String content) throws IOException {
    write(installDir.resolve("buildomatic").resolve("default_master.properties"), content);
  }

  /** Re-scripts the vendor Java probe, e.g. to simulate a JDK 11. */
  public void javaVersion(String banner) {
    for (String exe : List.of("java", "java.exe")) {
      fake.platform.on(
          List.of(javaHome.resolve("bin").resolve(exe).toString(), "-version"),
          new FakePlatform.Response(0, List.of(banner)));
    }
  }

  private void layout() throws IOException {
    Files.createDirectories(webappDir.resolve("WEB-INF").resolve("lib"));
    Files.createDirectories(webappDir.resolve("WEB-INF").resolve("classes"));
    Files.createDirectories(webappDir.resolve("scripts"));
    Files.createDirectories(webappDir.resolve("META-INF"));
    Files.createDirectories(tomcatDir.resolve("bin"));
    Files.createDirectories(tomcatDir.resolve("conf").resolve("Catalina").resolve("localhost"));
    write(webappDir.resolve("WEB-INF").resolve("lib").resolve("jasperserver-8.2.0.jar"), "old jar");
    write(webappDir.resolve("scripts").resolve("app.js"), OLD_SCRIPT);
    write(webappDir.resolve("version.txt"), OLD_VERSION);
    write(webappDir.resolve("META-INF").resolve("context.xml"), "<Context/>");
    write(
        tomcatDir.resolve("conf").resolve("server.xml"),
        "<Server><Service><Connector port=\"8080\" protocol=\"HTTP/1.1\"/></Service></Server>");
    write(
        tomcatDir
            .resolve("conf")
            .resolve("Catalina")
            .resolve("localhost")
            .resolve("jasperserver-pro.xml"),
        "<Context docBase=\"jasperserver-pro\"/>");
    Path buildomatic = Files.createDirectories(installDir.resolve("buildomatic"));
    write(
        buildomatic.resolve("default_master.properties"),
        "appServerType=tomcat\ndbType=postgresql\ndbHost=localhost\ndbPort=5432\ndbUsername=jasperdb\n"
            + "dbPassword=TopSecret\njs.dbName=jasperserver\n");
    exportScripts(buildomatic);
    installedVendorScripts(buildomatic);
    write(installDir.resolve("ctlscript.sh"), "#!/bin/sh\n");
    write(installDir.resolve("ctlscript.bat"), "@echo off\r\n");
    executable(buildomatic);
  }

  /**
   * The installed buildomatic's js-ant and js-import, which a database rollback runs (ADR-0029):
   * they log their arguments to the vendor log and say what the real ones say on success.
   */
  private void installedVendorScripts(Path buildomatic) throws IOException {
    String log = vendorLog.toString();
    write(
        buildomatic.resolve("js-ant.bat"),
        "@echo off\r\n"
            + "echo js-ant fake target=%1\r\n"
            + "echo %* >> \""
            + log
            + "\"\r\n"
            + "echo BUILD SUCCESSFUL\r\n"
            + "exit /b 0\r\n");
    write(
        buildomatic.resolve("js-ant.sh"),
        "#!/bin/sh\n"
            + "echo \"js-ant fake target=$1\"\n"
            + "echo \"$@\" >> \""
            + log
            + "\"\n"
            + "echo BUILD SUCCESSFUL\n"
            + "exit 0\n");
    importScript(buildomatic);
  }

  /** A js-import that logs its arguments to the vendor log and reports as the real one does. */
  private void importScript(Path buildomatic) throws IOException {
    String log = vendorLog.toString();
    write(
        buildomatic.resolve("js-import.bat"),
        "@echo off\r\n"
            + "echo js-import %* >> \""
            + log
            + "\"\r\n"
            + "echo Processing started\r\n"
            + "echo VALIDATION COMPLETED\r\n"
            + "echo Done\r\n"
            + "exit /b 0\r\n");
    write(
        buildomatic.resolve("js-import.sh"),
        "#!/bin/sh\n"
            + "echo \"js-import $@\" >> \""
            + log
            + "\"\n"
            + "echo Processing started\n"
            + "echo VALIDATION COMPLETED\n"
            + "echo Done\n"
            + "exit 0\n");
  }

  private void exportScripts(Path buildomatic) throws IOException {
    write(
        buildomatic.resolve("js-export.bat"),
        "@echo off\r\n"
            + "echo Processing started\r\n"
            + "echo fake export > \"%2\"\r\n"
            + "echo Done\r\n"
            + "exit /b 0\r\n");
    write(
        buildomatic.resolve("js-export.sh"),
        "#!/bin/sh\necho Processing started\necho fake export > \"$2\"\necho Done\nexit 0\n");
  }

  private void targetPackage() throws IOException {
    Path buildomatic = Files.createDirectories(packageDir.resolve("buildomatic"));
    Path webapp =
        Files.createDirectories(
            packageDir.resolve("jasperserver-pro").resolve("WEB-INF").resolve("lib"));
    write(webapp.resolve("some-lib.jar"), "lib");
    Path webappNew = Files.createDirectories(packageDir.resolve("webapp-new"));
    write(webappNew.resolve("version.txt"), NEW_VERSION);
    write(webappNew.resolve("scripts").resolve("app.js"), NEW_SCRIPT);
    write(webappNew.resolve("WEB-INF").resolve("lib").resolve("jasperserver-9.0.0.jar"), "new jar");
    String target = webappDir.toString();
    write(
        buildomatic.resolve("js-ant.bat"),
        "@echo off\r\n"
            + "echo js-ant fake target=%1 JAVA_HOME=%JAVA_HOME%\r\n"
            + "if \"%1\"==\"pre-upgrade-test-pro\" goto :validate\r\n"
            + "xcopy /E /Y /I /Q \"%~dp0..\\webapp-new\" \""
            + target
            + "\" >nul\r\n"
            + "if errorlevel 1 exit /b 1\r\n"
            + "if exist \"%~dp0..\\"
            + CREATE_KEYSTORE
            + "\" echo "
            + KEYSTORE_WARNING
            + "\r\n"
            + "if exist \"%~dp0..\\"
            + FAIL_AFTER_COPY
            + "\" (echo BUILD FAILED after copying the webapp & exit /b 3)\r\n"
            + "echo %* >> \"%~dp0..\\js-ant.log\"\r\n"
            + "exit /b 0\r\n"
            + ":validate\r\n"
            + "if exist \"%~dp0..\\"
            + FAIL_TEST
            + "\" (echo BUILD FAILED: cannot connect & exit /b 1)\r\n"
            + "echo BUILD SUCCESSFUL\r\n"
            + "exit /b 0\r\n");
    write(
        buildomatic.resolve("js-ant.sh"),
        "#!/bin/sh\n"
            + "echo \"js-ant fake target=$1 JAVA_HOME=$JAVA_HOME\"\n"
            + "if [ \"$1\" = \"pre-upgrade-test-pro\" ]; then\n"
            + "  if [ -f \"$(dirname \"$0\")/../"
            + FAIL_TEST
            + "\" ]; then echo \"BUILD FAILED: cannot connect\"; exit 1; fi\n"
            + "  echo BUILD SUCCESSFUL; exit 0\n"
            + "fi\n"
            + "cp -R \"$(dirname \"$0\")/../webapp-new/.\" \""
            + target
            + "/\" || exit 1\n"
            + "if [ -f \"$(dirname \"$0\")/../"
            + CREATE_KEYSTORE
            + "\" ]; then echo \""
            + KEYSTORE_WARNING
            + "\"; fi\n"
            + "if [ -f \"$(dirname \"$0\")/../"
            + FAIL_AFTER_COPY
            + "\" ]; then echo \"BUILD FAILED after copying the webapp\"; exit 3; fi\n"
            + "echo \"$@\" >> \"$(dirname \"$0\")/../js-ant.log\"\n"
            + "exit 0\n");
    exportScripts(buildomatic);
    // the target's js-import, which --include-events runs after the vendor run (issue #106): it
    // logs its arguments like the installed one and says what the real one says on success
    importScript(buildomatic);
    vendorWrappers(buildomatic);
    executable(buildomatic);
  }

  /**
   * The wrappers a real package ships, with the argument contract of the vendor's {@code
   * bin/do-js-upgrade} (ADR-0012): {@code js-upgrade-newdb} refuses to run without an existing
   * export file and hands {@code js-ant} the same target and properties the vendor script would;
   * {@code js-upgrade-samedb} takes no argument.
   */
  private static void vendorWrappers(Path buildomatic) throws IOException {
    write(
        buildomatic.resolve("js-upgrade-newdb.bat"),
        "@echo off\r\n"
            + "if \"%~1\"==\"test\" (call \"%~dp0js-ant.bat\" pre-upgrade-test-pro"
            + " -Dstrategy=standard & exit /b %errorlevel%)\r\n"
            + "if \"%~1\"==\"\" (echo JasperReports Server import file[path-to-file-and-filename]"
            + " expected as input & exit /b 1)\r\n"
            + "if not exist \"%~1\" (echo import file %~1 does not exist & exit /b 1)\r\n"
            + "call \"%~dp0js-ant.bat\" upgrade-minimal-pro -Dstrategy=standard"
            + " \"-DimportFile=%~1\"\r\n"
            + "exit /b %errorlevel%\r\n");
    write(
        buildomatic.resolve("js-upgrade-newdb.sh"),
        "#!/bin/sh\n"
            + "if [ \"$1\" = \"test\" ]; then exec \"$(dirname \"$0\")/js-ant.sh\""
            + " pre-upgrade-test-pro -Dstrategy=standard; fi\n"
            + "if [ -z \"$1\" ]; then echo \"JasperReports Server import file expected as"
            + " input\"; exit 1; fi\n"
            + "if [ ! -f \"$1\" ]; then echo \"import file $1 does not exist\"; exit 1; fi\n"
            + "exec \"$(dirname \"$0\")/js-ant.sh\" upgrade-minimal-pro -Dstrategy=standard"
            + " \"-DimportFile=$1\"\n");
    write(
        buildomatic.resolve("js-upgrade-samedb.bat"),
        "@echo off\r\n"
            + "if \"%~1\"==\"test\" (call \"%~dp0js-ant.bat\" pre-upgrade-test-pro"
            + " -Dstrategy=inDatabase & exit /b %errorlevel%)\r\n"
            + "call \"%~dp0js-ant.bat\" upgrade-minimal-pro -Dstrategy=inDatabase\r\n"
            + "exit /b %errorlevel%\r\n");
    write(
        buildomatic.resolve("js-upgrade-samedb.sh"),
        "#!/bin/sh\n"
            + "if [ \"$1\" = \"test\" ]; then exec \"$(dirname \"$0\")/js-ant.sh\""
            + " pre-upgrade-test-pro -Dstrategy=inDatabase; fi\n"
            + "exec \"$(dirname \"$0\")/js-ant.sh\" upgrade-minimal-pro -Dstrategy=inDatabase\n");
  }

  /**
   * The unpacked package of an intermediate {@code version} for a route (issue #1), named the way
   * the vendor names a bin distribution so it states its version. Its {@code js-ant} logs its
   * arguments to {@code js-ant.log} in the package and deploys nothing; a {@link #FAIL_AFTER_COPY}
   * file in the package makes it fail.
   */
  public Path transitPackage(String version) throws IOException {
    Path pkg =
        Files.createDirectories(root.resolve("jasperreports-server-pro-" + version + "-bin"));
    Path buildomatic = Files.createDirectories(pkg.resolve("buildomatic"));
    write(pkg.resolve("jasperserver-pro.war"), "war " + version);
    write(
        buildomatic.resolve("js-ant.bat"),
        "@echo off\r\n"
            + "echo js-ant transit "
            + version
            + " target=%1\r\n"
            + "if exist \"%~dp0..\\"
            + FAIL_AFTER_COPY
            + "\" (echo BUILD FAILED in the transit hop & exit /b 3)\r\n"
            + "echo %* >> \"%~dp0..\\js-ant.log\"\r\n"
            + "echo BUILD SUCCESSFUL\r\n"
            + "exit /b 0\r\n");
    write(
        buildomatic.resolve("js-ant.sh"),
        "#!/bin/sh\n"
            + "echo \"js-ant transit "
            + version
            + " target=$1\"\n"
            + "if [ -f \"$(dirname \"$0\")/../"
            + FAIL_AFTER_COPY
            + "\" ]; then echo \"BUILD FAILED in the transit hop\"; exit 3; fi\n"
            + "echo \"$@\" >> \"$(dirname \"$0\")/../js-ant.log\"\n"
            + "echo BUILD SUCCESSFUL\n"
            + "exit 0\n");
    exportScripts(buildomatic);
    importScript(buildomatic);
    vendorWrappers(buildomatic);
    executable(buildomatic);
    return pkg;
  }

  /** Turns the package into one that ships no {@code js-upgrade-*} wrapper, only {@code js-ant}. */
  public void removeVendorWrappers() throws IOException {
    for (String name :
        List.of(
            "js-upgrade-newdb.bat",
            "js-upgrade-newdb.sh",
            "js-upgrade-samedb.bat",
            "js-upgrade-samedb.sh")) {
      Files.deleteIfExists(packageDir.resolve("buildomatic").resolve(name));
    }
  }

  private static void executable(Path dir) throws IOException {
    try (var files = Files.list(dir)) {
      for (Path f : files.toList()) {
        if (f.getFileName().toString().endsWith(".sh")) {
          try {
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwxr-xr-x"));
          } catch (UnsupportedOperationException e) {
            // Windows
          }
        }
      }
    }
  }

  /**
   * Rebuilds the services with no admin password in the environment, as a host without JRS_PASSWORD
   * set: doctor then skips its login checks (field test 2, D1).
   */
  public void withoutAdminPassword() {
    fake.env.remove("JRS_PASSWORD");
    services = fake.build();
  }

  public DefaultUpgradeOperations ops() {
    return new DefaultUpgradeOperations(runtime(), userHome);
  }

  public UpgradeRuntime runtime() {
    return new UpgradeRuntime(
        services,
        snapshots(),
        s ->
            new VendorTools(
                new DefaultProcessRunner(),
                s.platform().files(),
                s.redactor(),
                Duration.ofMinutes(2)),
        jdbc,
        Sleeper.none());
  }

  public SnapshotStore snapshots() {
    return new SnapshotStore(fake.home, services.platform().files(), fake.clock);
  }

  public StateStore store() {
    return fake.stateStore();
  }

  public Context ctx(String runId) {
    return new Context(
        runId, fake.home, services.platform(), new CancellationToken(), java.util.Map.of());
  }

  public RunOutcome run(Plan plan, String runId, RunOptions options) {
    Runner runner = new Runner(store(), events::add, fake.clock, Sleeper.none());
    return runner.run(plan, ctx(runId), plan.fingerprint(), options);
  }

  public String sha(Path file) throws IOException {
    return services.platform().files().sha256(file);
  }

  public List<String> logs() {
    List<String> out = new ArrayList<>();
    for (Event e : events) {
      if (e instanceof Event.Log l) {
        out.add(l.message());
      }
    }
    return out;
  }

  public static List<String> ids(Plan plan) {
    return plan.steps().stream().map(Step::id).toList();
  }

  public static Step step(Plan plan, String id) {
    return plan.steps().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
  }

  public static void write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  public static String read(Path file) throws IOException {
    return Files.readString(file, StandardCharsets.UTF_8);
  }

  static String slashes(Path path) {
    return path.toAbsolutePath().toString().replace('\\', '/');
  }

  @Override
  public void close() {
    fake.close();
  }
}
