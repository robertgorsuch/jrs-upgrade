package com.jaspersoft.jrsupgrade.jrs.vendor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #39: the cache profile jrs-upgrade passes to the vendor tools is read from the buildomatic
 * tree the tools run from, in the order buildomatic itself resolves configuration.
 */
class BuildomaticCacheProviderTest {

  @TempDir Path tmp;

  private static Buildomatic at(Path dir) {
    return new Buildomatic(dir, Map.of(), Optional.empty(), Map.of());
  }

  private static void write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  @Test
  void should_read_cache_provider_from_default_build_conf_when_present() throws IOException {
    write(tmp.resolve("build_conf/default/cache.properties"), "js.cache.provider=ehcache\n");
    write(tmp.resolve("conf_source/iePro/cache.properties"), "js.cache.provider=infinispan\n");

    assertThat(at(tmp).cacheProvider()).isEqualTo("ehcache");
  }

  @Test
  void should_fall_back_to_conf_source_when_default_build_conf_is_missing() throws IOException {
    write(tmp.resolve("conf_source/iePro/cache.properties"), "js.cache.provider = ehcache \n");

    assertThat(at(tmp).cacheProvider()).isEqualTo("ehcache");
  }

  @Test
  void should_default_to_infinispan_when_no_cache_properties_exist() {
    assertThat(at(tmp.resolve("empty")).cacheProvider()).isEqualTo("infinispan");
  }

  @Test
  void should_ignore_a_value_that_is_not_a_plain_profile_name_when_reading() throws IOException {
    write(
        tmp.resolve("build_conf/default/cache.properties"),
        "js.cache.provider=ehcache -Dsomething=else\n");

    assertThat(at(tmp).cacheProvider()).isEqualTo("infinispan");
  }
}
