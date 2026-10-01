package com.jaspersoft.jrsupgrade.ops;

import com.jaspersoft.jrsupgrade.core.platform.ServiceController;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Service controller for tests: records every {@code stop} and {@code start}, flips the owning
 * {@link FakePlatform#serviceState} accordingly, and can be told to leave the state unchanged to
 * simulate a service that ignores the request.
 */
public final class FakeServiceController implements ServiceController {

  public final List<String> events = new ArrayList<>();
  public boolean stopFails;
  public boolean startFails;

  /** When set, {@code stop} leaves the service in this state (a stop that went wrong half-way). */
  public java.util.Optional<State> stopLeaves = java.util.Optional.empty();

  private final FakePlatform platform;
  private final String description;

  FakeServiceController(FakePlatform platform, String description) {
    this.platform = platform;
    this.description = description;
  }

  @Override
  public State state() {
    return platform.serviceState;
  }

  @Override
  public State stop(Duration timeout) {
    events.add("stop");
    if (stopLeaves.isPresent()) {
      platform.serviceState = stopLeaves.get();
    } else if (!stopFails) {
      platform.serviceState = State.STOPPED;
    }
    return platform.serviceState;
  }

  @Override
  public State start(Duration timeout) {
    events.add("start");
    if (!startFails) {
      platform.serviceState = State.RUNNING;
    }
    return platform.serviceState;
  }

  @Override
  public String describe() {
    return description;
  }
}
