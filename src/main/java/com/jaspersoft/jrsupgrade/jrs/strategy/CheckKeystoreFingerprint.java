package com.jaspersoft.jrsupgrade.jrs.strategy;

import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.jrs.api.ImportRequest;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsupgrade.jrs.api.KeystoreInfo;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Compares the keystore fingerprint recorded in the archive's sidecar with the target server's
 * (spec §9.3) before anything mutates. Non-mutating; the whole decision is in {@link #precheck}: no
 * sidecar or no recorded fingerprint is a warning, a mismatch fails with the remediation unless the
 * request supplies a source keystore, in which case {@code ImportSourceKeystore} handles it.
 */
final class CheckKeystoreFingerprint implements Step {

  static final String ID = "import.check-keystore";

  static final String REMEDIATION =
      "the archive was exported from a server with a different keystore, so encrypted"
          + " passwords inside it cannot be decrypted here; copy the source server's .jrsks and"
          + " .jrsksp files to this host and run the import again with --source-keystore <path>"
          + " and --source-keystore-password-ref <ref>, or export the data again from a server"
          + " that shares this keystore";

  private final String phase;
  private final ImportRequest request;

  CheckKeystoreFingerprint(String phase, ImportRequest request) {
    this.phase = Objects.requireNonNull(phase, "phase");
    this.request = Objects.requireNonNull(request, "request");
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public String title() {
    return "Check keystore fingerprint";
  }

  @Override
  public String phase() {
    return phase;
  }

  @Override
  public String detail() {
    return Sidecar.pathFor(request.archive()).toString();
  }

  @Override
  public boolean mutating() {
    return false;
  }

  @Override
  public CheckResult precheck(Context ctx) {
    if (request.keyAlias().isPresent()) {
      // the named key decrypts the archive on any server that holds it (REST reference 10.1
      // p.117); this server's own keystore is not what the archive was encrypted with
      return CheckResult.pass();
    }
    Path sidecarFile = Sidecar.pathFor(request.archive());
    Optional<Sidecar> sidecar;
    try {
      sidecar = Sidecar.read(sidecarFile);
    } catch (IOException | IllegalArgumentException e) {
      return CheckResult.fail(
          "cannot read sidecar " + sidecarFile + ": " + e.getMessage(),
          "fix or remove the sidecar file; without it the keystore fingerprint is not verified");
    }
    if (sidecar.isEmpty()) {
      return CheckResult.warn("no sidecar " + sidecarFile + "; keystore fingerprint not verified");
    }
    Optional<String> recorded = sidecar.get().keystoreFingerprint();
    if (recorded.isEmpty()) {
      return CheckResult.warn(
          "sidecar carries no keystore fingerprint (source server "
              + sidecar.get().serverVersion()
              + " had none); nothing to compare");
    }
    KeystoreInfo target;
    try {
      target = ctx.service(JrsAdapter.class).keystore();
    } catch (JrsUnreachableException e) {
      return CheckResult.fail("server unreachable: " + e.getMessage(), e.remediation());
    }
    if (target.fingerprint().isEmpty()) {
      return CheckResult.warn(
          "target keystore fingerprint unknown ("
              + target.reason().orElse("no keystore found")
              + "); the archive was exported with keystore "
              + shortFp(recorded.get()));
    }
    if (target.fingerprint().get().equals(recorded.get())) {
      return CheckResult.pass();
    }
    if (request.sourceKeystore().isPresent()) {
      return CheckResult.warn(
          "keystore fingerprint differs (archive "
              + shortFp(recorded.get())
              + ", server "
              + shortFp(target.fingerprint().get())
              + "); the source keystore "
              + request.sourceKeystore().get()
              + " will be imported first");
    }
    return CheckResult.fail(
        "keystore fingerprint mismatch: archive was exported with keystore "
            + shortFp(recorded.get())
            + " but this server uses "
            + shortFp(target.fingerprint().get()),
        REMEDIATION);
  }

  @Override
  public StepResult execute(Context ctx, EventSink out) {
    return StepResult.ok();
  }

  @Override
  public StepResult compensate(Context ctx, EventSink out) {
    return StepResult.ok();
  }

  static String shortFp(String fingerprint) {
    return fingerprint.length() > 12 ? fingerprint.substring(0, 12) + "…" : fingerprint;
  }
}
