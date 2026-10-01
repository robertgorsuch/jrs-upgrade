package com.jaspersoft.jrsupgrade.jrs.rest;

import com.jaspersoft.jrsupgrade.jrs.api.ServerIdentity;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a {@code serverInfo} document into a {@link ServerIdentity} (spec §7.1). Invariants: the
 * version is normalised to {@code major.minor.patch} with build suffixes stripped and a missing
 * patch component set to 0; edition is {@code CE} or {@code PRO}, falling back to the edition name
 * when the short code is missing; tenancy is {@code MULTI} exactly when the space-separated feature
 * list contains {@code MT}.
 */
public final class ServerIdentities {

  private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private ServerIdentities() {}

  /** Parses the JSON body of {@code GET /rest_v2/serverInfo}. */
  public static ServerIdentity parse(URI baseUrl, String json) {
    Wire.ServerInfo info = Wire.parse(json, Wire.ServerInfo.class, "GET", "/rest_v2/serverInfo");
    return from(baseUrl, info);
  }

  static ServerIdentity from(URI baseUrl, Wire.ServerInfo info) {
    if (info.version() == null || info.version().isBlank()) {
      throw new RestException(
          0, "GET", "/rest_v2/serverInfo", "serverInfo response carries no version");
    }
    Set<String> features = features(info.features());
    ServerIdentity.Tenancy tenancy =
        features.contains("MT") ? ServerIdentity.Tenancy.MULTI : ServerIdentity.Tenancy.SINGLE;
    return new ServerIdentity(
        baseUrl,
        normaliseVersion(info.version()),
        edition(info.edition(), info.editionName()),
        tenancy,
        features,
        info.build() == null ? "" : info.build().strip(),
        info.dateFormatPattern() == null ? "" : info.dateFormatPattern());
  }

  /** {@code "8.2.0_build-123"} to {@code "8.2.0"}; {@code "9.0"} to {@code "9.0.0"}. */
  public static String normaliseVersion(String raw) {
    Matcher m = VERSION.matcher(raw);
    if (!m.find()) {
      throw new RestException(
          0, "GET", "/rest_v2/serverInfo", "unrecognised server version '" + raw.strip() + "'");
    }
    String patch = m.group(3) == null ? "0" : m.group(3);
    return Integer.parseInt(m.group(1))
        + "."
        + Integer.parseInt(m.group(2))
        + "."
        + Integer.parseInt(patch);
  }

  static Set<String> features(String raw) {
    Set<String> out = new LinkedHashSet<>();
    if (raw != null) {
      for (String f : WHITESPACE.split(raw.strip(), -1)) {
        if (!f.isEmpty()) {
          out.add(f);
        }
      }
    }
    return out;
  }

  static ServerIdentity.Edition edition(String code, String name) {
    String c = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
    if (c.equals("CE")) {
      return ServerIdentity.Edition.CE;
    }
    if (c.equals("PRO")) {
      return ServerIdentity.Edition.PRO;
    }
    String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
    if (n.contains("community")) {
      return ServerIdentity.Edition.CE;
    }
    if (n.contains("enterprise") || n.contains("professional") || n.contains("commercial")) {
      return ServerIdentity.Edition.PRO;
    }
    return ServerIdentity.Edition.UNKNOWN;
  }
}
