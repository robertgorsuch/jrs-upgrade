package com.jaspersoft.jrsupgrade.core.engine;

/**
 * A journal write or read that failed. Invariant: this is the only failure the engine expects from
 * a {@link Journal}, so the Runner can end the run honestly (review finding 1.10) without knowing
 * what the journal is made of; {@code core.state.StateStoreException} is the SQLite implementation
 * of it, which keeps the store's own message and cause.
 */
public class JournalException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public JournalException(String message, Throwable cause) {
    super(message, cause);
  }

  public JournalException(String message) {
    super(message);
  }
}
