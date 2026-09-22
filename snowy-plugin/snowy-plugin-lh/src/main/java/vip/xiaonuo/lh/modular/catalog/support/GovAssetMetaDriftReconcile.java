package vip.xiaonuo.lh.modular.catalog.support;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.core.engine.VictoriaMetricsClient;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.enums.GovAssetStatusEnum;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.plat.service.PlatOutboxService;
import vip.xiaonuo.lh.modular.recon.entity.ReconMetaDrift;
import vip.xiaonuo.lh.modular.recon.mapper.ReconMetaDriftMapper;
import vip.xiaonuo.lh.modular.recon.support.ReconMetaDriftMetricsFormatter;
import vip.xiaonuo.lh.modular.schemasync.entity.CbGravAssetRef;
import vip.xiaonuo.lh.modular.schemasync.entity.CbOmAssetRef;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbGravAssetRefMapper;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbOmAssetRefMapper;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 门户 ↔ Grav/OM 指针漂移对账：落 {@code recon_meta_drift}；error/critical 且黄金 → 摘牌 + degraded。
 * <p>不依赖现网 Grav/OM 探活（指针表缺失即漂移）；夜莺看 {@code lh_meta_drift_open}。</p>
 */
@Slf4j
@Component
public class GovAssetMetaDriftReconcile {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_CLOSED = "closed";
    public static final String NOT_DELETE = "NOT_DELETE";

    public static final String TYPE_MISSING_GRAV_REF = "missing_grav_ref";
    public static final String TYPE_ORPHAN_GRAV_POINTER = "orphan_grav_pointer";
    public static final String TYPE_MISSING_OM_REF = "missing_om_ref";
    public static final String TYPE_ORPHAN_OM_POINTER = "orphan_om_pointer";
    public static final String TYPE_OM_FQN_MISMATCH = "om_fqn_mismatch";

    private static final Set<String> LAKE_LAYERS = Set.of("ods", "dwd", "dws", "ads", "dim");

    @Resource
    private GovAssetMapper assetMapper;
    @Resource
    private CbGravAssetRefMapper gravAssetRefMapper;
    @Resource
    private CbOmAssetRefMapper omAssetRefMapper;
    @Resource
    private ReconMetaDriftMapper driftMapper;
    @Resource
    private PlatOutboxService platOutboxService;
    @Resource
    private VictoriaMetricsClient victoriaMetricsClient;

    /**
     * 工作空间批对账（空 ws=全部非归档资产）。
     */
    public Map<String, Object> reconcileWorkspace(String ws) {
        String workspace = StrUtil.isBlank(ws) ? null : ws.trim();
        var qw = new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .ne(GovAsset::getStatus, GovAssetStatusEnum.ARCHIVED.getValue());
        if (StrUtil.isNotBlank(workspace)) {
            qw.eq(GovAsset::getWs, workspace);
        }
        List<GovAsset> assets = assetMapper.selectList(qw);
        int opened = 0;
        int closed = 0;
        int delisted = 0;
        int degraded = 0;
        List<Map<String, Object>> samples = new ArrayList<>();
        for (GovAsset asset : assets) {
            try {
                Map<String, Object> one = reconcileAsset(asset);
                opened += intVal(one.get("opened"));
                closed += intVal(one.get("closed"));
                if (Boolean.TRUE.equals(one.get("goldDelisted"))) {
                    delisted++;
                }
                if (Boolean.TRUE.equals(one.get("degraded"))) {
                    degraded++;
                }
                if (intVal(one.get("opened")) > 0 || Boolean.TRUE.equals(one.get("goldDelisted"))) {
                    if (samples.size() < 12) {
                        samples.add(one);
                    }
                }
            } catch (Exception e) {
                log.warn("meta drift soft-fail asset={}: {}", asset.getId(), e.getMessage());
            }
        }
        Map<String, Object> vm = writeVmOpenGauges(workspace);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ws", StrUtil.blankToDefault(workspace, "*"));
        out.put("scanned", assets.size());
        out.put("opened", opened);
        out.put("closed", closed);
        out.put("goldDelisted", delisted);
        out.put("degraded", degraded);
        out.put("samples", samples);
        out.put("vm", vm);
        return out;
    }

    /**
     * 单资产对账（refresh 挂接）。
     */
    public Map<String, Object> reconcileAsset(GovAsset asset) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("applied", false);
        if (asset == null || !NOT_DELETE.equals(asset.getDeleteFlag())) {
            r.put("skipped", true);
            return r;
        }
        if (GovAssetStatusEnum.ARCHIVED.getValue().equalsIgnoreCase(asset.getStatus())) {
            r.put("skipped", true);
            r.put("reason", "archived");
            return r;
        }
        String ws = StrUtil.blankToDefault(asset.getWs(), "default");
        r.put("assetId", asset.getId());
        r.put("assetCode", asset.getAssetCode());

        List<DriftHit> hits = detect(asset);
        Set<String> activeTypes = hits.stream().map(h -> h.driftType).collect(Collectors.toSet());
        int opened = 0;
        for (DriftHit hit : hits) {
            if (upsertOpen(asset, hit)) {
                opened++;
            }
        }
        int closed = closeResolved(asset.getId(), activeTypes);

        boolean goldDelisted = false;
        boolean degraded = false;
        boolean shouldDelist = hits.stream().anyMatch(h -> isDelistSeverity(h.severity));
        if (shouldDelist) {
            Map<String, Object> delist = delistIfNeeded(asset, hits);
            goldDelisted = Boolean.TRUE.equals(delist.get("goldDelisted"));
            degraded = Boolean.TRUE.equals(delist.get("degraded"));
            r.put("eventId", delist.get("eventId"));
        }

        r.put("opened", opened);
        r.put("closed", closed);
        r.put("openTypes", List.copyOf(activeTypes));
        r.put("goldDelisted", goldDelisted);
        r.put("degraded", degraded);
        r.put("applied", opened > 0 || closed > 0 || goldDelisted || degraded);
        r.put("ws", ws);
        return r;
    }

    public List<Map<String, Object>> listOpen(String ws, Integer limit) {
        int lim = limit == null ? 50 : Math.max(1, Math.min(200, limit));
        var qw = new QueryWrapper<ReconMetaDrift>().lambda()
                .eq(ReconMetaDrift::getDeleteFlag, NOT_DELETE)
                .eq(ReconMetaDrift::getStatus, STATUS_OPEN)
                .orderByDesc(ReconMetaDrift::getDetectedAt)
                .last("LIMIT " + lim);
        if (StrUtil.isNotBlank(ws)) {
            qw.eq(ReconMetaDrift::getWs, ws.trim());
        }
        List<ReconMetaDrift> rows = driftMapper.selectList(qw);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReconMetaDrift d : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("ws", d.getWs());
            m.put("driftType", d.getDriftType());
            m.put("severity", d.getSeverity());
            m.put("assetId", d.getAssetId());
            m.put("assetCode", d.getAssetCode());
            m.put("gravAssetId", d.getGravAssetId());
            m.put("omFqn", d.getOmFqn());
            m.put("leftSystem", d.getLeftSystem());
            m.put("rightSystem", d.getRightSystem());
            m.put("detectedAt", d.getDetectedAt());
            m.put("remark", d.getRemark());
            out.add(m);
        }
        return out;
    }

    private List<DriftHit> detect(GovAsset asset) {
        List<DriftHit> hits = new ArrayList<>();
        String gravId = asset.getGravAssetId();
        if (StrUtil.isNotBlank(gravId)) {
            CbGravAssetRef grav = gravAssetRefMapper.selectById(gravId);
            if (grav == null || !NOT_DELETE.equals(grav.getDeleteFlag())) {
                hits.add(hit(TYPE_ORPHAN_GRAV_POINTER, "error", "portal", "gravitino",
                        Map.of("gravAssetId", gravId),
                        Map.of("present", false),
                        "门户 grav_asset_id 无对应 cb_grav_asset_ref"));
            }
        } else if (expectsGrav(asset)) {
            hits.add(hit(TYPE_MISSING_GRAV_REF, "warn", "portal", "gravitino",
                    Map.of("layer", StrUtil.blankToDefault(asset.getLayer(), ""),
                            "assetKind", StrUtil.blankToDefault(asset.getAssetKind(), "")),
                    Map.of("gravAssetId", ""),
                    "分层湖表缺 Grav 指针（可 refresh 挂接）"));
        }

        String omId = asset.getOmAssetId();
        String omFqn = asset.getOmFqn();
        if (StrUtil.isNotBlank(omId)) {
            CbOmAssetRef om = omAssetRefMapper.selectById(omId);
            if (om == null || !NOT_DELETE.equals(om.getDeleteFlag())) {
                hits.add(hit(TYPE_ORPHAN_OM_POINTER, "error", "portal", "openmetadata",
                        Map.of("omAssetId", omId, "omFqn", StrUtil.blankToDefault(omFqn, "")),
                        Map.of("present", false),
                        "门户 om_asset_id 无对应 cb_om_asset_ref"));
            } else if (StrUtil.isNotBlank(omFqn) && StrUtil.isNotBlank(om.getOmFqn())
                    && !omFqn.trim().equalsIgnoreCase(om.getOmFqn().trim())) {
                hits.add(hit(TYPE_OM_FQN_MISMATCH, "error", "portal", "openmetadata",
                        Map.of("omFqn", omFqn),
                        Map.of("omFqn", om.getOmFqn(), "omAssetId", om.getId()),
                        "门户 om_fqn 与 cb_om_asset_ref 不一致"));
            }
        } else if (StrUtil.isNotBlank(omFqn)) {
            hits.add(hit(TYPE_MISSING_OM_REF, "warn", "portal", "openmetadata",
                    Map.of("omFqn", omFqn),
                    Map.of("omAssetId", ""),
                    "有 om_fqn 但缺 om_asset_id / 桥接未回填"));
        }
        return hits;
    }

    private boolean upsertOpen(GovAsset asset, DriftHit hit) {
        ReconMetaDrift existing = driftMapper.selectOne(new QueryWrapper<ReconMetaDrift>().lambda()
                .eq(ReconMetaDrift::getAssetId, asset.getId())
                .eq(ReconMetaDrift::getDriftType, hit.driftType)
                .eq(ReconMetaDrift::getStatus, STATUS_OPEN)
                .eq(ReconMetaDrift::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        Date now = new Date();
        if (existing != null) {
            existing.setSeverity(hit.severity);
            existing.setRemark(hit.remark);
            existing.setLeftSnapshot(JSONUtil.toJsonStr(hit.left));
            existing.setRightSnapshot(JSONUtil.toJsonStr(hit.right));
            existing.setGravAssetId(asset.getGravAssetId());
            existing.setOmFqn(asset.getOmFqn());
            existing.setRevision(existing.getRevision() == null ? 1 : existing.getRevision() + 1);
            existing.setDetectedAt(now);
            driftMapper.updateById(existing);
            return false;
        }
        ReconMetaDrift row = new ReconMetaDrift();
        row.setId(IdUtil.getSnowflakeNextIdStr());
        row.setRevision(1);
        row.setStatus(STATUS_OPEN);
        row.setWs(StrUtil.blankToDefault(asset.getWs(), "default"));
        row.setDriftType(hit.driftType);
        row.setSeverity(hit.severity);
        row.setAssetId(asset.getId());
        row.setAssetCode(asset.getAssetCode());
        row.setGravAssetId(asset.getGravAssetId());
        row.setOmFqn(asset.getOmFqn());
        row.setLeftSystem(hit.leftSystem);
        row.setRightSystem(hit.rightSystem);
        row.setLeftSnapshot(JSONUtil.toJsonStr(hit.left));
        row.setRightSnapshot(JSONUtil.toJsonStr(hit.right));
        row.setRemark(hit.remark);
        row.setDetectedAt(now);
        row.setDeleteFlag(NOT_DELETE);
        driftMapper.insert(row);
        return true;
    }

    private int closeResolved(String assetId, Set<String> stillOpen) {
        List<ReconMetaDrift> opens = driftMapper.selectList(new QueryWrapper<ReconMetaDrift>().lambda()
                .eq(ReconMetaDrift::getAssetId, assetId)
                .eq(ReconMetaDrift::getStatus, STATUS_OPEN)
                .eq(ReconMetaDrift::getDeleteFlag, NOT_DELETE));
        int closed = 0;
        Date now = new Date();
        for (ReconMetaDrift d : opens) {
            if (stillOpen.contains(d.getDriftType())) {
                continue;
            }
            d.setStatus(STATUS_CLOSED);
            d.setResolvedAt(now);
            d.setRevision(d.getRevision() == null ? 1 : d.getRevision() + 1);
            d.setRemark(StrUtil.blankToDefault(d.getRemark(), "") + " · auto_closed");
            driftMapper.updateById(d);
            closed++;
        }
        return closed;
    }

    private Map<String, Object> delistIfNeeded(GovAsset asset, List<DriftHit> hits) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("goldDelisted", false);
        r.put("degraded", false);
        GovAsset fresh = assetMapper.selectById(asset.getId());
        if (fresh == null) {
            return r;
        }
        boolean goldDelisted = false;
        boolean degraded = false;
        Date now = new Date();
        if (Integer.valueOf(1).equals(fresh.getIsGold())) {
            fresh.setIsGold(0);
            goldDelisted = true;
        }
        if (!GovAssetStatusEnum.ARCHIVED.getValue().equalsIgnoreCase(fresh.getStatus())
                && !GovAssetStatusEnum.DEGRADED.getValue().equalsIgnoreCase(fresh.getStatus())) {
            fresh.setStatus(GovAssetStatusEnum.DEGRADED.getValue());
            degraded = true;
        }
        if (goldDelisted || degraded) {
            fresh.setRevision(fresh.getRevision() == null ? 1 : fresh.getRevision() + 1);
            fresh.setLastSyncAt(now);
            fresh.setLastSyncStatus("meta_drift");
            assetMapper.updateById(fresh);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("assetId", fresh.getId());
            payload.put("assetCode", fresh.getAssetCode());
            payload.put("goldDelisted", goldDelisted);
            payload.put("degraded", degraded);
            payload.put("driftTypes", hits.stream().map(h -> h.driftType).toList());
            payload.put("severities", hits.stream().map(h -> h.severity).toList());
            String eventId = platOutboxService.appendSoft(
                    "catalog.asset.meta_drift.delisted",
                    "gov_asset",
                    fresh.getId(),
                    payload,
                    Map.of("source", "GovAssetMetaDriftReconcile"));
            r.put("eventId", eventId);
        }
        r.put("goldDelisted", goldDelisted);
        r.put("degraded", degraded);
        return r;
    }

    private Map<String, Object> writeVmOpenGauges(String wsFilter) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            var qw = new QueryWrapper<ReconMetaDrift>().lambda()
                    .eq(ReconMetaDrift::getDeleteFlag, NOT_DELETE)
                    .eq(ReconMetaDrift::getStatus, STATUS_OPEN);
            if (StrUtil.isNotBlank(wsFilter)) {
                qw.eq(ReconMetaDrift::getWs, wsFilter);
            }
            List<ReconMetaDrift> opens = driftMapper.selectList(qw);
            Map<String, Long> buckets = new LinkedHashMap<>();
            for (ReconMetaDrift d : opens) {
                String key = StrUtil.blankToDefault(d.getWs(), "default") + "|"
                        + StrUtil.blankToDefault(d.getSeverity(), "warn") + "|"
                        + StrUtil.blankToDefault(d.getDriftType(), "unknown");
                buckets.merge(key, 1L, Long::sum);
            }
            long ts = System.currentTimeMillis();
            List<String> lines = new ArrayList<>();
            if (buckets.isEmpty()) {
                lines.addAll(ReconMetaDriftMetricsFormatter.formatOpenCount(
                        StrUtil.blankToDefault(wsFilter, "default"), "none", "none", 0, ts));
            } else {
                for (Map.Entry<String, Long> e : buckets.entrySet()) {
                    String[] p = e.getKey().split("\\|", 3);
                    lines.addAll(ReconMetaDriftMetricsFormatter.formatOpenCount(
                            p[0], p[1], p[2], e.getValue(), ts));
                }
            }
            Map<String, Object> wr = victoriaMetricsClient.importPrometheus(
                    ReconMetaDriftMetricsFormatter.joinBody(lines));
            out.put("written", wr != null && !Boolean.FALSE.equals(wr.get("ok")));
            out.put("openTotal", opens.size());
            out.put("series", buckets.size());
            if (wr != null) {
                out.put("skipped", wr.get("skipped"));
                out.put("message", wr.get("message"));
            }
        } catch (Exception e) {
            log.warn("meta drift vm soft-fail: {}", e.getMessage());
            out.put("written", false);
            out.put("message", e.getMessage());
        }
        return out;
    }

    private static boolean expectsGrav(GovAsset asset) {
        String layer = StrUtil.blankToDefault(asset.getLayer(), "").toLowerCase(Locale.ROOT);
        if (LAKE_LAYERS.contains(layer)) {
            return true;
        }
        String kind = StrUtil.blankToDefault(asset.getAssetKind(), "").toLowerCase(Locale.ROOT);
        return kind.contains("iceberg") || kind.contains("hive") || kind.contains("table");
    }

    private static boolean isDelistSeverity(String severity) {
        String s = StrUtil.blankToDefault(severity, "").toLowerCase(Locale.ROOT);
        return "error".equals(s) || "critical".equals(s);
    }

    private static DriftHit hit(String type, String severity, String left, String right,
                                Map<String, Object> leftSnap, Map<String, Object> rightSnap, String remark) {
        DriftHit h = new DriftHit();
        h.driftType = type;
        h.severity = severity;
        h.leftSystem = left;
        h.rightSystem = right;
        h.left = leftSnap;
        h.right = rightSnap;
        h.remark = remark;
        return h;
    }

    private static int intVal(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    private static final class DriftHit {
        String driftType;
        String severity;
        String leftSystem;
        String rightSystem;
        Map<String, Object> left;
        Map<String, Object> right;
        String remark;
    }
}
