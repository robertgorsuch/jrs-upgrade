package com.jaspersoft.jrsupgrade.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Build-time identity of this jrs-upgrade binary. Invariant: {@link #current()} always returns a
 * non-empty version string; when the filtered resource is missing (IDE runs) it reports {@code
 * 0.0.0-dev} rather than failing, because version reporting must never block a diagnostic command.
 */
public final class Version {

  private static final String RESOURCE = "/jrs-upgrade-version.properties";
  private static final Version CURRENT = load();

  private final String version;
  private final String product;
  private final String vendor;

  private Version(String version, String product, String vendor) {
    this.version = version;
    this.product = product;
    this.vendor = vendor;
  }

  public static Version current() {
    return CURRENT;
  }

  public String version() {
    return version;
  }

  public String product() {
    return product;
  }

  public String vendor() {
    return vendor;
  }

  /** One-line banner used by {@code --version}. */
  public String banner() {
    return product + " " + version + " (" + vendor + ")";
  }

  private static Version load() {
    Properties p = new Properties();
    try (InputStream in = Version.class.getResourceAsStream(RESOURCE)) {
      if (in != null) {
        p.load(in);
      }
    } catch (IOException e) {
      // fall through to defaults; version reporting must not fail
    }
    String v = p.getProperty("version", "0.0.0-dev");
    if (v.startsWith("${")) {
      v = "0.0.0-dev";
    }
    return new Version(
        v, p.getProperty("product", "jrs-upgrade"), p.getProperty("vendor", "Jaspersoft"));
  }
}
