package vip.xiaonuo.lh.modular.compliance.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovDelCryptoShredSupportTest {

    @Test
    void dekPathStableAndSanitized() {
        String path = GovDelCryptoShredSupport.dekVaultPath(
                "AbC123", "prod.ods_user.dwd_user_info", "mobile_no");
        assertEquals("lh/compliance/dek/abc123/prod.ods_user.dwd_user_info/mobile_no", path);
    }

    @Test
    void wrapUnwrapRoundTrip() {
        byte[] dek = GovDelCryptoShredSupport.generateDek();
        String wrapped = GovDelCryptoShredSupport.wrapDek(dek, "test-kek-material-xyz");
        byte[] back = GovDelCryptoShredSupport.unwrapDek(wrapped, "test-kek-material-xyz");
        assertEquals(dek.length, back.length);
        for (int i = 0; i < dek.length; i++) {
            assertEquals(dek[i], back[i]);
        }
        assertFalse(GovDelCryptoShredSupport.fingerprint(wrapped).isBlank());
    }

    @Test
    void resolveColumnFromHashSuffix() {
        assertEquals("email", GovDelCryptoShredSupport.resolveColumn(
                "iceberg.dwd.user#email", null));
        assertEquals("phone", GovDelCryptoShredSupport.resolveColumn(
                "iceberg.dwd.user", "phone"));
        assertEquals("iceberg.dwd.user", GovDelCryptoShredSupport.stripColumnFromFqn(
                "iceberg.dwd.user#email"));
    }
}

class GovDelIntakeSignatureTest {

    @Test
    void signAndVerify() {
        String canon = GovDelIntakeSignature.canonical("user", "u-1", "fides", "DSR-9", "forget");
        String sig = GovDelIntakeSignature.signHex("secret-x", canon);
        assertTrue(GovDelIntakeSignature.verify("secret-x", canon, "sha256=" + sig));
        assertTrue(GovDelIntakeSignature.verify("secret-x", canon, sig));
        assertFalse(GovDelIntakeSignature.verify("secret-x", canon, "sha256=deadbeef"));
        assertFalse(GovDelIntakeSignature.verify("wrong", canon, sig));
    }

    @Test
    void timestampSkew() {
        long now = 1_700_000_000L;
        assertTrue(GovDelIntakeSignature.timestampOk(null, now, 300));
        assertTrue(GovDelIntakeSignature.timestampOk(String.valueOf(now), now, 300));
        assertTrue(GovDelIntakeSignature.timestampOk(String.valueOf(now * 1000L), now, 300));
        assertFalse(GovDelIntakeSignature.timestampOk(String.valueOf(now - 400), now, 300));
        assertFalse(GovDelIntakeSignature.timestampOk("not-a-number", now, 300));
    }
}

class GovDelObjectPurgeSupportTest {

    @Test
    void resolveSubstitutesHash() {
        var t = GovDelObjectPurgeSupport.resolve(
                "s3://lh-landing/pii/{subject_id_hash}/", "abcDEF");
        assertEquals("lh-landing", t.bucket());
        assertEquals("pii/abcdef/", t.prefix());
    }

    @Test
    void resolveAppendsHashWhenMissing() {
        var t = GovDelObjectPurgeSupport.resolve("minio://bucket/attachments", "hash99");
        assertEquals("bucket", t.bucket());
        assertEquals("attachments/hash99/", t.prefix());
    }

    @Test
    void rejectBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> GovDelObjectPurgeSupport.resolve("", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> GovDelObjectPurgeSupport.resolve("not-a-uri", "x"));
    }
}
