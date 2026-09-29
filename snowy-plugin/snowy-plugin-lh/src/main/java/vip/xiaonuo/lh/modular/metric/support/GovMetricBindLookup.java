package vip.xiaonuo.lh.modular.metric.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.dataapi.entity.DataapiApiBinding;
import vip.xiaonuo.lh.modular.dataapi.mapper.DataapiApiBindingMapper;
import vip.xiaonuo.lh.modular.metric.entity.GovMetric;
import vip.xiaonuo.lh.modular.metric.entity.GovMetricDep;
import vip.xiaonuo.lh.modular.metric.entity.GovMetricVer;
import vip.xiaonuo.lh.modular.metric.mapper.GovMetricDepMapper;
import vip.xiaonuo.lh.modular.metric.mapper.GovMetricMapper;
import vip.xiaonuo.lh.modular.metric.mapper.GovMetricVerMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 指标 ↔ 物理表 / 下游依赖查找（资产详情、血缘 impact、废弃影响共用）。
 */
@Component
public class GovMetricBindLookup {

    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private GovMetricMapper metricMapper;
    @Resource
    private GovMetricVerMapper verMapper;
    @Resource
    private GovMetricDepMapper depMapper;
    @Resource
    private DataapiApiBindingMapper bindingMapper;

    /**
     * 表被哪些指标引用（当前 ver 的 bind_table，或头表 grav_asset_id）。
     */
    public List<Map<String, Object>> metricsBoundToAsset(String ws, String assetId, String objectName,
                                                         String assetCode, String omFqn) {
        String workspace = StrUtil.blankToDefault(ws, "default");
        List<String> keys = tableKeys(objectName, assetCode, omFqn);
        List<GovMetric> heads = metricMapper.selectList(new QueryWrapper<GovMetric>().lambda()
                .eq(GovMetric::getDeleteFlag, NOT_DELETE)
                .eq(GovMetric::getWs, workspace));
        if (heads.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (GovMetric head : heads) {
            if (head == null || StrUtil.isBlank(head.getMetricCode()) || !seen.add(head.getMetricCode())) {
                continue;
            }
            boolean hit = StrUtil.isNotBlank(assetId) && Objects.equals(assetId, head.getGravAssetId());
            GovMetricVer ver = StrUtil.isNotBlank(head.getCurrentVerId())
                    ? verMapper.selectById(head.getCurrentVerId()) : null;
            if (!hit && ver != null && StrUtil.isNotBlank(ver.getBindTable())) {
                hit = tableMatches(ver.getBindTable(), keys);
            }
            if (!hit) {
                continue;
            }
            out.add(metricRow(head, ver));
            if (out.size() >= 40) {
                break;
            }
        }
        return out;
    }

    /** 下游复合/衍生指标 + 钉版本的数据服务绑定 */
    public Map<String, Object> downstreamImpact(String metricCode, String ws) {
        Map<String, Object> out = new LinkedHashMap<>();
        String code = StrUtil.trim(metricCode);
        out.put("metricCode", code);
        if (StrUtil.isBlank(code)) {
            out.put("downstreamMetrics", List.of());
            out.put("apiBindings", List.of());
            return out;
        }
        List<GovMetricDep> downEdges = depMapper.selectList(new QueryWrapper<GovMetricDep>().lambda()
                .eq(GovMetricDep::getDepCode, code));
        List<Map<String, Object>> downstream = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (GovMetricDep e : downEdges) {
            if (e == null || StrUtil.isBlank(e.getVerId())) {
                continue;
            }
            GovMetricVer dv = verMapper.selectById(e.getVerId());
            if (dv == null) {
                continue;
            }
            GovMetric dh = metricMapper.selectById(dv.getMetricId());
            if (dh == null || !NOT_DELETE.equals(dh.getDeleteFlag()) || !seen.add(dh.getMetricCode())) {
                continue;
            }
            if (StrUtil.isNotBlank(ws) && !Objects.equals(ws, dh.getWs())) {
                continue;
            }
            downstream.add(metricRow(dh, dv));
        }
        out.put("downstreamMetrics", downstream);
        out.put("apiBindings", apiBindingsForMetric(code, ws));
        out.put("downstreamCount", downstream.size());
        out.put("apiCount", ((List<?>) out.get("apiBindings")).size());
        return out;
    }

    public List<Map<String, Object>> apiBindingsForMetric(String metricCode, String ws) {
        String code = StrUtil.trim(metricCode);
        if (StrUtil.isBlank(code)) {
            return List.of();
        }
        List<DataapiApiBinding> all = bindingMapper.selectList(new QueryWrapper<DataapiApiBinding>().lambda()
                .eq(DataapiApiBinding::getDeleteFlag, NOT_DELETE)
                .eq(DataapiApiBinding::getSourceKind, "metric"));
        List<Map<String, Object>> out = new ArrayList<>();
        String prefix = code + "@";
        for (DataapiApiBinding b : all) {
            if (b == null) {
                continue;
            }
            if (StrUtil.isNotBlank(ws) && !Objects.equals(ws, b.getWs())) {
                continue;
            }
            String ref = StrUtil.blankToDefault(b.getSourceRef(), "");
            if (!ref.equals(code) && !ref.startsWith(prefix) && !ref.equalsIgnoreCase(code)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", b.getId());
            row.put("name", b.getName());
            row.put("publicPath", b.getPublicPath());
            row.put("sourceRef", b.getSourceRef());
            row.put("pinnedVer", pinnedVer(ref));
            row.put("state", b.getState());
            row.put("status", b.getStatus());
            out.add(row);
            if (out.size() >= 40) {
                break;
            }
        }
        return out;
    }

    private static Map<String, Object> metricRow(GovMetric head, GovMetricVer ver) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("metricCode", head.getMetricCode());
        row.put("name", head.getName());
        row.put("kind", head.getKind());
        row.put("status", head.getStatus());
        row.put("ver", head.getCurrentVer());
        row.put("owner", head.getOwner());
        row.put("domainCode", head.getDomainCode());
        if (ver != null) {
            row.put("bindTable", ver.getBindTable());
            row.put("bindField", ver.getBindField());
            row.put("agg", ver.getAgg());
        }
                    row.put("path", vip.xiaonuo.lh.core.link.LhModuleDeepLinks.metrics(head.getMetricCode()));
        return row;
    }

    static String pinnedVer(String sourceRef) {
        if (StrUtil.isBlank(sourceRef)) {
            return null;
        }
        int at = sourceRef.lastIndexOf('@');
        if (at <= 0 || at >= sourceRef.length() - 1) {
            return null;
        }
        return sourceRef.substring(at + 1).trim();
    }

    static List<String> tableKeys(String objectName, String assetCode, String omFqn) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        addKey(keys, objectName);
        addKey(keys, assetCode);
        addKey(keys, omFqn);
        return List.copyOf(keys);
    }

    private static void addKey(Set<String> keys, String raw) {
        if (StrUtil.isBlank(raw)) {
            return;
        }
        String s = raw.trim();
        keys.add(s);
        keys.add(shortName(s));
    }

    static boolean tableMatches(String bindTable, List<String> keys) {
        if (StrUtil.isBlank(bindTable) || keys == null || keys.isEmpty()) {
            return false;
        }
        String bt = bindTable.trim();
        String btShort = shortName(bt);
        for (String k : keys) {
            if (StrUtil.isBlank(k)) {
                continue;
            }
            if (bt.equalsIgnoreCase(k) || btShort.equalsIgnoreCase(shortName(k))) {
                return true;
            }
            if (bt.toLowerCase(Locale.ROOT).endsWith("." + k.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String shortName(String fqnOrName) {
        if (StrUtil.isBlank(fqnOrName)) {
            return "";
        }
        String s = fqnOrName.trim();
        int dot = s.lastIndexOf('.');
        return dot >= 0 && dot < s.length() - 1 ? s.substring(dot + 1) : s;
    }
}
