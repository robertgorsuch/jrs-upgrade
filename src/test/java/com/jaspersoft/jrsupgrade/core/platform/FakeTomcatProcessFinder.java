package com.jaspersoft.jrsupgrade.core.platform;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/** Replays a scripted sequence of process listings; the last listing repeats forever. */
final class FakeTomcatProcessFinder implements TomcatProcessFinder {

  private final Deque<List<TomcatProcess>> listings = new ArrayDeque<>();

  FakeTomcatProcessFinder(List<List<TomcatProcess>> sequence) {
    listings.addAll(sequence);
  }

  static TomcatProcess tomcatUnder(Path installDir) {
    Path home = installDir.resolve("apache-tomcat").toAbsolutePath().normalize();
    return new TomcatProcess(
        4242,
        "java -Dcatalina.home=\"" + home + "\" org.apache.catalina.startup.Bootstrap start",
        Optional.of(home),
        Optional.of(home),
        Optional.empty());
  }

  @Override
  public List<TomcatProcess> find() {
    if (listings.isEmpty()) {
      return List.of();
    }
    return listings.size() > 1 ? listings.poll() : listings.peek();
  }
}
