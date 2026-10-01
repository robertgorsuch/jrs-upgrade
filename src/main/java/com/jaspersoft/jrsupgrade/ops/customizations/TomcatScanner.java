package com.jaspersoft.jrsupgrade.ops.customizations;

import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.TomcatEntry;
import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.TomcatKind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lists the files outside the webapp that the upgrade guides tell a customer to carry over to the
 * new Tomcat (issue #117, vendor review §5.1): {@code bin/setenv.*}, {@code conf/server.xml},
 * {@code conf/Catalina/localhost/*.xml} and the {@code lib/*.jar} files Tomcat does not ship.
 * Invariants: there is no pristine Tomcat to compare with, so this is a list of what to carry over,
 * never a claim that a file was changed; a library is reported unless its name is one Tomcat
 * distributes itself; nothing is read but directory listings, and nothing is written.
 */
final class TomcatScanner {

  /** The jars a Tomcat 9, 10 and 11 distribution puts in {@code lib}, by name. */
  private static final Pattern SHIPPED_LIBRARY =
      Pattern.compile(
          "^(annotations-api|catalina|catalina-ant|catalina-ha|catalina-ssi|catalina-storeconfig"
              + "|catalina-tribes|ecj-[0-9].*|el-api|jakartaee-migration-.*|jasper|jasper-el"
              + "|jaspic-api|jsp-api|servlet-api|tomcat-.*|websocket-.*|jakarta\\..*)\\.jar$");

  private TomcatScanner() {}

  static boolean shippedByTomcat(String jarName) {
    return SHIPPED_LIBRARY.matcher(jarName.toLowerCase(Locale.ROOT)).matches();
  }

  static List<TomcatEntry> scan(Path tomcatDir, Set<Path> registered) throws IOException {
    List<TomcatEntry> out = new ArrayList<>();
    for (String name : List.of("setenv.sh", "setenv.bat")) {
      add(out, tomcatDir, "bin/" + name, TomcatKind.SETENV, registered);
    }
    add(out, tomcatDir, "conf/server.xml", TomcatKind.SERVER_XML, registered);
    for (Path file : files(tomcatDir.resolve("conf").resolve("Catalina").resolve("localhost"))) {
      String name = file.getFileName().toString();
      if (name.toLowerCase(Locale.ROOT).endsWith(".xml") && !WebappScanner.ignored(name)) {
        add(
            out,
            tomcatDir,
            "conf/Catalina/localhost/" + name,
            TomcatKind.CONTEXT_FRAGMENT,
            registered);
      }
    }
    for (Path file : files(tomcatDir.resolve("lib"))) {
      String name = file.getFileName().toString();
      if (name.toLowerCase(Locale.ROOT).endsWith(".jar") && !shippedByTomcat(name)) {
        add(out, tomcatDir, "lib/" + name, TomcatKind.LIBRARY, registered);
      }
    }
    return out;
  }

  private static void add(
      List<TomcatEntry> out, Path tomcatDir, String rel, TomcatKind kind, Set<Path> registered) {
    Path file = tomcatDir.resolve(rel).toAbsolutePath().normalize();
    if (Files.isRegularFile(file)) {
      out.add(new TomcatEntry(rel, kind, registered.contains(file)));
    }
  }

  private static List<Path> files(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> list = Files.list(dir)) {
      return list.filter(Files::isRegularFile)
          .sorted(Comparator.comparing(Path::toString))
          .toList();
    }
  }
}
