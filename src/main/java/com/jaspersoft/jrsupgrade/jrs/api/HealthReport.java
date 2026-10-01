package com.jaspersoft.jrsupgrade.jrs.api;

import java.time.Duration;
import java.util.List;

/** What {@code doctor} and {@code smoke} learn from a live server (spec §7.2, §12). */
public record HealthReport(boolean reachable, Duration latency, List<Item> items) {

  public enum Status {
    PASS,
    WARN,
    FAIL
  }

  public record Item(String name, Status status, String detail, String remediation) {}

  public HealthReport {
    items = List.copyOf(items);
  }

  public boolean ok() {
    return reachable && items.stream().noneMatch(i -> i.status() == Status.FAIL);
  }
}
