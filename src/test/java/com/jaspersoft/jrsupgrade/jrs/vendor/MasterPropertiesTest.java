package com.jaspersoft.jrsupgrade.jrs.vendor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MasterPropertiesTest {

  private static final String ORIGINAL =
      "# vendor file\nappServerType=tomcat\ndbType=postgresql\ndbPassword=keep-me\n";

  @TempDir Path tmp;
  private Path buildomatic;
  private Path runDir;
  private Path file;

  @BeforeEach
  void setUp() throws IOException {
    buildomatic = tmp.resolve("buildomatic");
    Files.createDirectories(buildomatic);
    runDir = tmp.resolve("runs").resolve("r-1");
    file = buildomatic.resolve(Buildomatic.MASTER_PROPERTIES);
  }

  @Test
  void should_backup_append_overrides_and_restore_when_file_exists() throws IOException {
    Files.writeString(file, ORIGINAL, StandardCharsets.ISO_8859_1);

    MasterProperties.Staged staged =
        MasterProperties.stage(
            buildomatic, Map.of("dbHost", "db.example", "js.path", "C:\\jrs"), runDir);

    assertThat(staged.backup()).contains(MasterProperties.backupFor(runDir));
    assertThat(Files.readString(staged.backup().get(), StandardCharsets.ISO_8859_1))
        .isEqualTo(ORIGINAL);
    String content = Files.readString(file, StandardCharsets.ISO_8859_1);
    assertThat(content).startsWith(ORIGINAL).contains(MasterProperties.OVERRIDE_HEADER);
    Map<String, String> parsed = BuildomaticLocator.parseMasterProperties(file);
    assertThat(parsed)
        .containsEntry("dbHost", "db.example")
        .containsEntry("js.path", "C:\\jrs")
        .containsEntry("appServerType", "tomcat")
        .doesNotContainKey("dbPassword");
    assertThat(content).contains("dbPassword=keep-me");

    MasterProperties.restore(buildomatic, runDir);

    assertThat(Files.readString(file, StandardCharsets.ISO_8859_1)).isEqualTo(ORIGINAL);
    assertThat(Files.exists(MasterProperties.backupFor(runDir))).isFalse();
  }

  @Test
  void should_create_and_then_delete_file_when_no_previous_file() throws IOException {
    MasterProperties.Staged staged =
        MasterProperties.stage(buildomatic, Map.of("dbType", "mysql"), runDir);

    assertThat(staged.backup()).isEmpty();
    assertThat(Files.exists(MasterProperties.backupFor(runDir))).isFalse();
    assertThat(BuildomaticLocator.parseMasterProperties(file)).containsEntry("dbType", "mysql");

    MasterProperties.restore(buildomatic, runDir);

    assertThat(Files.exists(file)).isFalse();
  }

  @Test
  void should_keep_pristine_backup_when_staged_twice() throws IOException {
    Files.writeString(file, ORIGINAL, StandardCharsets.ISO_8859_1);

    MasterProperties.stage(buildomatic, Map.of("first", "1"), runDir);
    MasterProperties.stage(buildomatic, Map.of("second", "2"), runDir);

    assertThat(Files.readString(MasterProperties.backupFor(runDir), StandardCharsets.ISO_8859_1))
        .isEqualTo(ORIGINAL);
    Map<String, String> parsed = BuildomaticLocator.parseMasterProperties(file);
    assertThat(parsed).containsEntry("second", "2").doesNotContainKey("first");

    MasterProperties.restore(buildomatic, runDir);

    assertThat(Files.readString(file, StandardCharsets.ISO_8859_1)).isEqualTo(ORIGINAL);
  }

  @Test
  void should_leave_the_restored_original_alone_when_restored_twice() throws IOException {
    Files.writeString(file, ORIGINAL, StandardCharsets.ISO_8859_1);
    MasterProperties.stage(buildomatic, Map.of("dbHost", "h"), runDir);

    MasterProperties.restore(buildomatic, runDir);
    MasterProperties.restore(buildomatic, runDir);

    assertThat(Files.readString(file, StandardCharsets.ISO_8859_1)).isEqualTo(ORIGINAL);
    assertThat(MasterProperties.isStaged(file)).isFalse();
  }

  @Test
  void should_stay_absent_when_restored_twice_without_a_previous_file() throws IOException {
    MasterProperties.stage(buildomatic, Map.of("dbHost", "h"), runDir);
    assertThat(MasterProperties.isStaged(file)).isTrue();

    MasterProperties.restore(buildomatic, runDir);
    MasterProperties.restore(buildomatic, runDir);

    assertThat(Files.exists(file)).isFalse();
  }

  @Test
  void should_only_write_password_keys_when_caller_passes_them() throws IOException {
    MasterProperties.stage(buildomatic, Map.of("dbHost", "h"), runDir);
    String withoutPass = Files.readString(file, StandardCharsets.ISO_8859_1);
    MasterProperties.restore(buildomatic, runDir);
    MasterProperties.stage(buildomatic, Map.of("dbPassword", "explicit"), runDir);
    String withPass = Files.readString(file, StandardCharsets.ISO_8859_1);

    assertThat(withoutPass.toLowerCase(java.util.Locale.ROOT)).doesNotContain("pass");
    assertThat(withPass).contains("dbPassword=explicit");
    assertThat(MasterProperties.isPasswordKey("dbPassword")).isTrue();
    assertThat(MasterProperties.isPasswordKey("dbHost")).isFalse();
  }
}
