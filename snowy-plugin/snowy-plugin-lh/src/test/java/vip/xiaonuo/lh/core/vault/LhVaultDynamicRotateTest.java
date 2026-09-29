package vip.xiaonuo.lh.core.vault;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LhVaultDynamicRotateTest {

    @Test
    void rotateMap_incrementsGenerationAndKeepsPreviousPassword() {
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("password", "old-secret");
        old.put("generation", 3);
        Map<String, Object> next = LhVaultDynamicRotate.rotateMap(old);
        assertEquals("old-secret", next.get("previousPassword"));
        assertNotEquals("old-secret", next.get("password"));
        assertTrue(String.valueOf(next.get("password")).length() >= 16);
        assertEquals(4, LhVaultDynamicRotate.generationOf(next));
        assertNotNull(next.get("rotatedAt"));
        assertEquals(LhVaultDynamicRotate.DEFAULT_LEASE_TTL_DAYS, next.get("leaseTtlDays"));
    }

    @Test
    void rotateMap_minioKeys() {
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("accessKey", "AKOLD");
        old.put("secretKey", "sk-old");
        old.put("generation", 1);
        Map<String, Object> next = LhVaultDynamicRotate.rotateMap(old);
        assertEquals("AKOLD", next.get("previousAccessKey"));
        assertEquals("sk-old", next.get("previousSecretKey"));
        assertNotEquals("sk-old", next.get("secretKey"));
        assertEquals(2, LhVaultDynamicRotate.generationOf(next));
    }

    @Test
    void rotateMap_emptySeedGetsPassword() {
        Map<String, Object> next = LhVaultDynamicRotate.rotateMap(new LinkedHashMap<>());
        assertNotNull(next.get("password"));
        assertEquals(1, LhVaultDynamicRotate.generationOf(next));
    }

    @Test
    void jobSaPath() {
        assertEquals("lh/job-sa/default/job.trade.ods_writer",
                LhVaultPaths.jobSa("default", "job.trade.ods_writer"));
        assertEquals("lh/job-sa/default/job.a.b", LhVaultPaths.jobSa("", "job.a.b"));
    }
}
