package com.jaspersoft.jrsupgrade.core.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * SHA-256 over every input a plan depends on (spec §6.2): server identity, input artifact hash,
 * hash of every target file the plan will touch, and the resolved effective config. Invariant: the
 * same inputs always produce the same {@link #value()} regardless of map iteration order, and
 * execution refuses to run a plan whose recomputed fingerprint differs.
 */
public record PlanFingerprint(String value, Map<String, String> inputs) {

  public PlanFingerprint {
    inputs = new TreeMap<>(inputs);
  }

  /** Builds a fingerprint from labelled inputs; each value should itself be a hash or identity. */
  public static PlanFingerprint of(Map<String, String> inputs) {
    TreeMap<String, String> sorted = new TreeMap<>(inputs);
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      for (var e : sorted.entrySet()) {
        md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        md.update(e.getValue().getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
      }
      return new PlanFingerprint("sha256:" + HexFormat.of().formatHex(md.digest()), sorted);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public boolean matches(PlanFingerprint other) {
    return value.equals(other.value);
  }

  /** Keys whose values differ, for the "inputs changed since planning" message. */
  public List<String> changedKeys(PlanFingerprint other) {
    var keys = new java.util.TreeSet<>(inputs.keySet());
    keys.addAll(other.inputs.keySet());
    return keys.stream()
        .filter(k -> !java.util.Objects.equals(inputs.get(k), other.inputs.get(k)))
        .toList();
  }
}
