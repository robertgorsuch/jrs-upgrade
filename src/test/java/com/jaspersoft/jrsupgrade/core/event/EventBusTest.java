package com.jaspersoft.jrsupgrade.core.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventBusTest {

  private static Event event(String runId) {
    return new Event.RunSucceeded(Instant.parse("2026-09-08T10:15:00Z"), runId, "run", 5L);
  }

  @Test
  void should_deliver_to_subscribers_in_subscription_order_when_emitting() {
    EventBus bus = new EventBus();
    List<String> order = new ArrayList<>();
    bus.subscribe(e -> order.add("a"));
    bus.subscribe(e -> order.add("b"));
    bus.subscribe(e -> order.add("c"));

    bus.emit(event("r1"));

    assertThat(order).containsExactly("a", "b", "c");
  }

  @Test
  void should_keep_delivering_to_other_subscribers_when_one_throws() {
    EventBus bus = new EventBus();
    RecordingSink first = new RecordingSink();
    RecordingSink last = new RecordingSink();
    bus.subscribe(first);
    bus.subscribe(
        e -> {
          throw new IllegalStateException("subscriber bug");
        });
    bus.subscribe(last);

    assertThatCode(() -> bus.emit(event("r1"))).doesNotThrowAnyException();

    assertThat(first.events()).hasSize(1);
    assertThat(last.events()).hasSize(1);
    assertThat(bus.subscriberCount()).isEqualTo(3);
  }

  @Test
  void should_stop_delivering_when_subscription_is_closed() {
    EventBus bus = new EventBus();
    RecordingSink sink = new RecordingSink();
    EventBus.Subscription sub = bus.subscribe(sink);
    bus.emit(event("r1"));

    sub.close();
    sub.close();
    bus.emit(event("r2"));

    assertThat(sink.events()).extracting(Event::runId).containsExactly("r1");
    assertThat(bus.subscriberCount()).isZero();
  }

  @Test
  void should_remove_every_registration_when_unsubscribing_a_sink() {
    EventBus bus = new EventBus();
    RecordingSink sink = new RecordingSink();
    bus.subscribe(sink);
    bus.subscribe(sink);
    bus.emit(event("r1"));
    assertThat(sink.events()).hasSize(2);

    bus.unsubscribe(sink);
    bus.emit(event("r2"));

    assertThat(sink.events()).hasSize(2);
  }

  @Test
  void should_allow_unsubscribing_from_within_a_delivery_when_emitting() {
    EventBus bus = new EventBus();
    List<String> seen = new ArrayList<>();
    EventBus.Subscription[] self = new EventBus.Subscription[1];
    self[0] =
        bus.subscribe(
            e -> {
              seen.add(e.runId());
              self[0].close();
            });

    bus.emit(event("r1"));
    bus.emit(event("r2"));

    assertThat(seen).containsExactly("r1");
  }
}
