package vip.xiaonuo.lh.modular.compute.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import vip.xiaonuo.lh.config.LhProperties;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 环境隔离链路：Catalog/桶解析 + 开发态写入 lint + 本地评审分支。
 */
class CpEnvIsolationChainTest {

    @TempDir
    Path temp;

    private LhProperties props;
    private CpEnvIsolationSupport isolation;

    @BeforeEach
    void setUp() {
        props = new LhProperties();
        props.getCompute().setGitRoot(temp.toString());
        props.getCompute().getEnvIsolation().setEnabled(true);
        props.getCompute().getEnvIsolation().setDevCatalog("dev_iceberg");
        props.getCompute().getEnvIsolation().setStgCatalog("stg_iceberg");
        props.getCompute().getEnvIsolation().setDevBucket("lh-dev-warehouse");
        props.getCompute().getEnvIsolation().setStgBucket("lh-stg-warehouse");
        props.getCompute().getEnvIsolation().setProdBucket("warehouse");
        props.getGravitino().setCatalog("iceberg");

        isolation = new CpEnvIsolationSupport();
        ReflectionTestUtils.setField(isolation, "lhProperties", props);
    }

    @Test
    void catalogsAndBucketsArePhysicallySplit() {
        assertEquals("dev_iceberg", isolation.catalogFor("TEST"));
        assertEquals("stg_iceberg", isolation.catalogFor("PRE"));
        assertEquals("stg_iceberg", isolation.catalogFor("stg"));
        assertEquals("iceberg", isolation.catalogFor("PROD"));

        assertEquals("lh-dev-warehouse", isolation.bucketFor("TEST"));
        assertEquals("lh-stg-warehouse", isolation.bucketFor("PRE"));
        assertEquals("warehouse", isolation.bucketFor("PROD"));
        assertEquals("s3a://lh-dev-warehouse/", isolation.warehouseUri("TEST"));
        assertEquals("s3a://lh-stg-warehouse/", isolation.warehouseUri("stg"));
    }

    @Test
    void lintBlocksWriteIntoProdCatalog() {
        List<Map<String, String>> hit = isolation.lintWrites(
                "INSERT INTO iceberg.dwd.t SELECT 1", "TEST");
        assertTrue(hit.stream().anyMatch(i -> "error".equals(i.get("tone"))));

        List<Map<String, String>> ok = isolation.lintWrites(
                "INSERT INTO dev_iceberg.dwd.t SELECT 1 WHERE dt='x'", "TEST");
        assertTrue(ok.isEmpty() || ok.stream().noneMatch(i -> "error".equals(i.get("tone"))));
    }

    @Test
    void reviewBranchCreatedFromHead() {
        CpScriptGitStore store = new CpScriptGitStore(props);
        store.commit("default", "scripts/a.sql", "SELECT 1;\n", "init", "dev", "dev@local");
        String warn = store.createAndPushBranch("default", "review/rel-1");
        // 无 origin 时仅本地建分支，返回提示而非抛错
        assertTrue(warn == null || warn.contains("origin") || warn.contains("本地"));
        String baseWarn = store.ensureBaseBranch("default", "prod");
        assertTrue(baseWarn == null || baseWarn.contains("origin") || baseWarn.contains("本地"));
    }

    @Test
    void fullLintChainStillBlocksBareProdLayer() {
        boolean forbid = props.getCompute().isForbidProdLayerWrite();
        assertTrue(forbid);
        List<Map<String, String>> lint = CpScriptLint.check(
                "INSERT INTO dwd_order SELECT 1", "TEST", true);
        lint = new java.util.ArrayList<>(lint);
        lint.addAll(isolation.lintWrites("INSERT INTO dwd_order SELECT 1", "TEST"));
        assertTrue(CpScriptLint.hasError(lint));
        assertFalse(CpScriptLint.hasError(CpScriptLint.check(
                "INSERT INTO stg_iceberg.dwd.t SELECT 1 WHERE 1=1", "PRE", true)));
    }
}
