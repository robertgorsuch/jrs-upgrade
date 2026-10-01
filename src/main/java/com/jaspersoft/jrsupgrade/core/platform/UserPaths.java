package com.jaspersoft.jrsupgrade.core.platform;

import java.util.Map;
import java.util.Objects;

/**
 * The one place a path typed by an operator is read the way a shell would read it (field test 2,
 * G3): a leading {@code ~} alone, or {@code ~/} and, on any host, {@code ~\}, means the operator's
 * home directory. Invariants: the home is {@code HOME} from the given environment, else {@code
 * USERPROFILE} (what Windows sets), else the JVM's {@code user.home}; {@code ~user} and a {@code ~}
 * anywhere but the start are left exactly as typed, since jrs-upgrade cannot look up other users
 * and a tilde inside a name is just a character; nothing else in the string changes, so an absolute
 * or relative path comes back as it went in.
 */
public final class UserPaths {

  private UserPaths() {}

  public static String expand(String raw, Map<String, String> env) {
    Objects.requireNonNull(raw, "raw");
    Objects.requireNonNull(env, "env");
    if (raw.equals("~")) {
      return home(env);
    }
    if (raw.startsWith("~/") || raw.startsWith("~\\")) {
      return home(env) + raw.substring(1);
    }
    return raw;
  }

  private static String home(Map<String, String> env) {
    String home = env.get("HOME");
    if (home == null || home.isBlank()) {
      home = env.get("USERPROFILE");
    }
    if (home == null || home.isBlank()) {
      home = System.getProperty("user.home", ".");
    }
    return home.strip();
  }
}
