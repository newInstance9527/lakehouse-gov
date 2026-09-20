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

/**
 * 节点 → 执行引擎路由（与 doc/ETL编排.md §6 对齐）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Component
public class EngineResolver {

    @Resource
    private LhProperties lhProperties;

    /** 与前端 NODE_TYPES / IgEtlNodeTypes.ALL 同名 */
    public static final List<String> NODE_TYPES = IgEtlNodeTypes.ALL;

    public Map<String, Object> resolve(String nodeType, String cfgJson) {
        if (StrUtil.isNotBlank(nodeType) && !IgEtlNodeTypes.isKnown(nodeType)) {
            throw new CommonException("unknown nodeType: " + nodeType);
        }
        JSONObject cfg = StrUtil.isBlank(cfgJson) ? JSONUtil.createObj() : JSONUtil.parseObj(cfgJson);
        String engine;
        String rule;
        if (StrUtil.isNotBlank(cfg.getStr("engine_override"))) {
            if (StrUtil.isBlank(cfg.getStr("override_reason"))) {
                throw new CommonException("override_reason required");
            }
            engine = normalizeEngine(cfg.getStr("engine_override"));
            rule = "manual_override";
        } else if (StrUtil.isNotBlank(cfg.getStr("engine"))) {
            engine = normalizeEngine(cfg.getStr("engine"));
            rule = "explicit_engine";
        } else if (isCdcSource(nodeType, cfg)) {
            engine = "flink";
            rule = "cdc_to_flink";
        } else if (preferDataxSink(nodeType)) {
            // source_api / source_file / 异构 sink：须在通用 source 分支之前，否则会被吞进 defaultEngine
            engine = "datax";
            rule = "sink_to_datax";
        } else if ("source".equals(nodeType)) {
            engine = normalizeEngine(lhProperties.getEtl().getDefaultEngine());
            rule = "source_to_default";
        } else if ("heavy_batch".equalsIgnoreCase(cfg.getStr("hint"))) {
            engine = "spark";
            rule = "heavy_batch_to_spark";
        } else if ("heterogeneous_sync".equalsIgnoreCase(cfg.getStr("pattern"))) {
            engine = "datax";
            rule = "hetero_to_datax";
        } else if ("sink_kafka".equals(nodeType)) {
            engine = "flink";
            rule = "kafka_to_flink";
        } else if ("sink_bi".equals(nodeType)) {
            engine = "ds_sql";
            rule = "bi_to_ds";
        } else if ("quality".equals(nodeType) || "parallel".equals(nodeType)
                || "condition".equals(nodeType) || "union".equals(nodeType)) {
            engine = "ds_sql";
            rule = "control_to_ds";
        } else if ("clean".equals(nodeType) || "mapping".equals(nodeType)) {
            // 清洗/映射：按 fieldRules 编译 SQL，默认 Spark（批式更稳）；CDC 链路可在 conf.engine 显式 flink
            engine = "spark";
            rule = "clean_mapping_to_spark";
        } else if ("transform".equals(nodeType) && StrUtil.isNotBlank(cfg.getStr("sql")) && isSimpleSql(cfg.getStr("sql"))) {
            engine = "ds_sql";
            rule = "simple_sql_to_ds";
        } else {
            engine = normalizeEngine(lhProperties.getEtl().getDefaultEngine());
            rule = "default";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("engine", engine);
        m.put("rule", rule);
        m.put("nodeType", nodeType);
        m.put("allowed", List.of("flink", "spark", "datax", "ds_sql"));
        m.put("submitShape", submitShape(engine));
        return m;
    }

    public Map<String, Object> policyMatrix() {
        return Map.of(
                "default_engine", lhProperties.getEtl().getDefaultEngine(),
                "engines", List.of("flink", "spark", "datax", "ds_sql"),
                "nodeTypes", NODE_TYPES,
                "rules", List.of(
                        Map.of("when", "mode=cdc", "engine", "flink"),
                        Map.of("when", "hint=heavy_batch", "engine", "spark"),
                        Map.of("when", "pattern=heterogeneous_sync", "engine", "datax"),
                        Map.of("when", "sink_ftp|sink_rdb", "engine", "datax"),
                        Map.of("when", "sink_kafka", "engine", "flink"),
                        Map.of("when", "simple transform sql / quality / control", "engine", "ds_sql"),
                        Map.of("when", "clean / mapping (fieldRules)", "engine", "spark")
                )
        );
    }

    private boolean preferDataxSink(String nodeType) {
        return "sink_ftp".equals(nodeType) || "sink_rdb".equals(nodeType)
                || "sink_object".equals(nodeType) || "source_api".equals(nodeType)
                || "source_file".equals(nodeType);
    }

    /** CDC：mode/source_mode=cdc，或 conf.cdcEngine 含 flink（前端常 mode=batch 却勾选 Flink CDC） */
    private boolean isCdcSource(String nodeType, JSONObject cfg) {
        if ("cdc".equalsIgnoreCase(cfg.getStr("mode")) || "cdc".equalsIgnoreCase(cfg.getStr("source_mode"))) {
            return true;
        }
        // 库表源显式选了 Flink CDC 引擎时，即使 mode=batch 也走 flink
        if ("source".equals(nodeType) || IgEtlNodeTypes.SOURCE.contains(nodeType)) {
            String cdcEngine = StrUtil.blankToDefault(cfg.getStr("cdcEngine"), cfg.getStr("cdc_engine"));
            return StrUtil.isNotBlank(cdcEngine) && cdcEngine.toLowerCase().contains("flink");
        }
        return false;
    }

    private String normalizeEngine(String engine) {
        if (StrUtil.isBlank(engine)) {
            return lhProperties.getEtl().getDefaultEngine();
        }
        String e = engine.trim().toLowerCase();
        if ("flink cdc".equals(e) || e.startsWith("flink")) {
            return "flink";
        }
        if (e.startsWith("spark")) {
            return "spark";
        }
        if (e.startsWith("datax")) {
            return "datax";
        }
        if ("ds_sql".equals(e) || "dssql".equals(e) || "sql".equals(e)) {
            return "ds_sql";
        }
        return e;
    }

    private String submitShape(String engine) {
        return switch (engine) {
            case "flink" -> "DS_FLINK_OR_SHELL";
            case "spark" -> "DS_SPARK_OR_SHELL_SUBMIT";
            case "datax" -> "DS_SHELL_DATAX";
            case "ds_sql" -> "DS_SQL";
            default -> "DS_SHELL";
        };
    }

    private boolean isSimpleSql(String sql) {
        String s = sql.toLowerCase();
        return s.contains("select") && !s.contains("join") && !s.contains("window");
    }
}
