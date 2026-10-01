package com.jaspersoft.jrsupgrade.core.secrets;

import java.util.Arrays;
import java.util.Objects;

/**
 * A secret value held in a {@code char[]} (spec §5.2). Invariants: {@link #toString()} never
 * reveals the value; {@link #chars()} hands out a defensive copy the caller must zero; {@link
 * #close()} zeroes the backing array and every later access fails fast, so a closed secret can
 * never leak through a stale reference.
 */
public final class Secret implements AutoCloseable {

  private final char[] value;
  private boolean closed;

  private Secret(char[] value) {
    this.value = value;
  }

  /** Copies {@code chars}; the caller keeps ownership of (and should zero) its own array. */
  public static Secret of(char[] chars) {
    Objects.requireNonNull(chars, "chars");
    return new Secret(chars.clone());
  }

  /**
   * Wraps a value that already exists as an immutable {@link String} (environment variables,
   * console input); the string itself cannot be zeroed, which is why callers should prefer {@link
   * #of(char[])} whenever the source is a char array.
   */
  public static Secret fromString(String text) {
    Objects.requireNonNull(text, "text");
    return new Secret(text.toCharArray());
  }

  /** A defensive copy of the value; zero it when done. */
  public synchronized char[] chars() {
    checkOpen();
    return value.clone();
  }

  public synchronized int length() {
    checkOpen();
    return value.length;
  }

  public synchronized boolean isClosed() {
    return closed;
  }

  /** Constant-time comparison with another secret; false when either is closed. */
  public boolean matches(Secret other) {
    Objects.requireNonNull(other, "other");
    char[] mine = null;
    char[] theirs = null;
    try {
      synchronized (this) {
        if (closed) {
          return false;
        }
        mine = value.clone();
      }
      synchronized (other) {
        if (other.closed) {
          return false;
        }
        theirs = other.value.clone();
      }
      int diff = mine.length ^ theirs.length;
      int n = Math.min(mine.length, theirs.length);
      for (int i = 0; i < n; i++) {
        diff |= mine[i] ^ theirs[i];
      }
      return diff == 0;
    } finally {
      if (mine != null) {
        Arrays.fill(mine, '\0');
      }
      if (theirs != null) {
        Arrays.fill(theirs, '\0');
      }
    }
  }

  @Override
  public synchronized void close() {
    if (!closed) {
      Arrays.fill(value, '\0');
      closed = true;
    }
  }

  @Override
  public String toString() {
    return "[secret]";
  }

  private void checkOpen() {
    if (closed) {
      throw new IllegalStateException("secret has been closed");
    }
  }
}
