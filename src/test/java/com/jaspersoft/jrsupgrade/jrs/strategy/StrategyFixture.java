package com.jaspersoft.jrsupgrade.jrs.strategy;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.engine.CancellationToken;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Plan;
import com.jaspersoft.jrsupgrade.core.engine.PlanFingerprint;
import com.jaspersoft.jrsupgrade.core.engine.PlanSummary;
import com.jaspersoft.jrsupgrade.core.engine.RunOptions;
import com.jaspersoft.jrsupgrade.core.engine.RunOutcome;
import com.jaspersoft.jrsupgrade.core.engine.Runner;
import com.jaspersoft.jrsupgrade.core.engine.Sleeper;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.platform.ServiceConfig;
import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.core.state.StateStore;
import com.jaspersoft.jrsupgrade.jrs.CapturingRunner;
import com.jaspersoft.jrsupgrade.jrs.FakePlatform;
import com.jaspersoft.jrsupgrade.jrs.FakeServiceController;
import com.jaspersoft.jrsupgrade.jrs.RecordingSink;
import com.jaspersoft.jrsupgrade.jrs.TestConfigs;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything a strategy test needs: a temp home with a real state store and Runner, a fixed clock,
 * a no-wait polling policy, a fake platform with a recording service controller and a capturing
 * process runner, and a context builder that registers the services the steps look up.
 */
final class StrategyFixture implements AutoCloseable {

  static final Instant NOW = Instant.parse("2026-09-08T10:15:00Z");
  static final String RUN = "r-20260908-101500-0001";
  static final URI BASE = URI.create("http://localhost:8080/jasperserver-pro");

  final JrsUpgradeHome home;
  final StateStore store;
  final RecordingSink sink = new RecordingSink();
  final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  final Runner runner;
  final Polling polling;
  final CapturingRunner processes = new CapturingRunner();
  final FakeServiceController service =
      new FakeServiceController(
          com.jaspersoft.jrsupgrade.core.platform.ServiceController.State.RUNNING);
  final Platform platform;
  final Redactor redactor = new Redactor();

  StrategyFixture(Path root) {
    this.home = new JrsUpgradeHome(root.resolve("home"));
    this.store = StateStore.open(home, clock);
    this.runner = new Runner(store, sink, clock, Sleeper.none());
    this.polling =
        new Polling(
            Duration.ZERO,
            Duration.ZERO,
            Duration.ofHours(2),
            Duration.ofSeconds(30),
            clock,
            Sleeper.none());
    this.platform = new FakePlatform(Platform.OsFamily.LINUX, processes, service);
  }

  /** A config with a Linux install dir, systemd service and vendor JDK under {@code root}. */
  static Config vendorConfig(Path installDir, Optional<Path> javaHome) {
    Config base = TestConfigs.server(BASE, Config.AuthMode.BASIC);
    Config.Server s = base.server();
    Config.Server server =
        new Config.Server(
            s.baseUrl(),
            s.webappName(),
            Optional.of(installDir),
            s.tomcatDir(),
            s.buildomaticDir(),
            s.runAsUser(),
            s.auth());
    Config.Service service =
        new Config.Service(
            Optional.of(ServiceConfig.Kind.SYSTEMD),
            Optional.of("jasperserver"),
            Optional.empty(),
            5);
    return new Config(
        server,
        service,
        base.database(),
        new Config.Vendor(javaHome),
        base.network(),
        base.backups(),
        base.smoke());
  }

  Context context(Config config, JrsAdapter adapter, Map<Class<?>, Object> extra) {
    Map<Class<?>, Object> services = new HashMap<>();
    services.put(Config.class, config);
    services.put(JrsAdapter.class, adapter);
    services.put(Redactor.class, redactor);
    services.putAll(extra);
    return new Context(RUN, home, platform, new CancellationToken(), services);
  }

  Context context(Config config, JrsAdapter adapter) {
    return context(config, adapter, Map.of());
  }

  static Plan plan(List<Step> steps) {
    return new Plan(
        "p1",
        steps,
        new PlanSummary(
            "export", "srv", List.of(), List.of(), false, List.of(), Map.of(), "rest", List.of()),
        fingerprint());
  }

  static PlanFingerprint fingerprint() {
    return PlanFingerprint.of(Map.of("server", "srv-1"));
  }

  RunOutcome run(List<Step> steps, Context ctx) {
    return runner.run(plan(steps), ctx, fingerprint(), RunOptions.DEFAULT);
  }

  List<String> journal() {
    return store.transitions(RUN).stream().map(t -> t.stepId() + ":" + t.toState()).toList();
  }

  @Override
  public void close() {
    store.close();
  }
}
