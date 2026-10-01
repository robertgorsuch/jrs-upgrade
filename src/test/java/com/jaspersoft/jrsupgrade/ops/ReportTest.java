package com.jaspersoft.jrsupgrade.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Field test 2, D2: the summary line names the items that failed or warned. */
class ReportTest {

  @Test
  void should_name_the_failing_and_warning_items_in_the_summary() {
    Report r =
        Report.of(
            List.of(
                ReportItem.pass("config", "ok"),
                ReportItem.fail("server", "down", "start it"),
                ReportItem.warn("disk", "low", "free some"),
                ReportItem.skip("auth", "no password", "set it")));

    assertThat(r.summary()).isEqualTo("1 fail (server), 1 warn (disk), 1 pass, 1 skip");
    assertThat(r.items().get(0).name()).isEqualTo("server");
  }

  @Test
  void should_keep_the_plain_counts_when_nothing_failed_or_warned() {
    Report r = Report.of(List.of(ReportItem.pass("config", "ok"), ReportItem.pass("disk", "ok")));

    assertThat(r.summary()).isEqualTo("0 fail, 0 warn, 2 pass, 0 skip");
    assertThat(r.counts().summary()).isEqualTo("2 pass 0 warn 0 fail 0 skip");
  }

  @Test
  void should_list_several_names_in_report_order() {
    Report r =
        Report.of(
            List.of(
                ReportItem.fail("b", "x", ""),
                ReportItem.fail("a", "y", ""),
                ReportItem.warn("w", "z", "")));

    assertThat(r.summary()).startsWith("2 fail (b, a), 1 warn (w)");
  }
}
