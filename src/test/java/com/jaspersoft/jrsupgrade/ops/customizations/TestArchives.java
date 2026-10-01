package com.jaspersoft.jrsupgrade.ops.customizations;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Jars, WARs and minimal class files built in memory for the customization-finding tests. */
public final class TestArchives {

  private TestArchives() {}

  public static byte[] zip(Map<String, byte[]> entries) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (Map.Entry<String, byte[]> e : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(e.getKey()));
        zip.write(e.getValue());
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  public static Path write(Path file, byte[] bytes) throws IOException {
    Files.createDirectories(file.getParent());
    try (OutputStream out = Files.newOutputStream(file)) {
      out.write(bytes);
    }
    return file;
  }

  public static byte[] text(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  /** A jar with a pom.properties for {@code groupId:artifactId:version}, plus {@code more}. */
  public static byte[] mavenJar(
      String groupId, String artifactId, String version, Map<String, byte[]> more)
      throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put(
        "META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties",
        text("groupId=" + groupId + "\nartifactId=" + artifactId + "\nversion=" + version + "\n"));
    entries.putAll(more);
    return zip(entries);
  }

  /** A jar holding the given classes (binary name -> class bytes). */
  public static byte[] classJar(Map<String, byte[]> classes) throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    classes.forEach((name, bytes) -> entries.put(name.replace('.', '/') + ".class", bytes));
    return zip(entries);
  }

  /**
   * A class file declaring {@code name} extending {@code superName} and implementing {@code
   * interfaces}, whose constant pool also references {@code refs} (as class constants) and holds
   * {@code descriptors} (as UTF-8 constants, the way method signatures name types).
   */
  public static byte[] classFile(
      String name,
      String superName,
      List<String> interfaces,
      List<String> refs,
      List<String> descriptors)
      throws IOException {
    List<Object[]> pool = new ArrayList<>();
    Map<String, Integer> classIndex = new LinkedHashMap<>();
    java.util.function.Function<String, Integer> classRef =
        n ->
            classIndex.computeIfAbsent(
                n,
                k -> {
                  pool.add(new Object[] {1, k.replace('.', '/')});
                  int utf = pool.size();
                  pool.add(new Object[] {7, utf});
                  return pool.size();
                });
    int self = classRef.apply(name);
    int sup = classRef.apply(superName);
    List<Integer> ifaces = interfaces.stream().map(classRef).toList();
    for (String r : refs) {
      int unused = classRef.apply(r);
    }
    descriptors.forEach(d -> pool.add(new Object[] {1, d}));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeInt(0xCAFEBABE);
      out.writeShort(0);
      out.writeShort(61);
      out.writeShort(pool.size() + 1);
      for (Object[] c : pool) {
        out.writeByte((Integer) c[0]);
        if ((Integer) c[0] == 1) {
          out.writeUTF((String) c[1]);
        } else {
          out.writeShort((Integer) c[1]);
        }
      }
      out.writeShort(0x21);
      out.writeShort(self);
      out.writeShort(sup);
      out.writeShort(ifaces.size());
      for (int i : ifaces) {
        out.writeShort(i);
      }
      out.writeShort(0);
      out.writeShort(0);
      out.writeShort(0);
    }
    return bytes.toByteArray();
  }

  public static Map<String, byte[]> entries(Object... pairs) {
    Map<String, byte[]> out = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      out.put((String) pairs[i], (byte[]) pairs[i + 1]);
    }
    return out;
  }
}
