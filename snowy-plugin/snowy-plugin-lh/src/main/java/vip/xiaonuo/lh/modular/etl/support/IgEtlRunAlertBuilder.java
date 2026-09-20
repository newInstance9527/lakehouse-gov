package vip.xiaonuo.lh.modular.etl.support;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlDag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 失败告警载荷：带 run_id，门户可跳转运维 /ops?runId=
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Component
public class IgEtlRunAlertBuilder {

    /**
     * @param severity P0/P1/P2
     */
    public Map<String, Object> build(IgEtlDag dag, String runId, String status, String message, String severity) {
        Map<String, Object> a = new LinkedHashMap<>();
        String rid = StrUtil.blankToDefault(runId, "");
        String dagId = dag == null ? "" : StrUtil.blankToDefault(dag.getId(), "");
        String dagCode = dag == null ? "" : StrUtil.blankToDefault(dag.getDagCode(), dagId);
        String sev = StrUtil.blankToDefault(severity, "P1");
        String opsPath = "/ops?runId=" + enc(rid);
        if (StrUtil.isNotBlank(dagId)) {
            opsPath += "&dagId=" + enc(dagId);
        }
        a.put("channel", "nightingale");
        a.put("severity", sev);
        a.put("runId", rid);
        a.put("dagId", dagId);
        a.put("dagCode", dagCode);
        a.put("owner", dag == null ? null : dag.getOwner());
        a.put("status", status);
        a.put("title", "ETL 运行失败 · " + dagCode);
        a.put("message", StrUtil.blankToDefault(message, "run failed"));
        a.put("opsPath", opsPath);
        a.put("opsHint", "在任务运维中心按 run_id 打开该次执行");
        // soft：本期登记载荷；真实推夜莺 webhook 后续接
        a.put("pushed", false);
        a.put("degraded", true);
        return a;
    }

    private static String enc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace(" ", "%20").replace("&", "%26");
    }
}
