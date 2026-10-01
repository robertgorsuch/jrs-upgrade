package com.jaspersoft.jrsupgrade.ops.customizations;

import com.jaspersoft.jrsupgrade.core.compat.UpgradeRules;
import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.JarFinding;
import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.JarVerdict;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Issue #4: one verdict per jar under {@code WEB-INF/lib} the vendor copy lacks or the site
 * patched, judged against what the target package ships. Invariants: a named rule of the matrix
 * wins over coordinates; otherwise a jar the target ships with the same artifactId (and the same
 * groupId when both state one) is {@link JarVerdict#DROP} when the target's is as new or newer and
 * {@link JarVerdict#REVIEW} when it is older, a jar the target ships nothing like is {@link
 * JarVerdict#KEEP}, and a jar with no coordinates is {@link JarVerdict#UNRESOLVED}, never guessed;
 * read-only.
 */
final class JarRetirement {

  private JarRetirement() {}

  static List<JarFinding> judge(
      List<Path> jars, PackageIndex target, List<UpgradeRules.JarRule> rules) throws IOException {
    List<JarFinding> out = new ArrayList<>();
    for (Path jar : jars) {
      Optional<PackageIndex.Coordinates> ours;
      try (InputStream in = Files.newInputStream(jar)) {
        ours = PackageIndex.scanJar(jar.getFileName().toString(), in, (n, b) -> {});
      }
      out.add(judge(jar.getFileName().toString(), ours, target, rules));
    }
    return out;
  }

  static JarFinding judge(
      String name,
      Optional<PackageIndex.Coordinates> ours,
      PackageIndex target,
      List<UpgradeRules.JarRule> rules) {
    Optional<String> coordinates = ours.map(PackageIndex.Coordinates::toString);
    for (UpgradeRules.JarRule rule : rules) {
      if (rule.matches(name)) {
        return new JarFinding(
            name,
            coordinates,
            switch (rule.verdict()) {
              case DROP -> JarVerdict.DROP;
              case KEEP -> JarVerdict.KEEP;
              case REPLACE -> JarVerdict.REPLACE;
            },
            Optional.empty(),
            rule.note()
                + rule.replacement().map(r -> "; replace with " + r).orElse("")
                + " ("
                + rule.source()
                + ")");
      }
    }
    if (ours.isEmpty()) {
      return new JarFinding(
          name,
          coordinates,
          JarVerdict.UNRESOLVED,
          Optional.empty(),
          "no pom.properties inside and no version in the file name: check by hand what it is");
    }
    PackageIndex.Coordinates c = ours.get();
    Optional<PackageIndex.Jar> theirs =
        target.jars().stream()
            .filter(j -> j.coordinates().filter(t -> same(c, t)).isPresent())
            .findFirst();
    if (theirs.isEmpty()) {
      return new JarFinding(
          name,
          coordinates,
          JarVerdict.KEEP,
          Optional.empty(),
          "the target ships nothing with artifactId "
              + c.artifactId()
              + ": keep it as your own dependency");
    }
    PackageIndex.Coordinates t = theirs.get().coordinates().orElseThrow();
    int cmp = Versions.compare(t.version(), c.version());
    return cmp >= 0
        ? new JarFinding(
            name,
            coordinates,
            JarVerdict.DROP,
            Optional.of(theirs.get().name()),
            "the target ships "
                + t.artifactId()
                + " "
                + t.version()
                + (cmp == 0 ? ", the same version" : ", newer than " + c.version())
                + ": drop yours")
        : new JarFinding(
            name,
            coordinates,
            JarVerdict.REVIEW,
            Optional.of(theirs.get().name()),
            "the target ships "
                + t.artifactId()
                + " "
                + t.version()
                + ", older than yours ("
                + c.version()
                + "): keeping yours puts two versions on the class path; check why it was patched");
  }

  private static boolean same(PackageIndex.Coordinates a, PackageIndex.Coordinates b) {
    if (!a.artifactId().equalsIgnoreCase(b.artifactId())) {
      return false;
    }
    return a.groupId().isEmpty() || b.groupId().isEmpty() || a.groupId().equals(b.groupId());
  }
}
