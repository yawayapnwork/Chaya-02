package dev.chaya.api.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The object-key tenancy rules and read-time verification, without storage (StoragePolicyTest covers the accounts). */
class TenantKeysTest {

    final UUID org = UUID.randomUUID();
    final UUID venue = UUID.randomUUID();
    final String own = "org/" + org + "/venue/" + venue + "/";

    @Test
    void aKeyBelongsOnlyToItsOwnVenuesPrefixes() {
        assertThat(TenantKeys.belongsTo(own + "scan/s/out.bin", org, venue)).isTrue();
        assertThat(TenantKeys.belongsTo("sealed/" + own + "scan/s/n/out.bin", org, venue)).isTrue();
        // another venue of the same organization, another organization, and the bare prefixes
        assertThat(TenantKeys.belongsTo("org/" + org + "/venue/" + UUID.randomUUID() + "/x", org, venue)).isFalse();
        assertThat(TenantKeys.belongsTo("org/" + UUID.randomUUID() + "/venue/" + venue + "/x", org, venue)).isFalse();
        assertThat(TenantKeys.belongsTo(own, org, venue)).isFalse();
        assertThat(TenantKeys.belongsTo("sealed/" + own, org, venue)).isFalse();
        // path tricks
        for (String k : new String[] {own + "../../other/x", own + "a//b", "/" + own + "x", own + "a\\b", "x/" + own + "y", null}) {
            assertThat(TenantKeys.belongsTo(k, org, venue)).as(String.valueOf(k)).isFalse();
        }
        assertThatThrownBy(() -> TenantKeys.require("org/other/x", org, venue)).isInstanceOf(TenantKeys.TenantKeyViolation.class);
    }

    @Test
    void aSealedKeyKeepsTheTenantThePiiSegmentAndTheFileNameAndIsUniquePerRegistration() {
        String worker = own + "scan/s/run/r/FFMPEG_PREPROCESS/attempt-1/pii/frame-000001.png";
        UUID n1 = UUID.randomUUID();
        String sealed = TenantKeys.sealed(worker, org, venue, n1);
        assertThat(sealed).isEqualTo("sealed/" + own + "scan/s/run/r/FFMPEG_PREPROCESS/attempt-1/pii/" + n1 + "/frame-000001.png");
        assertThat(TenantKeys.isSealed(sealed)).isTrue();
        assertThat(TenantKeys.belongsTo(sealed, org, venue)).isTrue();
        assertThat(TenantKeys.sealed(worker, org, venue, UUID.randomUUID())).isNotEqualTo(sealed);
        // only a worker key of this tenant can be sealed for it, and a sealed key is never sealed again
        assertThatThrownBy(() -> TenantKeys.sealed("org/" + UUID.randomUUID() + "/venue/" + venue + "/x", org, venue, n1))
            .isInstanceOf(TenantKeys.TenantKeyViolation.class);
        assertThatThrownBy(() -> TenantKeys.sealed(sealed, org, venue, n1)).isInstanceOf(TenantKeys.TenantKeyViolation.class);
    }

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    @Test
    void aVerifiedStreamPassesTheRegisteredBytesAndFailsOnAnyOther() throws Exception {
        byte[] genuine = "ksplat-bytes".getBytes(StandardCharsets.UTF_8);
        try (InputStream in = new VerifyingInputStream(new ByteArrayInputStream(genuine), sha(genuine), "k")) {
            assertThat(in.readAllBytes()).isEqualTo(genuine);
        }
        byte[] forged = "ksplat-bytez".getBytes(StandardCharsets.UTF_8);
        InputStream bad = new VerifyingInputStream(new ByteArrayInputStream(forged), sha(genuine), "k");
        assertThatThrownBy(bad::readAllBytes).isInstanceOf(VerifyingInputStream.IntegrityException.class).hasMessageContaining("k");
        // single-byte reads too, and a truncated object
        InputStream truncated = new VerifyingInputStream(new ByteArrayInputStream(genuine, 0, 5), sha(genuine), "k");
        assertThatThrownBy(() -> { while (truncated.read() >= 0) { } }).isInstanceOf(VerifyingInputStream.IntegrityException.class);
        // there is no unverified way through
        assertThatThrownBy(() -> new VerifyingInputStream(new ByteArrayInputStream(genuine), sha(genuine), "k").skip(3))
            .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> new VerifyingInputStream(new ByteArrayInputStream(genuine), null, "k"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
