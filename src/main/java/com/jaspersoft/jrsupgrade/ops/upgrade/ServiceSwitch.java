package com.jaspersoft.jrsupgrade.ops.upgrade;

import com.jaspersoft.jrsupgrade.core.platform.Platform.OsFamily;
import com.jaspersoft.jrsupgrade.core.platform.ServiceConfig;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The operator's steps for moving a registered service from the old Tomcat to the one {@code
 * --tomcat-dir} names (ADR-0026 amendment, issue #109). Invariants: pure text, nothing is run;
 * every kind gets the vendor's or the platform's own procedure with the real paths and service name
 * filled in ({@code <name>} when none is configured); Windows paths are rendered with backslashes
 * whatever the build host. jrs-upgrade does not re-register services itself, so this text is what
 * the refusal, the plan summary and the operator guide all show.
 */
final class ServiceSwitch {

  static final String NAME_PLACEHOLDER = "<name>";

  private ServiceSwitch() {}

  static String reregistration(
      ServiceConfig.Kind kind,
      Optional<String> name,
      Optional<Path> scriptPath,
      Path oldTomcat,
      Path newTomcat,
      OsFamily os) {
    String service = name.orElse(NAME_PLACEHOLDER);
    return switch (kind) {
      case WINDOWS_SERVICE -> windowsService(service, oldTomcat, newTomcat);
      case SYSTEMD -> systemd(service, newTomcat);
      case CTLSCRIPT -> ctlscript(scriptPath, newTomcat, os);
      case CATALINA -> catalina(newTomcat, os);
      case MANUAL -> manual(oldTomcat, newTomcat, os);
    };
  }

  /**
   * Tomcat's own service.bat (Windows service HOW-TO): remove the old registration, install the
   * new.
   */
  private static String windowsService(String service, Path oldTomcat, Path newTomcat) {
    return "from an elevated prompt run "
        + windows(oldTomcat.resolve("bin").resolve("service.bat"))
        + " remove "
        + service
        + ", then "
        + windows(newTomcat.resolve("bin").resolve("service.bat"))
        + " install "
        + service
        + ", then give the new service the JVM options, log-on account and start type the old one"
        + " had (the new Tomcat's bin\\tomcat*w.exe //ES//"
        + service
        + ", or sc.exe config "
        + service
        + " start= auto)";
  }

  private static String systemd(String service, Path newTomcat) {
    return "edit the unit (systemctl cat "
        + service
        + " shows it): point Environment=CATALINA_HOME and CATALINA_BASE, ExecStart and ExecStop at "
        + newTomcat
        + ", then systemctl daemon-reload and systemctl restart "
        + service;
  }

  private static String ctlscript(Optional<Path> scriptPath, Path newTomcat, OsFamily os) {
    return scriptPath.map(p -> render(p, os)).orElse("ctlscript")
        + " starts only the Tomcat bundled under its own installation and cannot be pointed at "
        + render(newTomcat, os)
        + ": set service.kind catalina and service.scriptPath "
        + render(catalinaScript(newTomcat, os), os)
        + " (jrs-upgrade config set), or register the new Tomcat as a service of its own";
  }

  private static String catalina(Path newTomcat, OsFamily os) {
    return "run jrs-upgrade config set service.scriptPath "
        + render(catalinaScript(newTomcat, os), os)
        + " so that stop and start use the new Tomcat's script";
  }

  /**
   * With {@code manual} the kind that was set aside for the upgrade is unknown, so the recipe for
   * this operating system's registered kind is given with the name left as a placeholder.
   */
  private static String manual(Path oldTomcat, Path newTomcat, OsFamily os) {
    String startup = os == OsFamily.WINDOWS ? "startup.bat" : "startup.sh";
    String registered =
        os == OsFamily.WINDOWS
            ? windowsService(NAME_PLACEHOLDER, oldTomcat, newTomcat)
            : systemd(NAME_PLACEHOLDER, newTomcat);
    return "start the upgraded server with "
        + render(newTomcat.resolve("bin").resolve(startup), os)
        + " rather than the old Tomcat's; if a service was registered for "
        + render(oldTomcat, os)
        + " before service.kind was set to manual, re-register it for the new Tomcat ("
        + registered
        + ") and set service.kind back";
  }

  private static Path catalinaScript(Path tomcat, OsFamily os) {
    return tomcat.resolve("bin").resolve(os == OsFamily.WINDOWS ? "catalina.bat" : "catalina.sh");
  }

  private static String render(Path path, OsFamily os) {
    return os == OsFamily.WINDOWS ? windows(path) : path.toString();
  }

  private static String windows(Path path) {
    return path.toString().replace('/', '\\');
  }
}
