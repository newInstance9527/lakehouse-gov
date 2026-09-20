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
 * DataX 提交口：生成 job.json + lh-datax Shell（由 DS Worker / JDK8 容器执行）。
 */
@Component
public class DataxClient {

    @Resource
    private LhProperties lhProperties;

    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("component", "datax");
        String home = lhProperties.getDatax() != null ? lhProperties.getDatax().getHome() : null;
        m.put("home", home);
        m.put("status", StrUtil.isNotBlank(home) ? "CONFIGURED" : "UNKNOWN");
        return m;
    }

    public Map<String, Object> previewJob(String nodeKey, String nodeType, Map<String, Object> conf) {
        IgEtlNode n = new IgEtlNode();
        n.setNodeKey(StrUtil.blankToDefault(nodeKey, "preview"));
        n.setNodeType(StrUtil.blankToDefault(nodeType, "source_rdb"));
        var c = conf == null ? JSONUtil.createObj() : JSONUtil.parseObj(JSONUtil.toJsonStr(conf));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", "datax");
        out.put("home", lhProperties.getDatax().getHome());
        out.put("job", DataxJobBuilder.buildJob(n, c));
        out.put("script", DataxJobBuilder.buildShell(n, c));
        return out;
    }
}
