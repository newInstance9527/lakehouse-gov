package vip.xiaonuo.lh.core.engine;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.Test;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DsTaskScriptBuilderQualityShellTest {

    @Test
    void emptyGovUrlFailClosedWhenBlockOnFail() {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey("dq1");
        JSONObject conf = JSONUtil.createObj()
                .set("ruleIds", "r1,r2")
                .set("blockOnFail", true);
        String shell = DsTaskScriptBuilder.qualityGateShell(n, conf);
        assertTrue(shell.contains("LH_GOV_URL empty; fail-closed"));
        assertTrue(shell.contains("exit 1"));
        assertTrue(shell.contains("blockOnFail=$BLOCK_ON_FAIL"));
    }

    @Test
    void emptyGovUrlSkipWhenNotBlocking() {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey("dq2");
        JSONObject conf = JSONUtil.createObj()
                .set("ruleIds", "r1")
                .set("blockOnFail", false);
        String shell = DsTaskScriptBuilder.qualityGateShell(n, conf);
        assertTrue(shell.contains("BLOCK_ON_FAIL=false"));
        assertTrue(shell.contains("skip evaluate (blockOnFail=false)"));
    }
}
