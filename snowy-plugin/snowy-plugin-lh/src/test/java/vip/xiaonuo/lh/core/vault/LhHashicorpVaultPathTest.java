package vip.xiaonuo.lh.core.vault;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HashiCorp KV v2 路径约定（与 {@link LhHashicorpVaultStore} 一致）。
 */
class LhHashicorpVaultPathTest {

    @Test
    void dataUrl_stripsSlashes() {
        assertEquals(
                "http://127.0.0.1:8200/v1/secret/data/platform/trino/query",
                kvDataUrl("http://127.0.0.1:8200/", "secret", "/platform/trino/query"));
    }

    /** 与实现内 dataUrl 同构，防回归。 */
    static String kvDataUrl(String addr, String mount, String vaultPath) {
        String a = addr.endsWith("/") ? addr.substring(0, addr.length() - 1) : addr;
        String path = vaultPath.startsWith("/") ? vaultPath.substring(1) : vaultPath.trim();
        return a + "/v1/" + mount + "/data/" + path;
    }
}
