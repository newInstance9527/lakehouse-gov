package vip.xiaonuo.lh.modular.quality.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.core.engine.OpenMetadataClient;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.quality.entity.GovDqRule;
import vip.xiaonuo.lh.modular.quality.mapper.GovDqRuleMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 质量中心 ↔ OpenMetadata：Profiler / Test Case 读聚合 + 规则 soft-fail 投影。
 */
@Component
public class GovDqOmBridge {

    private static final Logger log = LoggerFactory.getLogger(GovDqOmBridge.class);
    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private OpenMetadataClient openMetadataClient;
    @Resource
    private GovAssetMapper assetMapper;
    @Resource
    private GovDqRuleMapper ruleMapper;

    /**
     * overview 附加块：抽样规则绑定表的 OM Test / Profile 摘要（soft-fail）。
     */
    public Map<String, Object> overviewOm(String ws, int sampleTables) {
        Map<String, Object> om = new LinkedHashMap<>();
        try {
            Map<String, Object> health = openMetadataClient.health();
            om.put("health", health.get("status"));
            if (!"UP".equalsIgnoreCase(String.valueOf(health.get("status")))) {
                om.put("available", false);
                om.put("hint", "OpenMetadata 不可达，仅展示门户 runs");
                return om;
            }
            Set<String> fqns = collectTableFqns(ws, Math.max(1, Math.min(sampleTables, 30)));
            int profileOk = 0;
            int testPass = 0;
            int testFail = 0;
            int testTotal = 0;
            int tables = 0;
            List<String> notes = new ArrayList<>();
            for (String fqn : fqns) {
                tables++;
                Map<String, Object> prof = openMetadataClient.getTableProfileSummary(fqn);
                if (Boolean.TRUE.equals(prof.get("ok"))) {
                    profileOk++;
                } else if (notes.size() < 3) {
                    notes.add("profile " + shortFqn(fqn) + ": " + prof.get("message"));
                }
                Map<String, Object> tests = openMetadataClient.listTestCasesByEntityFqn(fqn, 50);
                if (Boolean.TRUE.equals(tests.get("ok"))) {
                    testPass += ((Number) tests.getOrDefault("pass", 0)).intValue();
                    testFail += ((Number) tests.getOrDefault("fail", 0)).intValue();
                    testTotal += ((Number) tests.getOrDefault("total", 0)).intValue();
                } else if (notes.size() < 5) {
                    notes.add("tests " + shortFqn(fqn) + ": " + tests.get("message"));
                }
            }
            om.put("available", true);
            om.put("sampledTables", tables);
            om.put("profileOk", profileOk);
            om.put("testTotal", testTotal);
            om.put("testPass", testPass);
            om.put("testFail", testFail);
            double passRate = testTotal <= 0 ? 0.0 : (testPass * 100.0 / testTotal);
            om.put("testPassRate", Math.round(passRate * 10) / 10.0);
            om.put("hint", tables == 0
                    ? "无绑定 omFqn 的规则/资产，OM 侧暂无抽样"
                    : "抽样 " + tables + " 张表的 OM Profiler/Test（不替代门户 runs）");
            if (!notes.isEmpty()) {
                om.put("notes", notes);
            }
            return om;
        } catch (Exception e) {
            om.put("available", false);
            om.put("degraded", true);
            om.put("hint", "OM 聚合 soft-fail: " + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            return om;
        }
    }

    /**
     * sync-om：刷新水位 + 拉 Test/Profile + 对非空/唯一规则 soft-fail 投影 Test Case。
     */
    public Map<String, Object> syncFromOm(String ws) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> health = openMetadataClient.health();
        out.put("openmetadata", health);
        if (!"UP".equalsIgnoreCase(String.valueOf(health.get("status")))) {
            out.put("ok", false);
            out.put("hint", "OM DOWN，跳过 Test/Profile 同步");
            return out;
        }

        List<GovDqRule> rules = ruleMapper.selectList(new QueryWrapper<GovDqRule>().lambda()
                .eq(StrUtil.isNotBlank(ws), GovDqRule::getWs, ws)
                .eq(GovDqRule::getDeleteFlag, NOT_DELETE)
                .eq(GovDqRule::getEnabled, 1)
                .last("LIMIT 80"));

        int synced = 0;
        int skipped = 0;
        int failed = 0;
        int profileOk = 0;
        int testPass = 0;
        int testFail = 0;
        List<String> notes = new ArrayList<>();
        Set<String> seenFqn = new LinkedHashSet<>();

        for (GovDqRule rule : rules) {
            String tableFqn = resolveOmTableFqn(rule);
            if (StrUtil.isBlank(tableFqn)) {
                skipped++;
                continue;
            }
            if (seenFqn.add(tableFqn)) {
                Map<String, Object> prof = openMetadataClient.getTableProfileSummary(tableFqn);
                if (Boolean.TRUE.equals(prof.get("ok"))) {
                    profileOk++;
                }
                Map<String, Object> tests = openMetadataClient.listTestCasesByEntityFqn(tableFqn, 50);
                if (Boolean.TRUE.equals(tests.get("ok"))) {
                    testPass += ((Number) tests.getOrDefault("pass", 0)).intValue();
                    testFail += ((Number) tests.getOrDefault("fail", 0)).intValue();
                }
            }

            String def = mapTestDefinition(rule);
            if (def == null || StrUtil.isBlank(rule.getFieldName())) {
                skipped++;
                continue;
            }
            if (StrUtil.isNotBlank(rule.getOmTestFqn())) {
                skipped++;
                continue;
            }
            try {
                String caseName = sanitize(rule.getRuleCode() + "_" + rule.getFieldName());
                Map<String, Object> up = openMetadataClient.upsertColumnTestCaseSoft(
                        tableFqn, rule.getFieldName().trim(), def, caseName);
                if (Boolean.TRUE.equals(up.get("ok")) && StrUtil.isNotBlank(String.valueOf(up.get("fqn")))) {
                    rule.setOmTestFqn(String.valueOf(up.get("fqn")));
                    rule.setUpdateTime(new Date());
                    ruleMapper.updateById(rule);
                    synced++;
                } else {
                    failed++;
                    if (notes.size() < 8) {
                        notes.add(rule.getRuleCode() + ": " + up.get("message"));
                    }
                }
            } catch (Exception e) {
                failed++;
                log.debug("OM test sync soft-fail rule={}: {}", rule.getId(), e.getMessage());
                if (notes.size() < 8) {
                    notes.add(rule.getRuleCode() + ": " + e.getMessage());
                }
            }
        }

        out.put("ok", true);
        out.put("rulesScanned", rules.size());
        out.put("testSynced", synced);
        out.put("skipped", skipped);
        out.put("failed", failed);
        out.put("profileOk", profileOk);
        out.put("testPass", testPass);
        out.put("testFail", testFail);
        out.put("hint", "已拉取 Profiler/Test 摘要；非空/唯一规则 soft-fail 投影 OM Test（写 om_test_fqn）");
        if (!notes.isEmpty()) {
            out.put("notes", notes);
        }
        return out;
    }

    /**
     * 单条规则保存后即时 soft-fail 投影（不阻断 upsert）。
     */
    public Map<String, Object> syncRuleSoft(GovDqRule rule) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (rule == null) {
            r.put("ok", false);
            return r;
        }
        if (StrUtil.isNotBlank(rule.getOmTestFqn())) {
            r.put("ok", true);
            r.put("skipped", true);
            r.put("fqn", rule.getOmTestFqn());
            return r;
        }
        String def = mapTestDefinition(rule);
        String tableFqn = resolveOmTableFqn(rule);
        if (def == null || StrUtil.isBlank(tableFqn) || StrUtil.isBlank(rule.getFieldName())) {
            r.put("ok", true);
            r.put("skipped", true);
            r.put("hint", "仅非空/唯一且有 omFqn+字段 才自动投影");
            return r;
        }
        try {
            Map<String, Object> up = openMetadataClient.upsertColumnTestCaseSoft(
                    tableFqn, rule.getFieldName().trim(), def,
                    sanitize(rule.getRuleCode() + "_" + rule.getFieldName()));
            if (Boolean.TRUE.equals(up.get("ok")) && up.get("fqn") != null) {
                rule.setOmTestFqn(String.valueOf(up.get("fqn")));
                rule.setUpdateTime(new Date());
                ruleMapper.updateById(rule);
                r.put("ok", true);
                r.put("fqn", up.get("fqn"));
                r.put("created", up.get("created"));
                return r;
            }
            r.put("ok", false);
            r.put("degraded", true);
            r.put("message", up.get("message"));
            return r;
        } catch (Exception e) {
            r.put("ok", false);
            r.put("degraded", true);
            r.put("message", e.getMessage());
            return r;
        }
    }

    private Set<String> collectTableFqns(String ws, int limit) {
        Set<String> fqns = new LinkedHashSet<>();
        List<GovDqRule> rules = ruleMapper.selectList(new QueryWrapper<GovDqRule>().lambda()
                .eq(StrUtil.isNotBlank(ws), GovDqRule::getWs, ws)
                .eq(GovDqRule::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 120"));
        for (GovDqRule rule : rules) {
            String fqn = resolveOmTableFqn(rule);
            if (StrUtil.isNotBlank(fqn)) {
                fqns.add(fqn);
            }
            if (fqns.size() >= limit) {
                break;
            }
        }
        return fqns;
    }

    private String resolveOmTableFqn(GovDqRule rule) {
        if (rule == null) {
            return null;
        }
        if (StrUtil.isNotBlank(rule.getAssetId())) {
            GovAsset asset = assetMapper.selectById(rule.getAssetId());
            if (asset != null && StrUtil.isNotBlank(asset.getOmFqn())) {
                return asset.getOmFqn().trim();
            }
        }
        // 规则上若直接存了类 FQN（含多段）
        String t = StrUtil.blankToDefault(rule.getTableName(), "").trim();
        if (t.chars().filter(ch -> ch == '.').count() >= 2) {
            return t;
        }
        return null;
    }

    /** @return OM testDefinition 名；不支持则 null */
    static String mapTestDefinition(GovDqRule rule) {
        if (rule == null || StrUtil.isBlank(rule.getFieldName())) {
            return null;
        }
        String raw = StrUtil.blankToDefault(rule.getRuleType(), rule.getRuleCode()).toLowerCase(Locale.ROOT);
        if (raw.contains("非空") || raw.contains("null") || raw.contains("not_null")) {
            return "columnValuesToBeNotNull";
        }
        if (raw.contains("主键") || raw.contains("unique") || raw.contains("pk_")) {
            return "columnValuesToBeUnique";
        }
        return null;
    }

    private static String sanitize(String raw) {
        String s = StrUtil.blankToDefault(raw, "dq").replaceAll("[^A-Za-z0-9_]", "_");
        if (s.length() > 48) {
            s = s.substring(0, 48);
        }
        if (s.isEmpty() || !Character.isLetter(s.charAt(0))) {
            s = "r_" + s;
        }
        return s;
    }

    private static String shortFqn(String fqn) {
        if (StrUtil.isBlank(fqn)) {
            return "";
        }
        String[] p = fqn.split("\\.");
        if (p.length <= 2) {
            return fqn;
        }
        return p[p.length - 2] + "." + p[p.length - 1];
    }
}
