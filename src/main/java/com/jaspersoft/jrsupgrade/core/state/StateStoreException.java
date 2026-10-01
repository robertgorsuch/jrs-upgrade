package com.jaspersoft.jrsupgrade.core.state;

import com.jaspersoft.jrsupgrade.core.engine.JournalException;

/**
 * Unchecked wrapper for SQL failures; the state store never leaks {@code SQLException}. It is a
 * {@link JournalException} so the engine can end a run on a failed journal write without knowing
 * that the journal is SQLite (roadmap item 16).
 */
public final class StateStoreException extends JournalException {
  private static final long serialVersionUID = 1L;

  public StateStoreException(String message, Throwable cause) {
    super(message, cause);
  }

  public StateStoreException(String message) {
    super(message);
  }
}
