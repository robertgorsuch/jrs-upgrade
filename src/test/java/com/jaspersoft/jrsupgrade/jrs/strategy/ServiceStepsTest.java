package com.jaspersoft.jrsupgrade.jrs.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.engine.CancellationToken;
import com.jaspersoft.jrsupgrade.core.engine.CheckResult;
import com.jaspersoft.jrsupgrade.core.engine.Context;
import com.jaspersoft.jrsupgrade.core.engine.Sleeper;
import com.jaspersoft.jrsupgrade.core.engine.Step;
import com.jaspersoft.jrsupgrade.core.engine.StepFailure;
import com.jaspersoft.jrsupgrade.core.engine.StepResult;
import com.jaspersoft.jrsupgrade.core.event.EventSink;
import com.jaspersoft.jrsupgrade.core.platform.ServiceController;
import com.jaspersoft.jrsupgrade.jrs.FakeJrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.RecordingSink;
import com.jaspersoft.jrsupgrade.jrs.TestConfigs;
import com.jaspersoft.jrsupgrade.jrs.api.JrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsupgrade.jrs.service.ContextServiceRuntime;
import com.jaspersoft.jrsupgrade.jrs.service.ServiceRuntime;
import com.jaspersoft.jrsupgrade.jrs.service.ServiceSteps;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shared service steps as the vendor export/import strategy uses them: the runtime is resolved
 * from the run's context ({@link ContextServiceRuntime}) rather than handed in by an ops operation.
 * Issue #43 is pinned here: the stop marker goes down before the stop, a refused command fails at
 * once, and an {@code UNKNOWN} state is refused before anything is stopped.
 */
class ServiceStepsTest {

  @TempDir Path tmp;
  private StrategyFixture fx;
  private Config config;
  private ServiceRuntime.Source runtime;

  @BeforeEach
  void setUp() {
    fx = new StrategyFixture(tmp);
    config = StrategyFixture.vendorConfig(tmp.resolve("jrs"), Optional.of(tmp.resolve("jdk")));
    runtime = ContextServiceRuntime.source(fx.polling.clock(), fx.polling.sleeper());
  }

  @AfterEach
  void tearDown() {
    fx.close();
  }

  private Context ctx() {
    return fx.context(config, new FakeJrsAdapter());
  }

  private Step stop(String phase) {
    return ServiceSteps.stop(runtime, phase, phase + ".stop-service");
  }

  private Step start(String phase) {
    return ServiceSteps.start(runtime, phase, phase + ".start-service");
  }

  private Step waitForServer(String phase, ServiceRuntime.Source source) {
    return ServiceSteps.waitForServer(source, phase, phase + ".wait-for-server");
  }

  @Test
  void should_not_call_controller_when_service_already_stopped() {
    fx.service.stop(Duration.ZERO);
    Step stop = stop("apply");
    Context ctx = ctx();

    StepResult result = stop.execute(ctx, EventSink.discard());
    StepResult comp = stop.compensate(ctx, EventSink.discard());

    assertThat(result).isInstanceOf(StepResult.Ok.class);
    assertThat(comp).isInstanceOf(StepResult.Ok.class);
    assertThat(fx.service.calls()).containsExactly("stop");
    assertThat(fx.service.state()).isEqualTo(ServiceController.State.STOPPED);
    assertThat(Files.exists(RunFiles.in(ctx, "apply.stop-service.stopped"))).isFalse();
  }

  @Test
  void should_stop_record_marker_and_start_on_compensate_when_service_running() {
    Step stop = stop("apply");
    Context ctx = ctx();

    StepResult result = stop.execute(ctx, EventSink.discard());
    assertThat(Files.exists(RunFiles.in(ctx, "apply.stop-service.stopped"))).isTrue();
    assertThat(stop.postcheck(ctx)).isInstanceOf(CheckResult.Pass.class);
    StepResult comp = stop.compensate(ctx, EventSink.discard());

    assertThat(result).isInstanceOf(StepResult.Ok.class);
    assertThat(comp).isInstanceOf(StepResult.Ok.class);
    assertThat(fx.service.calls()).containsExactly("stop", "start");
    assertThat(fx.service.state()).isEqualTo(ServiceController.State.RUNNING);
    assertThat(stop.id()).isEqualTo("apply.stop-service");
  }

  @Test
  void should_start_service_on_compensate_when_stop_failed_half_way() {
    fx.service.failStopHalfway();
    Step stop = stop("export");
    Context ctx = ctx();

    StepResult result = stop.execute(ctx, EventSink.discard());

    assertThat(result).isInstanceOf(StepResult.Failed.class);
    assertThat(RunFiles.in(ctx, "export.stop-service.stopped")).exists();
    fx.service.healStop();
    assertThat(stop.compensate(ctx, EventSink.discard())).isInstanceOf(StepResult.Ok.class);
    assertThat(fx.service.calls()).containsExactly("stop", "start");
    assertThat(fx.service.state()).isEqualTo(ServiceController.State.RUNNING);
  }

  @Test
  void should_fail_at_once_with_rights_remediation_when_platform_refuses_stop() {
    fx.service.refuse();
    Step stop = stop("export");

    StepResult result = stop.execute(ctx(), EventSink.discard());

    assertThat(result).isInstanceOf(StepResult.Failed.class);
    assertThat(((StepResult.Failed) result).failure())
        .isInstanceOfSatisfying(
            StepFailure.Recoverable.class,
            f -> {
              assertThat(f.nextAction()).isEqualTo(ServiceSteps.RIGHTS_REMEDIATION);
              assertThat(f.cause()).contains("Access denied");
            });
    assertThat(fx.service.calls()).containsExactly("stop");
    assertThat(fx.service.state()).isEqualTo(ServiceController.State.RUNNING);
  }

  @Test
  void should_refuse_precheck_when_service_state_is_unknown() {
    fx.service.state(ServiceController.State.UNKNOWN);
    Step stop = stop("export");

    CheckResult check = stop.precheck(ctx());

    assertThat(check).isInstanceOf(CheckResult.Fail.class);
    assertThat(((CheckResult.Fail) check).message()).contains("cannot be determined");
    assertThat(fx.service.calls()).isEmpty();
  }

  @Test
  void should_be_noop_when_start_called_on_running_service() {
    Step start = start("apply");

    StepResult result = start.execute(ctx(), EventSink.discard());

    assertThat(result).isInstanceOf(StepResult.Ok.class);
    assertThat(fx.service.calls()).isEmpty();
    assertThat(start.id()).isEqualTo("apply.start-service");
  }

  @Test
  void should_start_and_stop_on_compensate_when_service_stopped() {
    fx.service.stop(Duration.ZERO);
    Step start = start("apply");
    Context ctx = ctx();

    StepResult result = start.execute(ctx, EventSink.discard());
    StepResult comp = start.compensate(ctx, EventSink.discard());

    assertThat(result).isInstanceOf(StepResult.Ok.class);
    assertThat(comp).isInstanceOf(StepResult.Ok.class);
    assertThat(fx.service.calls()).containsExactly("stop", "start", "stop");
  }

  @Test
  void should_fail_precheck_when_service_kind_not_configured() {
    Config noService = TestConfigs.server(StrategyFixture.BASE, Config.AuthMode.BASIC);

    CheckResult result = stop("apply").precheck(fx.context(noService, new FakeJrsAdapter()));

    assertThat(result).isInstanceOf(CheckResult.Fail.class);
    assertThat(((CheckResult.Fail) result).message()).contains("service.kind");
  }

  @Test
  void should_wait_until_server_answers_when_server_restarting() {
    FakeJrsAdapter adapter = new FakeJrsAdapter();
    AtomicInteger n = new AtomicInteger();
    adapter.refreshIdentityBehaviour =
        () -> {
          if (n.incrementAndGet() < 3) {
            throw new JrsUnreachableException(
                URI.create("http://x"), "connection refused", "wait", null);
          }
          return FakeJrsAdapter.IDENTITY;
        };
    Step wait = waitForServer("apply", runtime);

    StepResult result = wait.execute(fx.context(config, adapter), EventSink.discard());

    assertThat(result).isInstanceOf(StepResult.Ok.class);
    assertThat(adapter.refreshIdentityCalls).isEqualTo(3);
    assertThat(wait.mutating()).isFalse();
    assertThat(wait.id()).isEqualTo("apply.wait-for-server");
  }

  @Test
  void should_fail_recoverably_and_log_progress_when_server_never_answers() {
    FakeJrsAdapter adapter = new FakeJrsAdapter();
    adapter.refreshIdentityBehaviour =
        () -> {
          throw new JrsUnreachableException(URI.create("http://x"), "refused", "wait", null);
        };
    Step wait = waitForServer("apply", runtime);
    RecordingSink sink = new RecordingSink();

    StepResult result = wait.execute(fx.context(config, adapter), sink);

    assertThat(result).isInstanceOf(StepResult.Failed.class);
    assertThat(((StepResult.Failed) result).failure().cause()).contains("10 minutes");
    assertThat(sink.logMessages()).anyMatch(m -> m.startsWith("not yet: refused"));
  }

  @Test
  void should_throw_cancelled_when_token_cancelled_before_poll() {
    CancellationToken token = new CancellationToken();
    token.cancel("operator");
    Context ctx =
        new Context(
            StrategyFixture.RUN,
            fx.home,
            fx.platform,
            token,
            Map.of(Config.class, config, JrsAdapter.class, new FakeJrsAdapter()));

    assertThatThrownBy(() -> waitForServer("apply", runtime).execute(ctx, EventSink.discard()))
        .isInstanceOf(CancellationToken.CancelledException.class);
  }

  /**
   * Review finding 1.11, remainder: the poll backoff slept without the token, so Ctrl-C during a
   * long backoff was noticed only when the backoff ended. A sleeper that cancels after its first
   * slice must end the wait within that slice.
   */
  @Test
  void should_notice_cancellation_during_the_poll_backoff_rather_than_after_it() {
    FakeJrsAdapter adapter = new FakeJrsAdapter();
    adapter.refreshIdentityBehaviour =
        () -> {
          throw new JrsUnreachableException(URI.create("http://x"), "refused", "wait", null);
        };
    CancellationToken token = new CancellationToken();
    CancellingSleeper sleeper = new CancellingSleeper(token);
    Context ctx =
        new Context(
            StrategyFixture.RUN,
            fx.home,
            fx.platform,
            token,
            Map.of(Config.class, config, JrsAdapter.class, adapter));
    ServiceRuntime.Source cancelling = ContextServiceRuntime.source(fx.clock, sleeper);

    assertThatThrownBy(() -> waitForServer("apply", cancelling).execute(ctx, EventSink.discard()))
        .isInstanceOf(CancellationToken.CancelledException.class);
    assertThat(sleeper.slept)
        .as("the wait ends within one slice, not after the whole backoff")
        .isLessThanOrEqualTo(Sleeper.SLICE);
  }

  /** Books every requested sleep and cancels the token on the first one, like Ctrl-C mid-wait. */
  static final class CancellingSleeper implements Sleeper {
    private final CancellationToken token;
    Duration slept = Duration.ZERO;

    CancellingSleeper(CancellationToken token) {
      this.token = token;
    }

    @Override
    public void sleep(Duration duration) {
      slept = slept.plus(duration);
      token.cancel("operator");
    }
  }
}
