package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spark 提交口：生成 spark-submit / spark-sql 脚本（门户不直连 Yarn；由 DS Worker 执行）。
 */
@Component
public class SparkClient {

    @Resource
    private LhProperties lhProperties;

    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("component", "spark");
        String master = lhProperties.getSpark() != null ? lhProperties.getSpark().getMaster() : null;
        if (StrUtil.isBlank(master)) {
            m.put("status", "UNKNOWN");
            m.put("message", "lh.spark.master 未配置（Worker 侧执行）");
        } else {
            m.put("status", "CONFIGURED");
            m.put("master", master);
        }
        return m;
    }

    public Map<String, Object> previewSubmitScript(String nodeKey, String nodeType, String sql, Map<String, Object> conf) {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey(StrUtil.blankToDefault(nodeKey, "preview"));
        n.setNodeType(StrUtil.blankToDefault(nodeType, "transform"));
        var c = conf == null ? JSONUtil.createObj() : JSONUtil.parseObj(JSONUtil.toJsonStr(conf));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", "spark");
        out.put("master", lhProperties.getSpark().getMaster());
        out.put("script", SparkSubmitBuilder.buildShell(n, StrUtil.blankToDefault(sql, "SELECT 1"), c));
        return out;
    }
}
