package vip.xiaonuo.lh.modular.compute.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.xiaonuo.lh.config.LhProperties;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpScriptSupportTest {

    @TempDir
    Path temp;

    @Test
    void lintBlocksProdAndWarnsFullScan() {
        assertTrue(CpScriptLint.hasError(CpScriptLint.check("INSERT INTO prod_dwd.t SELECT 1")));
        assertFalse(CpScriptLint.hasError(CpScriptLint.check("SELECT * FROM dev_dwd.t")));
        assertEquals("warn", CpScriptLint.check("SELECT * FROM dev_dwd.t").get(0).get("tone"));
        assertEquals("ok", CpScriptLint.check("SELECT * FROM dev_dwd.t WHERE dt = '2026-01-01'").get(0).get("tone"));
        assertThrows(IllegalArgumentException.class, () -> CpScriptLint.normalizeEnv("PROD"));
    }

    @Test
    void gitRoundTripDoesNotStoreOnlyInMemory() {
        LhProperties props = new LhProperties();
        props.getCompute().setGitRoot(temp.toString());
        CpScriptGitStore store = new CpScriptGitStore(props);
        String sha = store.commit("default", "scripts/trade/demo.sql", "SELECT 1;\n", "save", "dev", "dev@local");
        assertEquals("SELECT 1;\n", store.read("default", "scripts/trade/demo.sql"));
        String again = store.commit("default", "scripts/trade/demo.sql", "SELECT 1;\n", "save", "dev", "dev@local");
        assertEquals(sha, again);
        String tag = store.tag("default", "rel-1", "publish");
        store.commit("default", "scripts/trade/demo.sql", "SELECT 2;\n", "save2", "dev", "dev@local");
        assertEquals("SELECT 1;\n", store.readAt("default", "scripts/trade/demo.sql", tag));
        assertEquals(tag, store.previousTag("default", null));
    }
}
