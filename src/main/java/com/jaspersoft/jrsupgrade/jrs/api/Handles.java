package com.jaspersoft.jrsupgrade.jrs.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Opaque handles and statuses for asynchronous export and import (spec §7.2). Invariants: {@link
 * Phase#PENDING} is an import the server stopped before touching the repository and will not resume
 * by itself (REST reference 10.1 p.119: broken dependencies or an organisation mismatch), so it
 * counts as {@code done()}; only {@link Phase#INPROGRESS} keeps a poll going.
 */
public final class Handles {

  private Handles() {}

  public record ExportHandle(String id) {}

  public record ImportHandle(String id) {}

  public enum Phase {
    INPROGRESS,
    READY,
    FAILED,
    PENDING
  }

  public record ExportStatus(
      Phase phase,
      Optional<String> message,
      Optional<String> fileName,
      Optional<String> errorCode) {
    public boolean done() {
      return phase != Phase.INPROGRESS;
    }
  }

  /**
   * {@code errorParameters} are the server's {@code error.parameters}, e.g. the URIs with broken
   * dependencies; empty when the server gave none.
   */
  public record ImportStatus(
      Phase phase,
      Optional<String> message,
      Optional<String> errorCode,
      List<String> errorParameters) {

    public ImportStatus {
      errorParameters = List.copyOf(Objects.requireNonNull(errorParameters, "errorParameters"));
    }

    public ImportStatus(Phase phase, Optional<String> message, Optional<String> errorCode) {
      this(phase, message, errorCode, List.of());
    }

    public boolean done() {
      return phase != Phase.INPROGRESS;
    }
  }
}
