package vip.xiaonuo.lh.modular.sec.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.core.engine.OpenMetadataClient;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.sec.entity.SecMaskPolicyProj;
import vip.xiaonuo.lh.modular.sec.mapper.SecMaskPolicyProjMapper;
import vip.xiaonuo.lh.modular.sec.param.SecOmMaskSyncParam;
import vip.xiaonuo.lh.modular.sec.service.SecOmMaskSyncService;
import vip.xiaonuo.lh.modular.sec.support.SecOmMaskTagSupport;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class SecOmMaskSyncServiceImpl implements SecOmMaskSyncService {

    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private LhProperties lhProperties;
    @Resource
    private OpenMetadataClient openMetadataClient;
    @Resource
    private GovAssetMapper assetMapper;
    @Resource
    private SecMaskPolicyProjMapper maskMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> syncFromOm(SecOmMaskSyncParam param) {
        LhProperties.Openmetadata omCfg = lhProperties.getOpenmetadata();
        if (omCfg != null && !omCfg.isMaskTagSyncEnabled()) {
            throw new CommonException("OM→mask 同步已关闭（lh.openmetadata.mask-tag-sync-enabled=false）");
        }
        boolean dryRun = param != null && Boolean.TRUE.equals(param.getDryRun());
        List<String> prefixes = SecOmMaskTagSupport.normalizePrefixes(
                omCfg == null ? null : omCfg.getMaskSensitiveTagPrefixes());
        int batchLimit = omCfg == null ? 50 : Math.max(1, omCfg.getMaskTagSyncBatchLimit());

        List<GovAsset> assets = resolveAssets(param, batchLimit);
        int scanned = 0;
        int upserted = 0;
        int skipped = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (GovAsset asset : assets) {
            scanned++;
            Map<String, Object> one = syncOneAsset(asset, prefixes, dryRun);
            details.add(one);
            if (Boolean.TRUE.equals(one.get("ok"))) {
                Object n = one.get("upserted");
                upserted += n instanceof Number ? ((Number) n).intValue() : 0;
            } else {
                skipped++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("dryRun", dryRun);
        out.put("scanned", scanned);
        out.put("upserted", upserted);
        out.put("skipped", skipped);
        out.put("prefixes", prefixes);
        out.put("items", details.size() > 40 ? details.subList(0, 40) : details);
        out.put("hint", dryRun
                ? "dryRun：未写库；确认后去掉 dryRun 落 sec_mask_policy_proj"
                : "已按 OM 列标签 upsert sec_mask_policy_proj（不写 Grav mask）");
        return out;
    }

    @Override
    public Map<String, Object> tableReadPrefill(String assetId) {
        if (StrUtil.isBlank(assetId)) {
            throw new CommonException("assetId 不能为空");
        }
        GovAsset asset = assetMapper.selectById(assetId.trim());
        if (asset == null || !NOT_DELETE.equals(asset.getDeleteFlag())) {
            throw new CommonException("资产不存在");
        }
        List<String> suggested = loadMaskColumns(asset.getGravAssetId());
        String columnSource = suggested.isEmpty() ? "none" : "sec_mask_policy_proj";

        // 若投影为空，尝试 dry 读 OM 列标签作建议（不落库）
        List<Map<String, Object>> omHints = new ArrayList<>();
        boolean omOk = false;
        if (suggested.isEmpty() && StrUtil.isNotBlank(asset.getOmFqn())) {
            try {
                List<String> prefixes = SecOmMaskTagSupport.normalizePrefixes(
                        lhProperties.getOpenmetadata() == null
                                ? null
                                : lhProperties.getOpenmetadata().getMaskSensitiveTagPrefixes());
                List<ColHit> hits = extractSensitiveColumns(asset.getOmFqn(), prefixes);
                omOk = true;
                for (ColHit h : hits) {
                    suggested.add(h.columnName);
                    Map<String, Object> hint = new LinkedHashMap<>();
                    hint.put("column", h.columnName);
                    hint.put("omTagFqn", h.tagFqn);
                    hint.put("maskAlgo", h.maskAlgo);
                    hint.put("sensitivity", h.sensitivity);
                    omHints.add(hint);
                }
                if (!suggested.isEmpty()) {
                    columnSource = "om_preview";
                }
            } catch (Exception e) {
                log.debug("prefill OM preview soft-fail asset={}: {}", assetId, e.getMessage());
            }
        } else if (!suggested.isEmpty()) {
            omHints.addAll(loadMaskHints(asset.getGravAssetId()));
            omOk = true;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assetId", asset.getId());
        out.put("assetCode", asset.getAssetCode());
        out.put("sensitivity", asset.getSensitivity());
        out.put("suggestedColumns", suggested);
        out.put("columnSource", columnSource);
        out.put("rowFilterTemplates", SecOmMaskTagSupport.rowFilterTemplates(asset.getSensitivity()));
        out.put("maskHints", omHints);
        out.put("omSyncAvailable", omOk && StrUtil.isNotBlank(asset.getOmFqn()));
        out.put("hint", suggested.isEmpty()
                ? "暂无敏感列建议；可先在安全中心「从 OM 同步脱敏」或手填列/行过滤"
                : "建议申请列来自 " + columnSource + "；行过滤请从模板选用并按场景改写");
        return out;
    }

    private List<GovAsset> resolveAssets(SecOmMaskSyncParam param, int batchLimit) {
        if (param != null && StrUtil.isNotBlank(param.getAssetId())) {
            GovAsset a = assetMapper.selectById(param.getAssetId().trim());
            if (a == null || !NOT_DELETE.equals(a.getDeleteFlag())) {
                throw new CommonException("资产不存在: " + param.getAssetId());
            }
            return List.of(a);
        }
        String ws = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(param == null ? null : param.getWs());
        return assetMapper.selectList(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .eq(StrUtil.isNotBlank(ws), GovAsset::getWs, ws)
                .isNotNull(GovAsset::getOmFqn)
                .ne(GovAsset::getOmFqn, "")
                .isNotNull(GovAsset::getGravAssetId)
                .ne(GovAsset::getGravAssetId, "")
                .orderByDesc(GovAsset::getUpdateTime)
                .last("limit " + batchLimit));
    }

    private Map<String, Object> syncOneAsset(GovAsset asset, List<String> prefixes, boolean dryRun) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("assetId", asset.getId());
        one.put("assetCode", asset.getAssetCode());
        if (StrUtil.isBlank(asset.getOmFqn())) {
            one.put("ok", false);
            one.put("reason", "no_om_fqn");
            return one;
        }
        if (StrUtil.isBlank(asset.getGravAssetId())) {
            one.put("ok", false);
            one.put("reason", "no_grav_asset_id");
            return one;
        }
        try {
            List<ColHit> hits = extractSensitiveColumns(asset.getOmFqn(), prefixes);
            one.put("hits", hits.size());
            List<Map<String, Object>> cols = new ArrayList<>();
            int n = 0;
            Date now = new Date();
            String userId;
            try {
                userId = LhLoginUsers.requireUserId();
            } catch (Exception e) {
                userId = "system";
            }
            for (ColHit h : hits) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("column", h.columnName);
                c.put("omTagFqn", h.tagFqn);
                c.put("maskAlgo", h.maskAlgo);
                c.put("sensitivity", h.sensitivity);
                if (!dryRun) {
                    boolean wrote = upsertMask(asset, h, now, userId);
                    c.put("upserted", wrote);
                    if (wrote) {
                        n++;
                    }
                }
                cols.add(c);
            }
            one.put("ok", true);
            one.put("upserted", dryRun ? hits.size() : n);
            one.put("columns", cols);
            return one;
        } catch (Exception e) {
            log.warn("OM mask sync soft-fail asset={}: {}", asset.getId(), e.getMessage());
            one.put("ok", false);
            one.put("reason", "om_error");
            one.put("error", e.getMessage());
            return one;
        }
    }

    private List<ColHit> extractSensitiveColumns(String omFqn, List<String> prefixes) {
        JSONObject table = openMetadataClient.getTableByFqn(omFqn);
        if (table == null) {
            throw new CommonException("OM 表不存在: " + omFqn);
        }
        List<ColHit> hits = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Object colsNode = table.get("columns");
        if (!(colsNode instanceof JSONArray arr)) {
            return hits;
        }
        for (Object o : arr) {
            if (!(o instanceof JSONObject col)) {
                continue;
            }
            String name = firstColName(col);
            if (StrUtil.isBlank(name) || !seen.add(name.toLowerCase())) {
                continue;
            }
            List<String> tags = SecOmMaskTagSupport.extractTagFqns(col.get("tags"));
            String hitTag = SecOmMaskTagSupport.firstSensitiveTag(tags, prefixes);
            if (hitTag == null) {
                continue;
            }
            ColHit h = new ColHit();
            h.columnName = name;
            h.tagFqn = hitTag;
            h.maskAlgo = SecOmMaskTagSupport.resolveMaskAlgo(name, hitTag);
            h.sensitivity = SecOmMaskTagSupport.resolveSensitivity(hitTag);
            hits.add(h);
        }
        return hits;
    }

    private boolean upsertMask(GovAsset asset, ColHit h, Date now, String userId) {
        SecMaskPolicyProj exist = maskMapper.selectOne(new QueryWrapper<SecMaskPolicyProj>().lambda()
                .eq(SecMaskPolicyProj::getGravAssetId, asset.getGravAssetId())
                .eq(SecMaskPolicyProj::getColumnName, h.columnName)
                .eq(SecMaskPolicyProj::getDeleteFlag, NOT_DELETE)
                .last("limit 1"));
        if (exist != null) {
            exist.setStatus("active");
            exist.setSensitivity(h.sensitivity);
            exist.setMaskAlgo(h.maskAlgo);
            exist.setOmTagFqn(h.tagFqn);
            exist.setRemark("om tag sync");
            exist.setWs(StrUtil.blankToDefault(asset.getWs(), exist.getWs()));
            exist.setUpdateTime(now);
            exist.setUpdateUser(userId);
            exist.setRevision(exist.getRevision() == null ? 1 : exist.getRevision() + 1);
            maskMapper.updateById(exist);
            return true;
        }
        SecMaskPolicyProj m = new SecMaskPolicyProj();
        m.setId(IdUtil.getSnowflakeNextIdStr());
        m.setRevision(1);
        m.setStatus("active");
        m.setWs(StrUtil.blankToDefault(asset.getWs(), "default"));
        m.setGravAssetId(asset.getGravAssetId());
        m.setColumnName(h.columnName);
        m.setSensitivity(h.sensitivity);
        m.setMaskAlgo(h.maskAlgo);
        m.setOmTagFqn(h.tagFqn);
        m.setRemark("om tag sync");
        m.setDeleteFlag(NOT_DELETE);
        m.setCreateTime(now);
        m.setCreateUser(userId);
        m.setUpdateTime(now);
        maskMapper.insert(m);
        return true;
    }

    private List<String> loadMaskColumns(String gravAssetId) {
        List<String> out = new ArrayList<>();
        if (StrUtil.isBlank(gravAssetId)) {
            return out;
        }
        List<SecMaskPolicyProj> rows = maskMapper.selectList(new QueryWrapper<SecMaskPolicyProj>().lambda()
                .eq(SecMaskPolicyProj::getGravAssetId, gravAssetId)
                .eq(SecMaskPolicyProj::getStatus, "active")
                .eq(SecMaskPolicyProj::getDeleteFlag, NOT_DELETE)
                .ne(SecMaskPolicyProj::getColumnName, "*")
                .orderByAsc(SecMaskPolicyProj::getColumnName));
        for (SecMaskPolicyProj r : rows) {
            if (r != null && StrUtil.isNotBlank(r.getColumnName())) {
                out.add(r.getColumnName());
            }
        }
        return out;
    }

    private List<Map<String, Object>> loadMaskHints(String gravAssetId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (StrUtil.isBlank(gravAssetId)) {
            return out;
        }
        List<SecMaskPolicyProj> rows = maskMapper.selectList(new QueryWrapper<SecMaskPolicyProj>().lambda()
                .eq(SecMaskPolicyProj::getGravAssetId, gravAssetId)
                .eq(SecMaskPolicyProj::getStatus, "active")
                .eq(SecMaskPolicyProj::getDeleteFlag, NOT_DELETE)
                .ne(SecMaskPolicyProj::getColumnName, "*"));
        for (SecMaskPolicyProj r : rows) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("column", r.getColumnName());
            h.put("omTagFqn", r.getOmTagFqn());
            h.put("maskAlgo", r.getMaskAlgo());
            h.put("sensitivity", r.getSensitivity());
            out.add(h);
        }
        return out;
    }

    private static String firstColName(JSONObject col) {
        String n = col.getStr("name");
        if (StrUtil.isBlank(n)) {
            n = col.getStr("columnName");
        }
        if (StrUtil.isBlank(n)) {
            n = col.getStr("displayName");
        }
        return StrUtil.trim(n);
    }

    private static final class ColHit {
        String columnName;
        String tagFqn;
        String maskAlgo;
        String sensitivity;
    }
}
