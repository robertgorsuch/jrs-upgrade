package com.jaspersoft.jrsupgrade.jrs;

import com.jaspersoft.jrsupgrade.core.event.Event;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Collects every emitted event in order. */
public final class RecordingSink implements EventSink {

  private final List<Event> events = Collections.synchronizedList(new ArrayList<>());

  @Override
  public void emit(Event event) {
    events.add(event);
  }

  public List<Event> events() {
    return List.copyOf(events);
  }

  public <T extends Event> List<T> of(Class<T> type) {
    return events.stream().filter(type::isInstance).map(type::cast).toList();
  }

  public List<String> logMessages() {
    return of(Event.Log.class).stream().map(Event.Log::message).toList();
  }
}
