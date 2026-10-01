package com.jaspersoft.jrsupgrade.core.platform;

import java.nio.file.Path;
import java.util.Optional;

/**
 * What {@code detectTomcat} finds under a JRS install dir. Invariant: {@code webappDir} is the
 * deployed application ({@code webapps/jasperserver} or {@code webapps/jasperserver-pro}), so
 * {@code webappDir.resolve("WEB-INF/lib")} is always the jar directory hotfixes touch.
 */
public record TomcatLayout(
    Path installDir,
    Path tomcatDir,
    Path webappDir,
    String webappName,
    Optional<Path> buildomaticDir,
    Optional<Path> bundledJavaHome,
    Optional<Integer> httpPort) {

  public Path webInfLib() {
    return webappDir.resolve("WEB-INF").resolve("lib");
  }

  public Path webInfClasses() {
    return webappDir.resolve("WEB-INF").resolve("classes");
  }

  /** True for paths whose change requires a service stop (spec §5.3). */
  public boolean requiresServiceStop(Path file) {
    Path abs = file.toAbsolutePath().normalize();
    return abs.startsWith(webInfLib().toAbsolutePath().normalize())
        || abs.startsWith(webInfClasses().toAbsolutePath().normalize());
  }
}
