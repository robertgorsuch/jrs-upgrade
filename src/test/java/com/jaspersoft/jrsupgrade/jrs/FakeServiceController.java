package com.jaspersoft.jrsupgrade.jrs;

import com.jaspersoft.jrsupgrade.core.platform.ServiceControlException;
import com.jaspersoft.jrsupgrade.core.platform.ServiceController;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory {@link ServiceController} that records every stop/start call in order. It can be told
 * to refuse every command (as a service manager does without the rights), to end the service and
 * then throw (a stop that goes wrong half-way), or to report a given state such as {@code UNKNOWN}.
 */
public final class FakeServiceController implements ServiceController {

  private State state;
  private final List<String> calls = new ArrayList<>();
  private boolean refuse;
  private boolean stopThrowsAfterStopping;

  public FakeServiceController(State initial) {
    this.state = initial;
  }

  public List<String> calls() {
    return List.copyOf(calls);
  }

  /** Sets the reported state without recording a call. */
  public void state(State newState) {
    this.state = newState;
  }

  /** Every later stop or start is refused by the platform, leaving the state unchanged. */
  public void refuse() {
    this.refuse = true;
  }

  /** The next stops end the service and then throw, like a stop script that dies after the kill. */
  public void failStopHalfway() {
    this.stopThrowsAfterStopping = true;
  }

  /** Stops behave normally again. */
  public void healStop() {
    this.stopThrowsAfterStopping = false;
  }

  @Override
  public State state() {
    return state;
  }

  @Override
  public State stop(Duration timeout) {
    calls.add("stop");
    if (refuse) {
      throw new ServiceControlException(
          List.of("systemctl", "stop", "jasperserver"), 1, "Access denied");
    }
    state = State.STOPPED;
    if (stopThrowsAfterStopping) {
      throw new IllegalStateException("stop script died after ending the service");
    }
    return state;
  }

  @Override
  public State start(Duration timeout) {
    calls.add("start");
    if (refuse) {
      throw new ServiceControlException(
          List.of("systemctl", "start", "jasperserver"), 1, "Access denied");
    }
    state = State.RUNNING;
    return state;
  }

  @Override
  public String describe() {
    return "fake service";
  }
}
