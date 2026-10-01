package com.jaspersoft.jrsupgrade.ops.customizations;

import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.ClassFinding;
import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.ClassStatus;
import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.JakartaFinding;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.semver4j.Semver;

/**
 * Issue #5: which vendor types the site's own code builds on, whether the target still has them,
 * and which jars need a Jakarta EE recompile. Invariants: read-only, no class is loaded; a vendor
 * type is one under {@code com.jaspersoft.} or {@code net.sf.jasperreports.}; a referenced vendor
 * type is {@link ClassStatus#PRESENT} when a target jar holds it, {@link ClassStatus#MOVED} when it
 * is gone but a target class of the same simple name exists, {@link ClassStatus#MISSING} otherwise,
 * naming the package it was last seen in; the {@code javax.*} count is taken only for a target of
 * 10.0 or later (Jakarta EE 10) and covers {@link #JAVAX}; a jar that cannot be read is reported as
 * one {@link ClassStatus#UNREADABLE} finding, never skipped silently.
 */
final class VendorClassCheck {

  static final List<String> VENDOR = List.of("com.jaspersoft.", "net.sf.jasperreports.");

  /** The javax packages Jakarta EE 10 renamed that a JasperReports Server customization uses. */
  static final List<String> JAVAX =
      List.of("javax.servlet", "javax.ws.rs", "javax.persistence", "javax.annotation");

  static final Semver JAKARTA_FROM = new Semver("10.0.0");

  private VendorClassCheck() {}

  /** Findings and the jars to recompile. */
  record Result(List<ClassFinding> classes, List<JakartaFinding> jakarta) {}

  /**
   * Checks the code in {@code what}: a display name (a jar's file name, or {@code WEB-INF/classes})
   * mapped to the jars or single class files it stands for.
   */
  static Result check(Map<String, List<Path>> what, PackageIndex target, String targetVersion)
      throws IOException {
    boolean jakarta =
        Optional.ofNullable(Semver.coerce(targetVersion))
            .map(v -> v.isGreaterThanOrEqualTo(JAKARTA_FROM))
            .orElse(false);
    Map<String, List<String>> bySimpleName = null;
    List<ClassFinding> classes = new ArrayList<>();
    List<JakartaFinding> recompile = new ArrayList<>();
    for (Map.Entry<String, List<Path>> e : what.entrySet()) {
      Map<String, Usage> vendor = new TreeMap<>();
      Map<String, Integer> javax = new LinkedHashMap<>();
      try {
        forEachClass(
            e.getValue(),
            refs -> {
              note(vendor, refs.superName(), refs.name(), Relation.EXTENDS);
              refs.interfaces().forEach(i -> note(vendor, i, refs.name(), Relation.IMPLEMENTS));
              for (String r : refs.referenced()) {
                note(vendor, r, refs.name(), Relation.USES);
                for (String p : JAVAX) {
                  if (r.startsWith(p + ".") && !r.startsWith("javax.annotation.processing.")) {
                    javax.merge(p, 1, Integer::sum);
                  }
                }
              }
            });
      } catch (IOException ex) {
        classes.add(
            new ClassFinding(
                e.getKey(), "", "", ClassStatus.UNREADABLE, "cannot read: " + ex.getMessage()));
        continue;
      }
      for (Map.Entry<String, Usage> v : vendor.entrySet()) {
        String type = v.getKey();
        Usage u = v.getValue();
        String usedBy =
            u.relation.word
                + " by "
                + u.firstUser
                + (u.users.size() > 1 ? " and " + (u.users.size() - 1) + " more" : "");
        String jar = target.classes().get(type);
        if (jar != null) {
          classes.add(new ClassFinding(e.getKey(), type, usedBy, ClassStatus.PRESENT, "in " + jar));
          continue;
        }
        if (bySimpleName == null) {
          bySimpleName = new HashMap<>();
          for (String c : target.classes().keySet()) {
            bySimpleName.computeIfAbsent(simple(c), k -> new ArrayList<>()).add(c);
          }
        }
        List<String> same = bySimpleName.getOrDefault(simple(type), List.of());
        if (!same.isEmpty()) {
          List<String> shown = same.stream().sorted().limit(3).toList();
          classes.add(
              new ClassFinding(
                  e.getKey(),
                  type,
                  usedBy,
                  ClassStatus.MOVED,
                  "now "
                      + String.join(
                          ", ",
                          shown.stream().map(c -> c + " in " + target.classes().get(c)).toList())));
        } else {
          classes.add(
              new ClassFinding(
                  e.getKey(),
                  type,
                  usedBy,
                  ClassStatus.MISSING,
                  "not in the target; last seen in package " + packageOf(type)));
        }
      }
      if (jakarta && !javax.isEmpty()) {
        recompile.add(new JakartaFinding(e.getKey(), javax));
      }
    }
    return new Result(classes, recompile);
  }

  private enum Relation {
    EXTENDS("extended"),
    IMPLEMENTS("implemented"),
    USES("used");

    final String word;

    Relation(String word) {
      this.word = word;
    }
  }

  private static final class Usage {
    Relation relation;
    String firstUser;
    final java.util.Set<String> users = new java.util.TreeSet<>();

    Usage(Relation relation, String firstUser) {
      this.relation = relation;
      this.firstUser = firstUser;
    }
  }

  private static void note(Map<String, Usage> vendor, String type, String user, Relation r) {
    if (type.isEmpty() || VENDOR.stream().noneMatch(type::startsWith)) {
      return;
    }
    Usage u = vendor.computeIfAbsent(type, k -> new Usage(r, user));
    u.users.add(user);
    if (r.compareTo(u.relation) < 0) {
      u.relation = r;
      u.firstUser = user;
    }
  }

  @FunctionalInterface
  private interface ClassVisitor {
    void visit(ClassRefs refs) throws IOException;
  }

  /** Every class of each jar, and each single class file, in {@code sources}. */
  private static void forEachClass(List<Path> sources, ClassVisitor visitor) throws IOException {
    for (Path source : sources) {
      if (source.getFileName().toString().endsWith(".class")) {
        try (InputStream in = Files.newInputStream(source)) {
          visitor.visit(ClassRefs.read(in));
        }
        continue;
      }
      try (InputStream in = Files.newInputStream(source)) {
        PackageIndex.scanJar(
            source.getFileName().toString(),
            in,
            (name, bytes) -> visitor.visit(ClassRefs.read(bytes)));
      }
    }
  }

  private static String simple(String binary) {
    int dot = binary.lastIndexOf('.');
    return dot < 0 ? binary : binary.substring(dot + 1);
  }

  private static String packageOf(String binary) {
    int dot = binary.lastIndexOf('.');
    return dot < 0 ? "(default)" : binary.substring(0, dot);
  }
}
