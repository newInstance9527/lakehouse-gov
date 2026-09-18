package vip.xiaonuo.lh.core.engine;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class EngineResolver {

    @Resource
    private LhProperties lhProperties;

    public static final List<String> NODE_TYPES = List.of(
            "source", "source_api", "source_file",
            "clean", "transform", "mapping",
            "quality", "parallel", "condition", "union",
            "sink_iceberg", "sink_ck", "sink_kafka", "sink_bi"
    );

    public Map<String, Object> resolve(String nodeType, String cfgJson) {
        JSONObject cfg = StrUtil.isBlank(cfgJson) ? JSONUtil.createObj() : JSONUtil.parseObj(cfgJson);
        String engine;
        String rule;
        if (StrUtil.isNotBlank(cfg.getStr("engine_override"))) {
            if (StrUtil.isBlank(cfg.getStr("override_reason"))) {
                throw new CommonException("override_reason required");
            }
            engine = cfg.getStr("engine_override");
            rule = "manual_override";
        } else if (StrUtil.isNotBlank(cfg.getStr("engine"))) {
            engine = cfg.getStr("engine");
            rule = "explicit_engine";
        } else if ("cdc".equalsIgnoreCase(cfg.getStr("mode")) || "cdc".equalsIgnoreCase(cfg.getStr("source_mode"))) {
            engine = "flink";
            rule = "cdc_to_flink";
        } else if ("heavy_batch".equalsIgnoreCase(cfg.getStr("hint"))) {
            engine = "spark";
            rule = "heavy_batch_to_spark";
        } else if ("heterogeneous_sync".equalsIgnoreCase(cfg.getStr("pattern"))) {
            engine = "datax";
            rule = "hetero_to_datax";
        } else if ("transform".equals(nodeType) && StrUtil.isNotBlank(cfg.getStr("sql")) && isSimpleSql(cfg.getStr("sql"))) {
            engine = "ds_sql";
            rule = "simple_sql_to_ds";
        } else {
            engine = lhProperties.getEtl().getDefaultEngine();
            rule = "default";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("engine", engine);
        m.put("rule", rule);
        m.put("allowed", List.of("flink", "spark", "datax", "ds_sql"));
        return m;
    }

    public Map<String, Object> policyMatrix() {
        return Map.of(
                "default_engine", lhProperties.getEtl().getDefaultEngine(),
                "engines", List.of("flink", "spark", "datax", "ds_sql"),
                "rules", List.of(
                        Map.of("when", "mode=cdc", "engine", "flink"),
                        Map.of("when", "hint=heavy_batch", "engine", "spark"),
                        Map.of("when", "pattern=heterogeneous_sync", "engine", "datax"),
                        Map.of("when", "simple transform sql", "engine", "ds_sql")
                )
        );
    }

    private boolean isSimpleSql(String sql) {
        String s = sql.toLowerCase();
        return s.contains("select") && !s.contains("join") && !s.contains("window");
    }
}
