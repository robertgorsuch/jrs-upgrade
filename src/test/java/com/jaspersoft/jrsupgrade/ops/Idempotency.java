package com.jaspersoft.jrsupgrade.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Support for the Phase 8 idempotency tests (spec §6.1): drives steps exactly as the Runner and
 * {@code Recovery.resume} do (precheck, execute, postcheck), and captures the observable end state
 * of a directory tree as relative path to SHA-256 (or a normalised text for files whose content is
 * legitimately non-deterministic) so a re-executed or re-compensated step can be shown to have
 * changed nothing.
 */
public final class Idempotency {

  private Idempotency() {}

  /** Precheck must not fail, execute must return Ok, postcheck must not fail. */
  public static void executeOk(Step step, Context ctx) {
    executeOk(step, ctx, EventSink.discard());
  }

  public static void executeOk(Step step, Context ctx, EventSink sink) {
    CheckResult pre = step.precheck(ctx);
    assertThat(pre)
        .as("precheck of " + step.id() + ": " + pre)
        .isNotInstanceOf(CheckResult.Fail.class);
    StepResult result = step.execute(ctx, sink);
    assertThat(result)
        .as("execute of " + step.id() + ": " + describe(result))
        .isInstanceOf(StepResult.Ok.class);
    CheckResult post = step.postcheck(ctx);
    assertThat(post)
        .as("postcheck of " + step.id() + ": " + post)
        .isNotInstanceOf(CheckResult.Fail.class);
  }

  public static void compensateOk(Step step, Context ctx) {
    compensateOk(step, ctx, EventSink.discard());
  }

  public static void compensateOk(Step step, Context ctx, EventSink sink) {
    StepResult result = step.compensate(ctx, sink);
    assertThat(result)
        .as("compensate of " + step.id() + ": " + describe(result))
        .isInstanceOf(StepResult.Ok.class);
  }

  /** Runs every step of the plan in order up to and including {@code stepId}. */
  public static void runUpTo(Plan plan, Context ctx, String stepId) {
    runUpTo(plan, ctx, stepId, EventSink.discard());
  }

  public static void runUpTo(Plan plan, Context ctx, String stepId, EventSink sink) {
    for (Step s : plan.steps()) {
      executeOk(s, ctx, sink);
      if (s.id().equals(stepId)) {
        return;
      }
    }
    throw new AssertionError("plan has no step " + stepId + ": " + ids(plan));
  }

  /** Runs the whole plan in order. */
  public static void runAll(Plan plan, Context ctx) {
    runAll(plan, ctx, EventSink.discard());
  }

  public static void runAll(Plan plan, Context ctx, EventSink sink) {
    List<Step> steps = plan.steps();
    runUpTo(plan, ctx, steps.get(steps.size() - 1).id(), sink);
  }

  public static Step step(Plan plan, String id) {
    return plan.steps().stream()
        .filter(s -> s.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no step " + id + " in " + ids(plan)));
  }

  public static List<String> ids(Plan plan) {
    return plan.steps().stream().map(Step::id).toList();
  }

  /** Replaces the hash of some files with a canonical text; empty means "hash the file". */
  public interface Normaliser {
    Optional<String> apply(Path file) throws IOException;
  }

  /** Relative path to SHA-256 of every regular file under {@code dir}; empty when absent. */
  public static Map<String, String> tree(String prefix, Path dir) throws IOException {
    return tree(prefix, dir, p -> Optional.empty());
  }

  /**
   * Like {@link #tree(String, Path)} but {@code normaliser} may replace the hash of a file with a
   * canonical text (for example a properties file minus its date comment).
   */
  public static Map<String, String> tree(String prefix, Path dir, Normaliser normaliser)
      throws IOException {
    Map<String, String> out = new TreeMap<>();
    if (!Files.isDirectory(dir)) {
      return out;
    }
    List<Path> files = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.filter(Files::isRegularFile).forEach(files::add);
    }
    for (Path p : files) {
      String key = prefix + "/" + dir.relativize(p).toString().replace('\\', '/');
      Optional<String> text = normaliser.apply(p);
      out.put(key, text.isPresent() ? text.get() : sha256(p));
    }
    return out;
  }

  /** The file's lines without those starting with {@code #}, for files with date comments. */
  public static Optional<String> withoutComments(Path file) throws IOException {
    List<String> kept = new ArrayList<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      if (!line.startsWith("#")) {
        kept.add(line);
      }
    }
    return Optional.of(String.join("\n", kept));
  }

  public static String sha256(Path file) throws IOException {
    MessageDigest md;
    try {
      md = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    byte[] buf = new byte[64 * 1024];
    try (InputStream in = new DigestInputStream(Files.newInputStream(file), md)) {
      while (in.read(buf) != -1) {
        // the digest stream consumes the bytes
      }
    }
    return HexFormat.of().formatHex(md.digest());
  }

  private static String describe(StepResult result) {
    return switch (result) {
      case StepResult.Ok ok -> "ok";
      case StepResult.Failed f -> f.failure().cause() + " / " + f.failure().nextAction();
    };
  }
}
