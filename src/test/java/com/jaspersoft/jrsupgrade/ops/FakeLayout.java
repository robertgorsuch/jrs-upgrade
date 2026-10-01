package com.jaspersoft.jrsupgrade.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Creates JasperReports Server install trees on disk in the Linux or Windows bundle layout. */
public final class FakeLayout {

  public static final String DEFAULT_MASTER =
      """
      # buildomatic settings
      appServerType=tomcat
      dbType=postgresql
      dbHost=db.example.internal
      dbPort=5433
      dbUsername=jasperdb
      dbPassword=Sup3rSecret!
      js.dbName=jasperserver
      sysUsername=postgres
      sysPassword=AlsoSecret
      encrypt.keystore.password=Never
      """;

  private FakeLayout() {}

  /** Linux bundle: {@code apache-tomcat/}, {@code ctlscript.sh}, {@code buildomatic/*.sh}. */
  public static Path linux(Path installDir) throws IOException {
    return linux(installDir, "jasperserver-pro");
  }

  /** As {@link #linux(Path)} with the Community edition webapp {@code jasperserver}. */
  public static Path linuxCommunity(Path installDir) throws IOException {
    return linux(installDir, "jasperserver");
  }

  private static Path linux(Path installDir, String webapp) throws IOException {
    Path tomcat = tomcat(installDir.resolve("apache-tomcat"), webapp, 8081);
    Files.writeString(installDir.resolve("ctlscript.sh"), "#!/bin/sh\n", StandardCharsets.UTF_8);
    Files.writeString(
        tomcat.resolve("bin").resolve("catalina.sh"), "#!/bin/sh\n", StandardCharsets.UTF_8);
    buildomatic(installDir, ".sh");
    java(installDir, "java");
    return installDir;
  }

  /** Windows bundle: {@code tomcat\\}, {@code ctlscript.bat}, {@code buildomatic\\*.bat}. */
  public static Path windows(Path installDir) throws IOException {
    Path tomcat = tomcat(installDir.resolve("tomcat"), "jasperserver-pro", 8080);
    Files.writeString(installDir.resolve("ctlscript.bat"), "@echo off\r\n", StandardCharsets.UTF_8);
    Files.writeString(
        tomcat.resolve("bin").resolve("catalina.bat"), "@echo off\r\n", StandardCharsets.UTF_8);
    buildomatic(installDir, ".bat");
    java(installDir, "java.exe");
    return installDir;
  }

  private static Path tomcat(Path tomcat, String webapp, int port) throws IOException {
    Path webappDir = tomcat.resolve("webapps").resolve(webapp);
    Files.createDirectories(webappDir.resolve("WEB-INF").resolve("lib"));
    Files.createDirectories(webappDir.resolve("WEB-INF").resolve("classes"));
    Files.createDirectories(tomcat.resolve("bin"));
    Files.createDirectories(tomcat.resolve("conf"));
    Files.writeString(
        tomcat.resolve("conf").resolve("server.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <Server port="8005" shutdown="SHUTDOWN">
          <Service name="Catalina">
            <!-- <Connector port="9999" protocol="HTTP/1.1"/> -->
            <Connector port="%d" protocol="HTTP/1.1" connectionTimeout="20000" redirectPort="8443"/>
          </Service>
        </Server>
        """
            .formatted(port),
        StandardCharsets.UTF_8);
    return tomcat;
  }

  private static void buildomatic(Path installDir, String ext) throws IOException {
    Path buildomatic = Files.createDirectories(installDir.resolve("buildomatic"));
    for (String script : new String[] {"js-export", "js-import", "js-ant"}) {
      Files.writeString(buildomatic.resolve(script + ext), "echo\n", StandardCharsets.UTF_8);
    }
    Files.writeString(
        buildomatic.resolve("default_master.properties"), DEFAULT_MASTER, StandardCharsets.UTF_8);
    // the installer bundles Ant next to buildomatic, where the vendor setup script looks for it
    Path antBin = Files.createDirectories(installDir.resolve("apache-ant").resolve("bin"));
    Files.writeString(antBin.resolve("ant.bat"), "@echo off\r\n", StandardCharsets.UTF_8);
    Files.writeString(antBin.resolve("ant"), "#!/bin/sh\n", StandardCharsets.UTF_8);
  }

  private static void java(Path installDir, String exe) throws IOException {
    Path bin = Files.createDirectories(installDir.resolve("java").resolve("bin"));
    Files.writeString(bin.resolve(exe), "", StandardCharsets.UTF_8);
  }
}
