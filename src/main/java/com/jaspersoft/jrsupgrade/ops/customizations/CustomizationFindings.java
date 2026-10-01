package com.jaspersoft.jrsupgrade.ops.customizations;

import com.jaspersoft.jrsupgrade.core.compat.UpgradeRules;
import com.jaspersoft.jrsupgrade.core.state.Customization;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The customization findings of ADR-0003 for the registered customizations, in the words an upgrade
 * plan prints. Invariants: read-only; the target webapp is indexed only when a registered
 * customization needs it; a target that cannot be read is one line saying so, never a failure,
 * since the findings are advice; lines are ordered by issue and then by path.
 */
public final class CustomizationFindings {

  private CustomizationFindings() {}

  /**
   * One line per finding for {@code registered}, upgrading from {@code source} to {@code target}
   * whose webapp (directory or WAR) is {@code targetWebapp}, when the plan knows it.
   */
  public static List<String> forPlan(
      List<Customization> registered,
      Optional<Path> targetWebapp,
      String source,
      String target,
      UpgradeRules rules) {
    List<Path> jars = new ArrayList<>();
    for (Customization c : registered) {
      String path = c.path().toString().replace('\\', '/');
      if (path.contains("/" + PackageIndex.LIB)
          && path.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
          && Files.isRegularFile(c.path())) {
        jars.add(c.path());
      }
    }
    List<String> out = new ArrayList<>();
    if (jars.isEmpty() || targetWebapp.isEmpty()) {
      return out;
    }
    try {
      PackageIndex index = PackageIndex.read(targetWebapp.get());
      for (CustomizationOperations.JarFinding f :
          JarRetirement.judge(jars, index, rules.jarRules(source, target))) {
        out.add("jar " + f.jar() + ": " + f.verdict() + ", " + f.reason() + " (issue #4)");
      }
      java.util.Map<String, List<Path>> code = new java.util.LinkedHashMap<>();
      jars.forEach(j -> code.put(j.getFileName().toString(), List.of(j)));
      VendorClassCheck.Result classes = VendorClassCheck.check(code, index, target);
      for (CustomizationOperations.ClassFinding c : classes.classes()) {
        if (c.status() != CustomizationOperations.ClassStatus.PRESENT) {
          out.add(
              "jar "
                  + c.jar()
                  + ": vendor type "
                  + c.vendorType()
                  + " "
                  + c.status()
                  + " ("
                  + c.usedBy()
                  + "): "
                  + c.detail()
                  + " (issue #5)");
        }
      }
      for (CustomizationOperations.JakartaFinding j : classes.jakarta()) {
        out.add(
            "jar "
                + j.jar()
                + " refers to "
                + String.join(", ", new java.util.TreeSet<>(j.javaxReferences().keySet()))
                + ": recompile it for Jakarta EE 10 (jakarta.*) before it goes on "
                + target
                + " (issue #5)");
      }
    } catch (IOException e) {
      out.add(
          "the registered jars cannot be judged against "
              + targetWebapp.get()
              + ": "
              + e.getMessage());
    }
    return out;
  }
}
