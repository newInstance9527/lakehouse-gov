package vip.xiaonuo.lh.modular.etl.support;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.MarquezClient;
import vip.xiaonuo.lh.core.ws.ExternalName;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlDag;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OpenLineage → Marquez 投影（soft-fail）
 * <p>Flink/Spark：投递运行事件；DataX/DS-SQL：解析补边形态的 COMPLETE 事件（字段边仍由 C3 写门户）。</p>
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Component
public class IgEtlOpenLineageProjector {

    private static final Logger log = LoggerFactory.getLogger(IgEtlOpenLineageProjector.class);
    private static final Set<String> OL_NATIVE = Set.of("flink", "spark");
    private static final Set<String> PARSE_SUPPLEMENT = Set.of("datax", "ds_sql");

    @Resource
    private MarquezClient marquezClient;
    @Resource
    private LhProperties lhProperties;

    /**
     * @param eventType START | COMPLETE | FAIL | ABORT
     */
    public Map<String, Object> project(IgEtlDag dag, List<IgEtlNode> nodes, String runId, String eventType) {
        Map<String, Object> out = new LinkedHashMap<>();
        int ok = 0;
        int fail = 0;
        int skipped = 0;
        List<String> notes = new ArrayList<>();
        String ns = ExternalName.marquezNamespace(dag.getWs());
        String et = StrUtil.blankToDefault(eventType, "COMPLETE").toUpperCase(Locale.ROOT);
        String olRunId = StrUtil.blankToDefault(runId, IdUtil.fastSimpleUUID());

        if (nodes == null || nodes.isEmpty()) {
            out.put("ok", true);
            out.put("olOk", 0);
            out.put("olFail", 0);
            out.put("olSkipped", 0);
            out.put("olRunId", olRunId);
            out.put("namespace", ns);
            return out;
        }

        for (IgEtlNode n : nodes) {
            String engine = StrUtil.blankToDefault(n.getResolvedEngine(), "").toLowerCase(Locale.ROOT);
            if (StrUtil.isBlank(engine)) {
                engine = guessEngine(n);
            }
            boolean nativeOl = OL_NATIVE.contains(engine);
            boolean parse = PARSE_SUPPLEMENT.contains(engine);
            if (!nativeOl && !parse) {
                skipped++;
                continue;
            }
            try {
                Map<String, Object> event = buildEvent(dag, n, engine, olRunId, et, nativeOl, ns);
                Map<String, Object> resp = marquezClient.postLineageEvent(event);
                if (Boolean.TRUE.equals(resp.get("ok"))) {
                    ok++;
                } else {
                    fail++;
                    notes.add(n.getNodeKey() + ": " + resp.get("message"));
                }
            } catch (Exception e) {
                fail++;
                notes.add(n.getNodeKey() + ": " + e.getMessage());
                log.warn("OL project soft-fail node={}: {}", n.getNodeKey(), e.getMessage());
            }
        }

        out.put("ok", fail == 0);
        out.put("degraded", fail > 0);
        out.put("olOk", ok);
        out.put("olFail", fail);
        out.put("olSkipped", skipped);
        out.put("olRunId", olRunId);
        out.put("namespace", ns);
        out.put("extId", ExternalName.extId(ExternalName.KIND_MARQUEZ_NS, dag.getWs(), "lakehouse"));
        out.put("marquezUrl", lhProperties.getMarquez() == null ? null : lhProperties.getMarquez().getUrl());
        if (!notes.isEmpty()) {
            out.put("notes", notes.size() > 8 ? notes.subList(0, 8) : notes);
        }
        return out;
    }

    private Map<String, Object> buildEvent(
            IgEtlDag dag, IgEtlNode n, String engine, String olRunId, String eventType, boolean nativeOl, String ns) {
        String dagCode = StrUtil.blankToDefault(dag.getDagCode(), dag.getId());
        String jobName = ExternalName.marquezJob(dagCode, n.getNodeKey());
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("namespace", ns);
        job.put("name", jobName);

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("runId", olRunId + ":" + n.getNodeKey());
        Map<String, Object> facets = new LinkedHashMap<>();
        Map<String, Object> engFacet = new LinkedHashMap<>();
        engFacet.put("_producer", "https://github.com/lakehouse/etl");
        engFacet.put("_schemaURL", "https://openlineage.io/spec/facets/1-0-0/ProcessingEngineRunFacet.json");
        engFacet.put("name", engine);
        engFacet.put("version", "portal");
        facets.put("processing_engine", engFacet);
        if (!nativeOl) {
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("_producer", "https://github.com/lakehouse/etl");
            note.put("source", "parse_supplement");
            note.put("hint", "DataX/DS-SQL 字段边由门户解析补齐，本事件登记作业边");
            facets.put("lakehouse_parse", note);
        }
        run.put("facets", facets);

        JSONObject conf = parseConf(n.getConfJson());
        List<Map<String, Object>> inputs = new ArrayList<>();
        List<Map<String, Object>> outputs = new ArrayList<>();
        String src = firstNonBlank(conf.getStr("table"), conf.getStr("src"), conf.getStr("topic"));
        String dst = firstNonBlank(conf.getStr("targetTable"), conf.getStr("destTable"), conf.getStr("sinkTable"));
        if (StrUtil.isNotBlank(src)) {
            inputs.add(dataset(ns, src));
        }
        if (StrUtil.isNotBlank(dst)) {
            outputs.add(dataset(ns, dst));
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", eventType);
        event.put("eventTime", Instant.now().toString());
        event.put("run", run);
        event.put("job", job);
        event.put("inputs", inputs);
        event.put("outputs", outputs);
        event.put("producer", "https://github.com/lakehouse/etl");
        event.put("schemaURL", "https://openlineage.io/spec/1-0-5/OpenLineage.json#/$defs/RunEvent");
        return event;
    }

    private static Map<String, Object> dataset(String ns, String name) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("namespace", ns);
        d.put("name", name);
        return d;
    }

    private static String guessEngine(IgEtlNode n) {
        String t = StrUtil.blankToDefault(n.getNodeType(), "").toLowerCase(Locale.ROOT);
        if (t.contains("flink") || "source".equals(t) || "cdc".equals(t)) {
            return "flink";
        }
        if (t.contains("spark") || "transform".equals(t)) {
            return "spark";
        }
        if (t.contains("datax") || "sync".equals(t)) {
            return "datax";
        }
        if (t.contains("sql")) {
            return "ds_sql";
        }
        return "";
    }

    private static JSONObject parseConf(String json) {
        if (StrUtil.isBlank(json)) {
            return new JSONObject();
        }
        try {
            return JSONUtil.parseObj(json);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) {
                return v;
            }
        }
        return null;
    }
}
