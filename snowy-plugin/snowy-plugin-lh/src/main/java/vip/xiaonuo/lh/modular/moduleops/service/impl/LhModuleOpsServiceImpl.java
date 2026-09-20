package vip.xiaonuo.lh.modular.moduleops.service.impl;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.GravitinoClient;
import vip.xiaonuo.lh.core.engine.MarquezClient;
import vip.xiaonuo.lh.core.engine.OpenMetadataClient;
import vip.xiaonuo.lh.core.link.LhModuleDeepLinks;
import vip.xiaonuo.lh.modular.lineage.service.GovLineageService;
import vip.xiaonuo.lh.modular.moduleops.service.LhModuleOpsService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 目录 / 质量 / 血缘 统一运维视图：依赖组件连接参数（无密文）+ 探活状态 + 水位摘要
 * <p>探活一律 soft-fail，禁止因下游 DOWN / NPE 整接口 500。</p>
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Slf4j
@Service
public class LhModuleOpsServiceImpl implements LhModuleOpsService {

    @Resource
    private LhProperties lhProperties;
    @Resource
    private OpenMetadataClient openMetadataClient;
    @Resource
    private MarquezClient marquezClient;
    @Resource
    private GravitinoClient gravitinoClient;
    @Resource
    private GovLineageService govLineageService;

    @Override
    public List<Map<String, Object>> listModules(String module) {
        String m = StrUtil.blankToDefault(module, "all").toLowerCase(Locale.ROOT);
        List<Map<String, Object>> list = new ArrayList<>();
        if ("all".equals(m) || "catalog".equals(m)) {
            list.add(detail("catalog"));
        }
        if ("all".equals(m) || "quality".equals(m)) {
            list.add(detail("quality"));
        }
        if ("all".equals(m) || "lineage".equals(m)) {
            list.add(detail("lineage"));
        }
        return list;
    }

    @Override
    public Map<String, Object> detail(String module) {
        String m = StrUtil.blankToDefault(module, "").toLowerCase(Locale.ROOT);
        return switch (m) {
            case "catalog" -> catalogOps();
            case "quality" -> qualityOps();
            case "lineage" -> lineageOps();
            default -> {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("module", module);
                err.put("ok", false);
                err.put("message", "未知模块，支持 catalog|quality|lineage");
                yield err;
            }
        };
    }

    private Map<String, Object> catalogOps() {
        Map<String, Object> r = base("catalog", "资产目录", "/catalog");
        List<Map<String, Object>> deps = new ArrayList<>();
        deps.add(dep("gravitino", "Gravitino",
                safeUrl(lhProperties.getGravitino() == null ? null : lhProperties.getGravitino().getUrl()),
                lhProperties.getGravitino() == null ? null : lhProperties.getGravitino().getVaultPath(),
                safeHealth("gravitino", () -> gravitinoClient.health())));
        deps.add(dep("openmetadata", "OpenMetadata",
                safeUrl(lhProperties.getOpenmetadata() == null ? null : lhProperties.getOpenmetadata().getUrl()),
                lhProperties.getOpenmetadata() == null ? null : lhProperties.getOpenmetadata().getVaultPath(),
                safeHealth("openmetadata", () -> openMetadataClient.health())));
        r.put("deps", deps);
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("schemaSource", "Gravitino loadTable → OM columns fallback");
        runtime.put("portalSoT", "gov_asset / gov_asset_source_link");
        r.put("runtime", runtime);
        r.put("status", rollup(deps));
        return r;
    }

    private Map<String, Object> qualityOps() {
        Map<String, Object> r = base("quality", "数据质量", "/quality");
        List<Map<String, Object>> deps = new ArrayList<>();
        deps.add(dep("openmetadata", "OpenMetadata",
                safeUrl(lhProperties.getOpenmetadata() == null ? null : lhProperties.getOpenmetadata().getUrl()),
                lhProperties.getOpenmetadata() == null ? null : lhProperties.getOpenmetadata().getVaultPath(),
                safeHealth("openmetadata", () -> openMetadataClient.health())));
        r.put("deps", deps);
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("portalSoT", "gov_dq_rule / gov_dq_rule_run / gov_dq_gate");
        runtime.put("omSyncApi", "POST /lh/quality/sync-om");
        r.put("runtime", runtime);
        r.put("status", rollup(deps));
        r.put("entryPath", LhModuleDeepLinks.quality(null));
        return r;
    }

    private Map<String, Object> lineageOps() {
        Map<String, Object> r = base("lineage", "字段血缘", "/lineage");
        List<Map<String, Object>> deps = new ArrayList<>();
        deps.add(dep("openmetadata", "OpenMetadata",
                safeUrl(lhProperties.getOpenmetadata() == null ? null : lhProperties.getOpenmetadata().getUrl()),
                lhProperties.getOpenmetadata() == null ? null : lhProperties.getOpenmetadata().getVaultPath(),
                safeHealth("openmetadata", () -> openMetadataClient.health())));
        deps.add(dep("marquez", "Marquez / OpenLineage",
                safeUrl(lhProperties.getMarquez() == null ? null : lhProperties.getMarquez().getUrl()),
                lhProperties.getMarquez() == null ? null : lhProperties.getMarquez().getVaultPath(),
                safeHealth("marquez", () -> marquezClient.health())));
        r.put("deps", deps);
        Map<String, Object> syncStatus = new LinkedHashMap<>();
        try {
            Map<String, Object> ss = govLineageService.syncStatus("default");
            if (ss != null) {
                syncStatus.putAll(ss);
            }
        } catch (Exception e) {
            syncStatus.put("ok", false);
            syncStatus.put("degraded", true);
            syncStatus.put("message", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
        }
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("portalSoT", "gov_lineage_field_edge");
        runtime.put("watermarkTable", "cb_lineage_sync_watermark");
        runtime.put("syncStatus", syncStatus);
        r.put("runtime", runtime);
        r.put("status", rollup(deps));
        return r;
    }

    private static Map<String, Object> base(String id, String name, String path) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("module", id);
        r.put("name", name);
        r.put("entryPath", path);
        r.put("ok", true);
        return r;
    }

    private static Map<String, Object> dep(String id, String label, String url, String vaultPath,
                                          Map<String, Object> health) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("component", id);
        d.put("label", label);
        d.put("url", url);
        d.put("vaultPath", vaultPath);
        String st = health == null ? "UNKNOWN" : String.valueOf(health.getOrDefault("status", "UNKNOWN"));
        d.put("status", st);
        d.put("health", health);
        return d;
    }

    private Map<String, Object> safeHealth(String component, Supplier<Map<String, Object>> call) {
        try {
            Map<String, Object> h = call.get();
            return h != null ? h : Map.of("component", component, "status", "UNKNOWN");
        } catch (Exception e) {
            log.warn("module-ops health soft-fail {}: {}", component, e.getMessage());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("component", component);
            m.put("status", "DOWN");
            m.put("error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return m;
        }
    }

    private static String rollup(List<Map<String, Object>> deps) {
        boolean anyDown = deps.stream().anyMatch(d -> "DOWN".equalsIgnoreCase(String.valueOf(d.get("status"))));
        boolean allUp = deps.stream().allMatch(d -> "UP".equalsIgnoreCase(String.valueOf(d.get("status"))));
        if (allUp) {
            return "UP";
        }
        if (anyDown) {
            return "DEGRADED";
        }
        return "UNKNOWN";
    }

    private static String safeUrl(String url) {
        return StrUtil.blankToDefault(url, null);
    }
}
