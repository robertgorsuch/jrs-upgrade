package com.jaspersoft.jrsupgrade.ops.init;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.platform.TomcatLayout;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@code init} detected (spec §12.0): the configuration it proposes and, for the operator's
 * confirmation, every value with where it came from. Invariants: {@code values} lists only keys
 * present in {@code config}; a source is human-readable ({@code "detected from conf/server.xml"},
 * {@code "from default_master.properties"}, {@code "default"}); password references appear as
 * placeholders, never as values; {@code candidates} is every installation the platform search
 * found, ranked so the first is the recommended one (field test 3), and is empty when {@code
 * --install-dir} named the installation; at most one candidate is {@link Candidate#chosen()}, the
 * one {@code config} describes; {@code notes} are sentences the operator should read about the
 * search itself, such as what the process scan could not see.
 */
public record InitReport(
    Config config, List<Detected> values, List<Candidate> candidates, List<String> notes) {

  /** One detected configuration key with its provenance. */
  public record Detected(String key, String value, String source) {
    public Detected {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(value, "value");
      Objects.requireNonNull(source, "source");
    }
  }

  /**
   * An installation found on this machine: its layout, the version its files state when they state
   * one, whether a running Tomcat pointed at it, and whether it is the one this report proposes.
   */
  public record Candidate(
      TomcatLayout layout, Optional<String> version, boolean running, boolean chosen) {
    public Candidate {
      Objects.requireNonNull(layout, "layout");
      Objects.requireNonNull(version, "version");
    }

    /** {@code commercial} for {@code jasperserver-pro}, else {@code community}. */
    public String edition() {
      return layout.webappName().equals(Config.WebappName.JASPERSERVER_PRO.yamlValue())
          ? "commercial"
          : "community";
    }

    Candidate withChosen(boolean value) {
      return new Candidate(layout, version, running, value);
    }
  }

  public InitReport {
    Objects.requireNonNull(config, "config");
    values = List.copyOf(values);
    candidates = List.copyOf(candidates);
    notes = List.copyOf(notes);
  }

  /** A report with no candidate list and no notes: an explicit or remote installation. */
  public InitReport(Config config, List<Detected> values) {
    this(config, values, List.of(), List.of());
  }

  /** True when a Tomcat layout was found; otherwise the report holds defaults only. */
  public boolean detectedInstall() {
    return config.server().installDir().isPresent();
  }
}
