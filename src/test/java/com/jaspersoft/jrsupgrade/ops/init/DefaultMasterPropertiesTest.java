package com.jaspersoft.jrsupgrade.ops.init;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.ops.FakeLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultMasterPropertiesTest {

  @TempDir Path tmp;

  @Test
  void should_strip_every_password_key_when_parsing() throws Exception {
    Path file = tmp.resolve("default_master.properties");
    Files.writeString(file, FakeLayout.DEFAULT_MASTER, StandardCharsets.UTF_8);

    DefaultMasterProperties props = DefaultMasterProperties.parse(file);

    assertThat(props.values().keySet())
        .noneMatch(k -> k.toLowerCase(java.util.Locale.ROOT).contains("pass"));
    assertThat(props.values().values()).doesNotContain("Sup3rSecret!", "AlsoSecret", "Never");
    assertThat(props.dbType()).contains("postgresql");
    assertThat(props.dbHost()).contains("db.example.internal");
    assertThat(props.dbPort()).contains("5433");
    assertThat(props.dbName()).contains("jasperserver");
    assertThat(props.dbUsername()).contains("jasperdb");
  }

  @Test
  void should_build_jdbc_url_and_map_type_when_values_present() throws Exception {
    Path file = tmp.resolve("dm.properties");
    Files.writeString(
        file, "dbType=sqlserver\ndbHost=sql01\njs.dbName=jrs\n", StandardCharsets.UTF_8);

    DefaultMasterProperties props = DefaultMasterProperties.parse(file);

    assertThat(props.databaseType()).contains(Config.DatabaseType.MSSQL);
    assertThat(props.jdbcUrl()).contains("jdbc:sqlserver://sql01:1433;databaseName=jrs");
  }

  @Test
  void should_return_empty_when_file_missing() throws Exception {
    DefaultMasterProperties props = DefaultMasterProperties.parse(tmp.resolve("nope"));
    assertThat(props.values()).isEmpty();
    assertThat(props.jdbcUrl()).isEmpty();
  }

  @Test
  void should_refuse_to_hold_a_password_key_when_constructed_directly() {
    assertThatThrownBy(() -> new DefaultMasterProperties(Map.of("dbPassword", "x")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void should_build_urls_for_every_database_type() {
    assertThat(DefaultMasterProperties.jdbcUrl(Config.DatabaseType.POSTGRESQL, "h", "1", "d"))
        .isEqualTo("jdbc:postgresql://h:1/d");
    assertThat(DefaultMasterProperties.jdbcUrl(Config.DatabaseType.MYSQL, "h", "1", "d"))
        .isEqualTo("jdbc:mysql://h:1/d");
    assertThat(DefaultMasterProperties.jdbcUrl(Config.DatabaseType.ORACLE, "h", "1", "d"))
        .isEqualTo("jdbc:oracle:thin:@h:1:d");
    assertThat(DefaultMasterProperties.jdbcUrl(Config.DatabaseType.DB2, "h", "1", "d"))
        .isEqualTo("jdbc:db2://h:1/d");
  }
}
