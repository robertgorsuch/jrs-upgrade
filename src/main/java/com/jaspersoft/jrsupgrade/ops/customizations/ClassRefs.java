package com.jaspersoft.jrsupgrade.ops.customizations;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The types one class file names, read from its constant pool (issue #5): its superclass and
 * interfaces, every class constant, and the object types inside descriptors and signatures.
 * Invariants: the bytes are parsed as data and never loaded or linked; names are binary names
 * ({@code com.example.Outer$Inner}), array types reduced to their element type; a malformed class
 * file throws {@link IOException} rather than yielding a partial answer.
 */
record ClassRefs(String name, String superName, List<String> interfaces, Set<String> referenced) {

  private static final Pattern OBJECT_TYPE = Pattern.compile("L([\\w/$]+)[;<]");

  ClassRefs {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(superName, "superName");
    interfaces = List.copyOf(interfaces);
    referenced = Set.copyOf(referenced);
  }

  static ClassRefs read(InputStream bytes) throws IOException {
    DataInputStream in = new DataInputStream(bytes);
    if (in.readInt() != 0xCAFEBABE) {
      throw new IOException("not a class file");
    }
    in.readUnsignedShort();
    in.readUnsignedShort();
    int count = in.readUnsignedShort();
    String[] utf8 = new String[count];
    int[] classNameIndex = new int[count];
    for (int i = 1; i < count; i++) {
      int tag = in.readUnsignedByte();
      switch (tag) {
        case 1 -> utf8[i] = in.readUTF();
        case 7 -> classNameIndex[i] = in.readUnsignedShort();
        case 8, 16, 19, 20 -> in.readUnsignedShort();
        case 15 -> in.skipNBytes(3);
        case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
        case 5, 6 -> {
          in.skipNBytes(8);
          i++;
        }
        default -> throw new IOException("unknown constant pool tag " + tag + " at " + i);
      }
    }
    in.readUnsignedShort();
    String self = className(utf8, classNameIndex, in.readUnsignedShort());
    int superIndex = in.readUnsignedShort();
    String sup = superIndex == 0 ? "" : className(utf8, classNameIndex, superIndex);
    int n = in.readUnsignedShort();
    List<String> interfaces = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      interfaces.add(className(utf8, classNameIndex, in.readUnsignedShort()));
    }
    Set<String> referenced = new TreeSet<>();
    for (int i = 1; i < count; i++) {
      if (classNameIndex[i] != 0) {
        String c = elementType(utf8[classNameIndex[i]]);
        if (c != null) {
          referenced.add(c.replace('/', '.'));
        }
      } else if (utf8[i] != null && utf8[i].indexOf(';') > 0) {
        Matcher m = OBJECT_TYPE.matcher(utf8[i]);
        while (m.find()) {
          referenced.add(m.group(1).replace('/', '.'));
        }
      }
    }
    referenced.remove(self);
    return new ClassRefs(self, sup, interfaces, referenced);
  }

  private static String className(String[] utf8, int[] classNameIndex, int index)
      throws IOException {
    if (index <= 0 || index >= classNameIndex.length || classNameIndex[index] == 0) {
      throw new IOException("bad class index " + index);
    }
    String internal = utf8[classNameIndex[index]];
    if (internal == null) {
      throw new IOException("bad class name at " + index);
    }
    return internal.replace('/', '.');
  }

  /** {@code [[Lcom/x/Y;} -> {@code com/x/Y}; primitive arrays -> null; plain names unchanged. */
  private static String elementType(String internal) {
    if (internal == null) {
      return null;
    }
    if (!internal.startsWith("[")) {
      return internal;
    }
    int at = internal.lastIndexOf('[') + 1;
    return internal.charAt(at) == 'L' && internal.endsWith(";")
        ? internal.substring(at + 1, internal.length() - 1)
        : null;
  }
}
