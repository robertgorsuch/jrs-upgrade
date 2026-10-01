package com.jaspersoft.jrsupgrade.core.keys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jaspersoft.jrsupgrade.core.JrsUpgradeHome;
import com.jaspersoft.jrsupgrade.core.crypto.Ed25519;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeyRingTest {

  @TempDir Path tmp;

  @Test
  void should_verify_with_an_added_customer_key_when_signature_matches() {
    KeyRing ring = new KeyRing(new JrsUpgradeHome(tmp));
    KeyPair pair = Ed25519.generate();
    ring.add("acme-internal", pair.getPublic());
    byte[] data = "manifest".getBytes(StandardCharsets.UTF_8);
    byte[] sig = Ed25519.sign(pair.getPrivate(), data);

    assertThat(ring.verify(data, sig)).map(KeyRing.TrustedKey::name).contains("acme-internal");
    assertThat(ring.verify("other".getBytes(StandardCharsets.UTF_8), sig)).isEmpty();
  }

  @Test
  void should_round_trip_keys_through_base64_encodings() {
    KeyPair pair = Ed25519.generate();
    var pub = Ed25519.decodePublic(Ed25519.encodePublic(pair.getPublic()));
    var priv = Ed25519.decodePrivate(Ed25519.encodePrivate(pair.getPrivate()));
    byte[] data = {1, 2, 3};
    assertThat(Ed25519.verify(pub, data, Ed25519.sign(priv, data))).isTrue();
    assertThat(Ed25519.fingerprint(pub)).hasSize(16);
  }

  /**
   * The publisher key is provisioned (core/src/main/resources/keys/jaspersoft-publisher.pub): the
   * public half of the release signing key whose private half is the CI secret, fingerprint
   * recorded in docs/security.md. A fresh home therefore trusts exactly that key.
   */
  @Test
  void should_trust_the_bundled_publisher_key_in_a_fresh_home() {
    KeyRing ring = new KeyRing(new JrsUpgradeHome(tmp));

    assertThat(ring.list()).hasSize(1);
    KeyRing.TrustedKey publisher = ring.list().get(0);
    assertThat(publisher.name()).isEqualTo(KeyRing.PUBLISHER);
    assertThat(publisher.bundled()).isTrue();
    assertThat(Ed25519.fingerprint(publisher.key())).isEqualTo("245731f29b662027");
    assertThat(ring.find(KeyRing.PUBLISHER)).isPresent();
  }

  @Test
  void should_refuse_to_add_or_remove_the_publisher_key() {
    KeyRing ring = new KeyRing(new JrsUpgradeHome(tmp));
    KeyPair pair = Ed25519.generate();
    assertThatThrownBy(() -> ring.add(KeyRing.PUBLISHER, pair.getPublic()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ring.remove(KeyRing.PUBLISHER))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void should_refuse_to_remove_a_name_that_is_not_a_key_name() {
    KeyRing ring = new KeyRing(new JrsUpgradeHome(tmp));

    assertThatThrownBy(() -> ring.remove("../../foo"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("key name");
  }

  @Test
  void should_list_and_remove_customer_keys_when_present() {
    KeyRing ring = new KeyRing(new JrsUpgradeHome(tmp));
    ring.add("k1", Ed25519.generate().getPublic());
    ring.add("k2", Ed25519.generate().getPublic());
    assertThat(ring.list()).extracting(KeyRing.TrustedKey::name).contains("k1", "k2");
    assertThat(ring.remove("k1")).isTrue();
    assertThat(ring.find("k1")).isEmpty();
    assertThatThrownBy(() -> ring.add("bad name!", Ed25519.generate().getPublic()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
