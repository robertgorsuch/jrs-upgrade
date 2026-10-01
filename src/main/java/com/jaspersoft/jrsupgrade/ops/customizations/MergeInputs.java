package com.jaspersoft.jrsupgrade.ops.customizations;

import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.MergeFinding;
import com.jaspersoft.jrsupgrade.ops.customizations.CustomizationOperations.MergeStatus;
import com.jaspersoft.jrsupgrade.ops.merge.Diff;
import com.jaspersoft.jrsupgrade.ops.merge.Diff3;
import com.jaspersoft.jrsupgrade.ops.merge.Text;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Issue #6: the three-way merge of a customized file: base is the vendor's file of the running
 * version, mine the installed customization, theirs the target version's file. Invariants: nothing
 * is written unless an output directory is given; under it, a file's inputs and results keep its
 * path relative to the webapp: {@code <path>.base}, {@code .mine}, {@code .theirs}, {@code .merged}
 * (with conflict markers where both sides changed the same lines) and {@code .patch} (the unified
 * diff from theirs to merged, which turns the target's file into the merged one); a file holding a
 * NUL byte is binary and not merged; a merged file keeps the target file's line ends.
 */
final class MergeInputs {

  static final List<String> SUFFIXES = List.of(".base", ".mine", ".theirs", ".merged", ".patch");

  private MergeInputs() {}

  static MergeFinding merge(
      String relativePath, byte[] base, byte[] mine, Optional<byte[]> theirs, Optional<Path> out)
      throws IOException {
    if (theirs.isEmpty()) {
      return new MergeFinding(relativePath, MergeStatus.NOT_IN_TARGET, 0, Optional.empty());
    }
    if (binary(base) || binary(mine) || binary(theirs.get())) {
      return new MergeFinding(relativePath, MergeStatus.BINARY, 0, Optional.empty());
    }
    Text theirsText = Text.of(theirs.get());
    Diff3.Result result =
        Diff3.merge(Text.of(base).lines(), Text.of(mine).lines(), theirsText.lines());
    MergeStatus status = result.clean() ? MergeStatus.CLEAN : MergeStatus.CONFLICT;
    if (out.isEmpty()) {
      return new MergeFinding(relativePath, status, result.conflicts(), Optional.empty());
    }
    Path dir = out.get().toAbsolutePath().normalize();
    Path stem = dir.resolve(relativePath).normalize();
    if (!stem.startsWith(dir)) {
      throw new IOException(relativePath + " leaves " + dir);
    }
    Files.createDirectories(stem.getParent());
    byte[] merged = theirsText.bytes(result.lines());
    write(stem, ".base", base);
    write(stem, ".mine", mine);
    write(stem, ".theirs", theirs.get());
    write(stem, ".merged", merged);
    List<String> patch =
        Diff.unified("a/" + relativePath, "b/" + relativePath, theirsText.lines(), result.lines());
    write(stem, ".patch", theirsText.bytes(patch));
    return new MergeFinding(
        relativePath, status, result.conflicts(), Optional.of(stem + ".merged"));
  }

  private static void write(Path stem, String suffix, byte[] bytes) throws IOException {
    Files.write(stem.resolveSibling(stem.getFileName() + suffix), bytes);
  }

  private static boolean binary(byte[] bytes) {
    for (byte b : bytes) {
      if (b == 0) {
        return true;
      }
    }
    return false;
  }
}
