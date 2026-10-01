package com.jaspersoft.jrsupgrade.jrs.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.config.Config;
import com.jaspersoft.jrsupgrade.core.platform.Platform;
import com.jaspersoft.jrsupgrade.core.redact.Redactor;
import com.jaspersoft.jrsupgrade.core.secrets.SecretRef;
import com.jaspersoft.jrsupgrade.jrs.FakeJrsAdapter;
import com.jaspersoft.jrsupgrade.jrs.FakePlatform;
import com.jaspersoft.jrsupgrade.jrs.TestConfigs;
import com.jaspersoft.jrsupgrade.jrs.api.Capability;
import com.jaspersoft.jrsupgrade.jrs.api.ExportImportStrategy;
import com.jaspersoft.jrsupgrade.jrs.api.ExportRequest;
import com.jaspersoft.jrsupgrade.jrs.api.ImportRequest;
import com.jaspersoft.jrsupgrade.jrs.api.JrsUnreachableException;
import com.jaspersoft.jrsupgrade.jrs.rest.RestException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StrategiesTest {

  private final Config config = TestConfigs.server(StrategyFixture.BASE, Config.AuthMode.BASIC);
  private final Strategies strategies =
      Strategies.standard(new FakePlatform(Platform.OsFamily.LINUX), new Redactor());

  private static ExportRequest export(boolean fullServer) {
    return new ExportRequest(
        ExportRequest.Scope.REPOSITORY,
        Set.of("/public"),
        false,
        false,
        false,
        false,
        false,
        fullServer,
        Path.of("x.zip"));
  }

  private static ImportRequest importRequest() {
    return importRequest(Optional.empty());
  }

  private static ImportRequest importRequest(Optional<Path> sourceKeystore) {
    return new ImportRequest(
        Path.of("x.zip"),
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        sourceKeystore,
        sourceKeystore.map(k -> new SecretRef.Env("KS_PASS")));
  }

  @Test
  void should_propagate_an_authentication_failure_instead_of_selecting_vendor() {
    FakeJrsAdapter refused =
        new FakeJrsAdapter()
            .failingProbe(
                new RestException(
                    401,
                    "GET",
                    "/rest_v2/export/jrs-upgrade-probe/state",
                    "GET /rest_v2/export/jrs-upgrade-probe/state answered HTTP 401"));

    assertThatThrownBy(() -> strategies.select(config, refused, export(false), Optional.empty()))
        .as("a wrong password must never turn a REST import into a service stop")
        .isInstanceOf(RestException.class)
        .satisfies(e -> assertThat(((RestException) e).authenticationFailure()).isTrue());
    assertThatThrownBy(() -> strategies.select(config, refused, importRequest(), Optional.empty()))
        .isInstanceOf(RestException.class);
  }

  @Test
  void should_still_select_vendor_when_the_probe_fails_for_another_reason() {
    FakeJrsAdapter broken =
        new FakeJrsAdapter()
            .failingProbe(new RestException(503, "GET", "/rest_v2/organizations", "HTTP 503"));

    Strategies.Selection s = strategies.select(config, broken, export(false), Optional.empty());

    assertThat(s.kind()).isEqualTo(ExportImportStrategy.Kind.VENDOR_CLI);
    assertThat(s.reason()).contains("probe failed");
  }

  @Test
  void should_select_vendor_when_an_import_brings_a_source_keystore() {
    FakeJrsAdapter fine =
        new FakeJrsAdapter()
            .withCapabilities(Set.of(Capability.EXPORT_ASYNC, Capability.IMPORT_ASYNC));
    ImportRequest withKeystore = importRequest(Optional.of(Path.of("source.jrsks")));

    Strategies.Selection chosen = strategies.select(config, fine, withKeystore, Optional.empty());
    Strategies.Selection forcedRest =
        strategies.select(config, fine, withKeystore, Optional.of(ExportImportStrategy.Kind.REST));

    assertThat(chosen.kind()).isEqualTo(ExportImportStrategy.Kind.VENDOR_CLI);
    assertThat(chosen.reason()).contains("source keystore").contains("service stopped");
    assertThat(forcedRest.kind())
        .as("the keystore swap needs the server down whatever --strategy says")
        .isEqualTo(ExportImportStrategy.Kind.VENDOR_CLI);
    assertThat(forcedRest.reason()).contains("--strategy rest");
    assertThat(strategies.select(config, fine, importRequest(), Optional.empty()).kind())
        .as("without a keystore the REST path is unchanged")
        .isEqualTo(ExportImportStrategy.Kind.REST);
  }

  @Test
  void should_refuse_rest_import_steps_when_a_source_keystore_is_present() {
    assertThatThrownBy(
            () -> new RestStrategy().importSteps(importRequest(Optional.of(Path.of("k.jrsks")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("source keystore");
  }

  @Test
  void should_select_rest_when_probe_passes_and_not_full_server() {
    Strategies.Selection s =
        strategies.select(config, new FakeJrsAdapter(), export(false), Optional.empty());

    assertThat(s.kind()).isEqualTo(ExportImportStrategy.Kind.REST);
    assertThat(s.strategy().requiresServiceStop()).isFalse();
    assertThat(s.reason()).contains("EXPORT_ASYNC");
  }

  @Test
  void should_select_vendor_when_full_server_even_if_probe_passes() {
    Strategies.Selection s =
        strategies.select(config, new FakeJrsAdapter(), export(true), Optional.empty());

    assertThat(s.kind()).isEqualTo(ExportImportStrategy.Kind.VENDOR_CLI);
    assertThat(s.strategy().requiresServiceStop()).isTrue();
    assertThat(s.reason()).contains("full-server");
  }

  @Test
  void should_select_vendor_when_probe_fails() {
    FakeJrsAdapter noAsync = new FakeJrsAdapter().withCapabilities(Set.of(Capability.ORGS));

    Strategies.Selection export =
        strategies.select(config, noAsync, export(false), Optional.empty());
    Strategies.Selection imp =
        strategies.select(config, noAsync, importRequest(), Optional.empty());

    assertThat(export.kind()).isEqualTo(ExportImportStrategy.Kind.VENDOR_CLI);
    assertThat(export.reason()).contains("EXPORT_ASYNC probe failed");
    assertThat(imp.kind()).isEqualTo(ExportImportStrategy.Kind.VENDOR_CLI);
    assertThat(imp.reason()).contains("IMPORT_ASYNC probe failed");
  }

  @Test
  void should_select_vendor_when_server_unreachable_during_probe() {
    FakeJrsAdapter down =
        new FakeJrsAdapter()
            .failingProbe(
                new JrsUnreachableException(
                    URI.create("http://x"), "connection refused", "start the server", null));

    Strategies.Selection s = strategies.select(config, down, export(false), Optional.empty());

    assertThat(s.kind()).isEqualTo(ExportImportStrategy.Kind.VENDOR_CLI);
    assertThat(s.reason()).contains("probe failed").contains("connection refused");
  }

  @Test
  void should_honour_forced_kind_when_given() {
    FakeJrsAdapter adapter = new FakeJrsAdapter();

    Strategies.Selection vendor =
        strategies.select(
            config, adapter, export(false), Optional.of(ExportImportStrategy.Kind.VENDOR_CLI));
    Strategies.Selection rest =
        strategies.select(
            config, adapter, importRequest(), Optional.of(ExportImportStrategy.Kind.REST));

    assertThat(vendor.kind()).isEqualTo(ExportImportStrategy.Kind.VENDOR_CLI);
    assertThat(vendor.reason()).contains("forced");
    assertThat(rest.kind()).isEqualTo(ExportImportStrategy.Kind.REST);
    assertThat(rest.reason()).contains("forced");
  }

  @Test
  void should_select_rest_for_import_when_import_probe_passes() {
    Strategies.Selection s =
        strategies.select(config, new FakeJrsAdapter(), importRequest(), Optional.empty());

    assertThat(s.kind()).isEqualTo(ExportImportStrategy.Kind.REST);
  }
}
