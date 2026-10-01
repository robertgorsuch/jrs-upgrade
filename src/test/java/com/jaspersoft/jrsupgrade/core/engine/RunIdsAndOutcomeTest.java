package com.jaspersoft.jrsupgrade.core.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RunIdsAndOutcomeTest {

  @Test
  void should_format_run_id_with_utc_timestamp_and_hex_suffix_when_generated() {
    Clock clock = Clock.fixed(Instant.parse("2026-09-08T10:15:00Z"), ZoneOffset.UTC);

    String id = RunIds.next(clock);

    assertThat(id).matches("r-20260908-101500-[0-9a-f]{4}");
  }

  @Test
  void should_produce_distinct_ids_when_called_repeatedly_in_the_same_second() {
    Clock clock = Clock.fixed(Instant.parse("2026-09-08T10:15:00Z"), ZoneOffset.UTC);
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < 200; i++) {
      ids.add(RunIds.next(clock));
    }
    assertThat(ids.size()).isGreaterThan(150);
  }

  @Test
  void should_map_outcomes_to_spec_exit_codes_when_asked() {
    assertThat(new RunOutcome.Succeeded().exitCode()).isEqualTo(0);
    assertThat(new RunOutcome.RolledBack("apply", "x").exitCode()).isEqualTo(3);
    assertThat(new RunOutcome.Failed("x", true, "y", List.of()).exitCode()).isEqualTo(4);
    assertThat(new RunOutcome.Failed("x", false, "y", List.of()).exitCode()).isEqualTo(2);
    assertThat(new RunOutcome.Cancelled("x").exitCode()).isEqualTo(5);
    assertThat(new RunOutcome.PrecheckFailed("s1", "x", "y").exitCode()).isEqualTo(2);
    assertThat(new RunOutcome.FingerprintMismatch(List.of("bundle")).exitCode()).isEqualTo(2);
  }
}
