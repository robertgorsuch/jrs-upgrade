package com.jaspersoft.jrsupgrade.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0014: the ports a Tomcat binds by configuration decide whether a JVM whose command line
 * cannot be read might be that Tomcat, so they must include the shutdown port and every live
 * connector, and never a commented-out sample.
 */
class ServerXmlPortsTest {

  private static final String SERVER_XML =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <Server port="8006" shutdown="SHUTDOWN">
        <Listener className="org.apache.catalina.startup.VersionLoggerListener"/>
        <Service name="Catalina">
          <!-- <Connector port="9999" protocol="HTTP/1.1"/> -->
          <Connector port="8082" protocol="HTTP/1.1"
                     connectionTimeout="20000" redirectPort="8443"/>
          <Connector port="8009" protocol="AJP/1.3" secretRequired="false"/>
          <Connector port="8443" protocol="org.apache.coyote.http11.Http11NioProtocol"
                     SSLEnabled="true"/>
        </Service>
      </Server>
      """;

  @Test
  void should_list_the_shutdown_port_and_every_live_connector(@TempDir Path dir)
      throws IOException {
    Path xml = dir.resolve("server.xml");
    Files.writeString(xml, SERVER_XML, StandardCharsets.UTF_8);

    assertThat(ServerXml.ports(xml)).containsExactlyInAnyOrder(8006, 8082, 8009, 8443);
    assertThat(ServerXml.httpPort(xml)).contains(8082);
  }

  @Test
  void should_leave_out_a_disabled_shutdown_port(@TempDir Path dir) throws IOException {
    Path xml = dir.resolve("server.xml");
    Files.writeString(
        xml,
        "<Server port=\"-1\" shutdown=\"SHUTDOWN\"><Service name=\"Catalina\">"
            + "<Connector port=\"8080\" protocol=\"HTTP/1.1\"/></Service></Server>",
        StandardCharsets.UTF_8);

    assertThat(ServerXml.ports(xml)).containsExactly(8080);
  }

  @Test
  void should_find_the_server_xml_of_an_install_dir_or_of_the_tomcat_dir_itself(
      @TempDir Path install) throws IOException {
    Path conf = Files.createDirectories(install.resolve("apache-tomcat").resolve("conf"));
    Files.writeString(conf.resolve("server.xml"), SERVER_XML, StandardCharsets.UTF_8);

    assertThat(ServerXml.portsUnder(install)).contains(8006, 8082);
    assertThat(ServerXml.portsUnder(install.resolve("apache-tomcat"))).contains(8006, 8082);
    assertThat(ServerXml.portsUnder(install.resolve("missing"))).isEmpty();
  }
}
