package com.jaspersoft.jrsupgrade.ops;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A memoising supplier. Invariants: the delegate runs at most once successfully; an exception is
 * propagated unmemoised so a later call may retry (a server that was down may be up now); {@link
 * #peek()} never triggers the delegate, so a caller can close a resource only if it was ever
 * opened.
 */
public final class Lazy<T> implements Supplier<T> {

  private final Supplier<T> delegate;
  private final Object lock = new Object();
  private T value;

  private Lazy(Supplier<T> delegate) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
  }

  public static <T> Lazy<T> of(Supplier<T> delegate) {
    return new Lazy<>(delegate);
  }

  @Override
  public T get() {
    synchronized (lock) {
      if (value == null) {
        value = Objects.requireNonNull(delegate.get(), "lazy value");
      }
      return value;
    }
  }

  /** The value if it has been computed, without computing it. */
  public Optional<T> peek() {
    synchronized (lock) {
      return Optional.ofNullable(value);
    }
  }
}
