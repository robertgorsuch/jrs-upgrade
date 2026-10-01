package com.jaspersoft.jrsupgrade.core.engine;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.event.EventBus;
import com.jaspersoft.jrsupgrade.core.event.RecordingSink;
import com.jaspersoft.jrsupgrade.core.state.StateStore;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Everything an engine test needs: a temp home, an open store, a bus, a fixed clock, a runner. */
public final class EngineFixture implements AutoCloseable {

  public static final Instant NOW = Instant.parse("2026-09-08T10:15:00Z");

  public final JrsUpgradeHome home;
  public final StateStore store;
  public final EventBus bus = new EventBus();
  public final RecordingSink sink = new RecordingSink();
  public final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  public final List<Duration> sleeps = new ArrayList<>();
  public final List<String> trace = new ArrayList<>();
  public final Runner runner;

  public EngineFixture(Path root) {
    this.home = new JrsUpgradeHome(root);
    this.store = StateStore.open(home, clock);
    bus.subscribe(sink);
    this.runner = new Runner(store, bus, clock, recordingSleeper());
  }

  /** Records each requested wait whole (not in slices) and still honours the token. */
  private Sleeper recordingSleeper() {
    return new Sleeper() {
      @Override
      public void sleep(Duration duration) {
        sleeps.add(duration);
      }

      @Override
      public void sleep(Duration duration, CancellationToken token) {
        token.checkpoint();
        sleeps.add(duration);
        token.checkpoint();
      }
    };
  }

  public Context context(String runId) {
    return new Context(
        runId, home, new FakePlatform(home.root()), new CancellationToken(), Map.of());
  }

  public FakeStep step(String id, String phase) {
    return new FakeStep(id, phase, trace);
  }

  public static Plan plan(String planId, Step... steps) {
    return new Plan(planId, Arrays.asList(steps), summary(), fingerprint());
  }

  public static PlanSummary summary() {
    return new PlanSummary(
        "hotfix apply",
        "server-1",
        List.of(),
        List.of(),
        false,
        List.of(),
        Map.of(),
        "rest",
        List.of());
  }

  public static PlanFingerprint fingerprint() {
    return PlanFingerprint.of(Map.of("server", "srv-1", "bundle", "sha256:abc"));
  }

  @Override
  public void close() {
    store.close();
  }
}
