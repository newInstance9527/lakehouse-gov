package vip.xiaonuo.lh.modular.etl.support;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.DsTaskScriptBuilder;
import vip.xiaonuo.lh.core.engine.TrinoClient;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlRunNode;
import vip.xiaonuo.lh.modular.etl.mapper.IgEtlRunNodeMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DS 不可达时的试跑兜底：对可 SQL 化节点用门户 TrinoClient 执行。
 */
@Slf4j
@Component
public class IgEtlLocalTrialExecutor {

    @Resource
    private LhProperties lhProperties;
    @Resource
    private TrinoClient trinoClient;
    @Resource
    private IgEtlRunNodeMapper runNodeMapper;

    public Map<String, Object> execute(String runId, List<IgEtlNode> nodes, List<Map<String, Object>> plan) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "local_trino");
        if (!lhProperties.getEtl().isLocalTrialFallback()) {
            out.put("skipped", true);
            out.put("message", "lh.etl.local-trial-fallback=false");
            return out;
        }
        if (StrUtil.isBlank(lhProperties.getTrino().getUrl())) {
            out.put("skipped", true);
            out.put("message", "lh.trino.url 未配置");
            return out;
        }

        int ok = 0;
        int fail = 0;
        int skip = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        Map<String, String> engineByKey = new LinkedHashMap<>();
        if (plan != null) {
            for (Map<String, Object> p : plan) {
                if (p.get("nodeKey") != null) {
                    engineByKey.put(String.valueOf(p.get("nodeKey")), String.valueOf(p.get("engine")));
                }
            }
        }

        for (IgEtlNode n : nodes) {
            String engine = engineByKey.getOrDefault(n.getNodeKey(),
                    StrUtil.blankToDefault(n.getResolvedEngine(), "ds_sql"));
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("nodeKey", n.getNodeKey());
            d.put("nodeType", n.getNodeType());
            d.put("engine", engine);

            if (!canLocalExecute(n.getNodeType(), engine)) {
                skip++;
                d.put("status", "skipped");
                d.put("message", "非本地可执行引擎/类型");
                details.add(d);
                continue;
            }

            String sql = DsTaskScriptBuilder.resolveSql(n);
            d.put("sql", sql);
            try {
                Map<String, Object> exec = trinoClient.execute(sql);
                boolean degraded = Boolean.TRUE.equals(exec.get("degraded"));
                if (degraded) {
                    fail++;
                    d.put("status", "failed");
                    d.put("message", String.valueOf(exec.getOrDefault("rows", exec)));
                    updateRunNode(runId, n.getNodeKey(), "failed", "local-trino degraded");
                } else {
                    ok++;
                    d.put("status", "success");
                    d.put("rowCount", exec.get("rowCount"));
                    updateRunNode(runId, n.getNodeKey(), "success",
                            "local-trino rows=" + exec.getOrDefault("rowCount", 0));
                }
            } catch (Exception e) {
                fail++;
                d.put("status", "failed");
                d.put("message", e.getMessage());
                updateRunNode(runId, n.getNodeKey(), "failed", e.getMessage());
                log.warn("local trial node {} failed: {}", n.getNodeKey(), e.getMessage());
            }
            details.add(d);
        }

        out.put("skipped", false);
        out.put("okCount", ok);
        out.put("failCount", fail);
        out.put("skipCount", skip);
        out.put("nodes", details);
        out.put("success", fail == 0 && ok > 0);
        out.put("partial", fail > 0 && ok > 0);
        return out;
    }

  private boolean canLocalExecute(String nodeType, String engine) {
        // Flink / Spark / DataX 必须走真实引擎；禁止用门户 Trino 假跑误报成功/失败
        if ("flink".equalsIgnoreCase(engine) || "spark".equalsIgnoreCase(engine)
                || "datax".equalsIgnoreCase(engine)) {
            return false;
        }
        if ("parallel".equals(nodeType) || "condition".equals(nodeType) || "union".equals(nodeType)
                || "clean".equals(nodeType)) {
            return false;
        }
        // 仅 ds_sql / 未指定重引擎时的 SQL 化节点
        if ("source".equals(nodeType) || StrUtil.startWith(nodeType, "source_")
                || StrUtil.startWith(nodeType, "sink_")
                || "transform".equals(nodeType) || "mapping".equals(nodeType)
                || "quality".equals(nodeType) || "ds_sql".equalsIgnoreCase(engine)) {
            return true;
        }
        return "ds_sql".equalsIgnoreCase(engine);
    }

    private void updateRunNode(String runId, String nodeKey, String status, String message) {
        IgEtlRunNode rn = runNodeMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<IgEtlRunNode>()
                .lambda().eq(IgEtlRunNode::getRunId, runId).eq(IgEtlRunNode::getNodeKey, nodeKey).last("LIMIT 1"));
        if (rn == null) {
            return;
        }
        rn.setStatus(status);
        rn.setMessage(StrUtil.maxLength(message, 500));
        if ("success".equals(status) || "failed".equals(status) || "blocked".equals(status)) {
            rn.setFinishedAt(new Date());
        }
        runNodeMapper.updateById(rn);
    }
}
