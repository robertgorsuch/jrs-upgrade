package com.jaspersoft.jrsupgrade.ops.customizations;

import static com.jaspersoft.jrsupgrade.ops.customizations.TestArchives.classFile;
import static com.jaspersoft.jrsupgrade.ops.customizations.TestArchives.entries;
import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.ClassFinding;
import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.ClassStatus;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Issue #5: NGRA's pre-authentication filter jar against a 10.1 package. */
class VendorClassCheckTest {

  static final String BASE =
      "com.jaspersoft.jasperserver.api.security.externalAuth.preauth.BasePreAuthenticatedProcessingFilter";
  static final String OLD_PROCESSOR =
      "com.jaspersoft.jasperserver.api.security.externalAuth.processors.AbstractExternalUserProcessor";
  static final String NEW_PROCESSOR =
      "com.jaspersoft.jasperserver.multipleTenancy.processors.AbstractExternalUserProcessor";
  static final String AUDIT = "com.jaspersoft.jasperserver.api.logging.audit.AuditService";
  static final String FILTER = "com.ngra.security.AuthenticatedProcessingFilter";

  @TempDir Path tmp;

  /** A 10.1-shaped WAR holding the base class and the processor under its new package. */
  static Path targetWar(Path dir) throws Exception {
    return TestArchives.write(
        dir.resolve("jasperserver-pro.war"),
        TestArchives.zip(
            entries(
                "WEB-INF/lib/jasperserver-api-externalAuth-impl-10.1.0.jar",
                TestArchives.classJar(
                    Map.of(
                        BASE,
                        classFile(BASE, "java.lang.Object", List.of(), List.of(), List.of()),
                        NEW_PROCESSOR,
                        classFile(
                            NEW_PROCESSOR,
                            "java.lang.Object",
                            List.of(),
                            List.of(),
                            List.of()))))));
  }

  /** NGRA's jar: extends the base filter, uses the old processor and the audit service. */
  static byte[] ngraJar() throws Exception {
    return TestArchives.classJar(
        Map.of(
            FILTER,
            classFile(
                FILTER,
                BASE,
                List.of(),
                List.of(OLD_PROCESSOR, AUDIT, "javax.ws.rs.core.Response"),
                List.of("(Ljavax/servlet/http/HttpServletRequest;)V"))));
  }

  @Test
  void should_find_the_base_class_present_and_the_jar_needing_a_jakarta_recompile_on_10_1()
      throws Exception {
    PackageIndex target = PackageIndex.read(targetWar(tmp.resolve("target")));
    Path jar = TestArchives.write(tmp.resolve("lib/ngra-auth.jar"), ngraJar());

    VendorClassCheck.Result r =
        VendorClassCheck.check(Map.of("ngra-auth.jar", List.of(jar)), target, "10.1.0");

    assertThat(r.classes())
        .extracting(ClassFinding::vendorType, ClassFinding::status)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(BASE, ClassStatus.PRESENT),
            org.assertj.core.groups.Tuple.tuple(OLD_PROCESSOR, ClassStatus.MOVED),
            org.assertj.core.groups.Tuple.tuple(AUDIT, ClassStatus.MISSING));
    ClassFinding base = find(r, BASE);
    assertThat(base.detail()).contains("jasperserver-api-externalAuth-impl");
    assertThat(base.usedBy()).isEqualTo("extended by " + FILTER);
    assertThat(find(r, OLD_PROCESSOR).detail()).contains(NEW_PROCESSOR);
    assertThat(find(r, AUDIT).detail())
        .contains("last seen in package com.jaspersoft.jasperserver.api.logging.audit");
    assertThat(r.jakarta())
        .singleElement()
        .satisfies(
            j -> {
              assertThat(j.jar()).isEqualTo("ngra-auth.jar");
              assertThat(j.javaxReferences()).containsKeys("javax.servlet", "javax.ws.rs");
            });
  }

  @Test
  void should_not_ask_for_a_jakarta_recompile_below_10_0() throws Exception {
    PackageIndex target = PackageIndex.read(targetWar(tmp.resolve("target")));
    Path jar = TestArchives.write(tmp.resolve("lib/ngra-auth.jar"), ngraJar());

    assertThat(
            VendorClassCheck.check(Map.of("ngra-auth.jar", List.of(jar)), target, "9.0.0")
                .jakarta())
        .isEmpty();
  }

  @Test
  void should_report_an_unreadable_jar_instead_of_skipping_it() throws Exception {
    PackageIndex target = PackageIndex.read(targetWar(tmp.resolve("target")));
    Path broken =
        TestArchives.write(
            tmp.resolve("lib/broken.jar"),
            TestArchives.zip(Map.of("com/x/Broken.class", new byte[] {1, 2, 3, 4})));

    assertThat(
            VendorClassCheck.check(Map.of("broken.jar", List.of(broken)), target, "10.1.0")
                .classes())
        .singleElement()
        .satisfies(c -> assertThat(c.status()).isEqualTo(ClassStatus.UNREADABLE));
  }

  /** Every constant pool tag a real class file holds, read from the JDK's own String. */
  @Test
  void should_read_the_supertypes_and_references_of_a_real_class_file() throws Exception {
    try (InputStream in = ClassLoader.getSystemResourceAsStream("java/lang/String.class")) {
      if (in == null) {
        return;
      }
      ClassRefs refs = ClassRefs.read(in);
      assertThat(refs.name()).isEqualTo("java.lang.String");
      assertThat(refs.superName()).isEqualTo("java.lang.Object");
      assertThat(refs.interfaces()).contains("java.lang.Comparable");
      assertThat(refs.referenced())
          .contains("java.lang.StringBuilder")
          .doesNotContain("java.lang.String");
    }
  }

  private static ClassFinding find(VendorClassCheck.Result r, String type) {
    return r.classes().stream().filter(c -> c.vendorType().equals(type)).findFirst().orElseThrow();
  }
}
