package com.jaspersoft.jrsupgrade.core.event;

/**
 * Where a {@code Step} or the {@code Runner} publishes events. Invariant: implementations must be
 * safe to call from any thread and must never throw back into the emitter; a failing subscriber is
 * the subscriber's problem, not the run's.
 */
@FunctionalInterface
public interface EventSink {

  void emit(Event event);

  /** A sink that drops everything; useful for planning and tests. */
  static EventSink discard() {
    return e -> {};
  }
}
