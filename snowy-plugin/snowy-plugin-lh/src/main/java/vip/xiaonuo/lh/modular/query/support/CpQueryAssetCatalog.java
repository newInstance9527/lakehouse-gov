package vip.xiaonuo.lh.modular.query.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.core.engine.GravitinoClient;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.schemasync.entity.CbGravAssetRef;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbGravAssetRefMapper;
import vip.xiaonuo.lh.modular.sec.entity.SecAuthGrant;
import vip.xiaonuo.lh.modular.sec.mapper.SecAuthGrantMapper;
import vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService;
import vip.xiaonuo.lh.modular.sec.service.impl.SecAuthGrantServiceImpl;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 即席目录：只读门户资产。引擎坐标仅用于 sampleSql 与列快照回源，不出现在节点上。
 */
@Slf4j
@Component
public class CpQueryAssetCatalog {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final List<String> LAYER_ORDER = List.of("ods", "dwd", "dws", "ads", "dim");
    private static final List<String> VISIBLE_STATUS = List.of("active", "syncing", "degraded");
    private static final Pattern MASK_HINT = Pattern.compile(
            "(mobile|phone|id_card|idcard|email|password|secret|token)", Pattern.CASE_INSENSITIVE);

    @Resource
    private GovAssetMapper assetMapper;
    @Resource
    private CbGravAssetRefMapper gravAssetRefMapper;
    @Resource
    private SecAuthGrantMapper grantMapper;
    @Resource
    private SecAuthGrantService secAuthGrantService;
    @Resource
    private GravitinoClient gravitinoClient;

    public List<Map<String, Object>> schemaTree(String ws) {
        String workspace = StrUtil.blankToDefault(StrUtil.trim(ws), "default");
        SaBaseLoginUser user = LhLoginUsers.requireUser();
        Set<String> granted = selectGrantAssetIds(user.getId());
        List<GovAsset> assets = assetMapper.selectList(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .eq(GovAsset::getWs, workspace)
                .in(GovAsset::getStatus, VISIBLE_STATUS)
                .and(w -> w.eq(GovAsset::getAssetKind, "table")
                        .or().isNull(GovAsset::getAssetKind)
                        .or().eq(GovAsset::getAssetKind, ""))
                .orderByAsc(GovAsset::getLayer)
                .orderByAsc(GovAsset::getDomainCode)
                .orderByAsc(GovAsset::getName));
        assets.sort((a, b) -> {
            int c = Integer.compare(layerRank(a.getLayer()), layerRank(b.getLayer()));
            if (c != 0) {
                return c;
            }
            c = StrUtil.blankToDefault(a.getDomainCode(), "").compareToIgnoreCase(
                    StrUtil.blankToDefault(b.getDomainCode(), ""));
            if (c != 0) {
                return c;
            }
            return displayName(a).compareToIgnoreCase(displayName(b));
        });
        Map<String, CbGravAssetRef> refs = loadRefs(assets);
        List<Map<String, Object>> tree = new ArrayList<>();
        Map<String, Object> dbNode = null;
        String dbKey = null;
        for (GovAsset asset : assets) {
            String layer = StrUtil.blankToDefault(asset.getLayer(), "other").trim().toLowerCase(Locale.ROOT);
            String domain = StrUtil.blankToDefault(asset.getDomainCode(), "default").trim();
            String key = layer + "\n" + domain.toLowerCase(Locale.ROOT);
            if (!key.equals(dbKey)) {
                dbNode = newDatabase(layer, domain, tree.isEmpty());
                tree.add(dbNode);
                dbKey = key;
            }
            CbGravAssetRef ref = StrUtil.isBlank(asset.getGravAssetId()) ? null : refs.get(asset.getGravAssetId());
            boolean locked = !SecAuthGrantServiceImpl.isAssetOwner(asset, user) && !granted.contains(asset.getId());
            boolean runnable = runnable(ref);
            children(dbNode).add(newTable(asset, layer, domain, locked, runnable, ref));
            dbNode.put("tableCount", children(dbNode).size());
        }
        return tree;
    }

    public Map<String, Object> tableColumns(String assetId, String fqn) {
        GovAsset asset = resolveAsset(assetId, fqn);
        if (asset == null) {
            throw new CommonException("资产不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assetId", asset.getId());
        out.put("assetCode", asset.getAssetCode());
        out.put("fqn", platformFqn(asset));
        String layer = StrUtil.blankToDefault(asset.getLayer(), "");
        if (StrUtil.isNotBlank(layer)) {
            out.put("layer", layer.toUpperCase(Locale.ROOT));
        }
        if (!secAuthGrantService.hasTableReadGrant(asset.getId())) {
            out.put("locked", true);
            out.put("source", "platform");
            out.put("columns", List.of());
            out.put("message", "无查看权");
            return out;
        }
        out.put("locked", false);
        CbGravAssetRef ref = resolveRef(asset);
        if (ref == null) {
            out.put("source", "platform");
            out.put("columns", List.of());
            out.put("runnable", false);
            out.put("message", "未挂接查询引擎");
            return out;
        }
        boolean drift = ref.getDriftFlag() != null && ref.getDriftFlag() == 1;
        List<Map<String, Object>> cols = parseColumns(ref.getColumnsJson());
        if (drift || cols.isEmpty()) {
            cols = refreshColumns(ref, cols);
        }
        out.put("source", "platform");
        out.put("columns", cols);
        out.put("runnable", true);
        if (StrUtil.isNotBlank(ref.getRemark())) {
            out.put("comment", ref.getRemark());
        }
        return out;
    }

    private List<Map<String, Object>> refreshColumns(CbGravAssetRef ref, List<Map<String, Object>> fallback) {
        try {
            GravitinoClient.GravTable gt = gravitinoClient.loadTable(
                    ref.getGravMetalake(), ref.getGravCatalog(), ref.getGravSchema(), ref.getGravTable());
            List<Map<String, Object>> fresh = mapGravColumns(gt);
            if (fresh.isEmpty()) {
                return fallback;
            }
            ref.setColumnsJson(JSONUtil.toJsonStr(fresh));
            ref.setDriftFlag(0);
            ref.setLastSeenAt(new Date());
            ref.setUpdateTime(ref.getLastSeenAt());
            if (gt.auditVersion > 0) {
                ref.setGravRevision(gt.auditVersion);
            }
            ref.setRevision(ref.getRevision() == null ? 1 : ref.getRevision() + 1);
            gravAssetRefMapper.updateById(ref);
            return fresh;
        } catch (Exception e) {
            log.warn("column snapshot refresh skipped ref={}: {}", ref.getId(), e.getMessage());
            return fallback;
        }
    }

    private GovAsset resolveAsset(String assetId, String fqn) {
        if (StrUtil.isNotBlank(assetId)) {
            GovAsset byId = assetMapper.selectById(assetId.trim());
            if (byId != null && NOT_DELETE.equals(byId.getDeleteFlag())) {
                return byId;
            }
        }
        String raw = StrUtil.trim(fqn);
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String[] parts = raw.split("\\.");
        if (parts.length != 3 || StrUtil.hasBlank(parts)) {
            return null;
        }
        GovAsset byPlatform = assetMapper.selectOne(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .apply("lower(layer) = {0}", parts[0].trim().toLowerCase(Locale.ROOT))
                .eq(GovAsset::getDomainCode, parts[1].trim())
                .eq(GovAsset::getAssetCode, parts[2].trim())
                .last("limit 1"));
        if (byPlatform != null) {
            return byPlatform;
        }
        CbGravAssetRef ref = gravAssetRefMapper.selectOne(new QueryWrapper<CbGravAssetRef>().lambda()
                .eq(CbGravAssetRef::getGravCatalog, parts[0].trim())
                .eq(CbGravAssetRef::getGravSchema, parts[1].trim())
                .eq(CbGravAssetRef::getGravTable, parts[2].trim())
                .eq(CbGravAssetRef::getDeleteFlag, NOT_DELETE)
                .last("limit 1"));
        if (ref == null) {
            return null;
        }
        return assetMapper.selectOne(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getGravAssetId, ref.getId())
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .last("limit 1"));
    }

    private Set<String> selectGrantAssetIds(String userId) {
        Date now = new Date();
        List<SecAuthGrant> grants = grantMapper.selectList(new QueryWrapper<SecAuthGrant>().lambda()
                .eq(SecAuthGrant::getDeleteFlag, NOT_DELETE)
                .eq(SecAuthGrant::getStatus, "active")
                .eq(SecAuthGrant::getSubjectType, "user")
                .eq(SecAuthGrant::getSubjectId, userId)
                .eq(SecAuthGrant::getPrivilege, "SELECT")
                .and(w -> w.isNull(SecAuthGrant::getExpiresAt).or().gt(SecAuthGrant::getExpiresAt, now)));
        Set<String> ids = new java.util.HashSet<>();
        for (SecAuthGrant g : grants) {
            if (StrUtil.isNotBlank(g.getAssetId())) {
                ids.add(g.getAssetId());
            }
            if ("asset".equalsIgnoreCase(StrUtil.blankToDefault(g.getResourceType(), ""))
                    && StrUtil.isNotBlank(g.getResourceId())) {
                ids.add(g.getResourceId());
            }
        }
        return ids;
    }

    private Map<String, CbGravAssetRef> loadRefs(List<GovAsset> assets) {
        List<String> ids = new ArrayList<>();
        for (GovAsset asset : assets) {
            if (StrUtil.isNotBlank(asset.getGravAssetId())) {
                ids.add(asset.getGravAssetId());
            }
        }
        Map<String, CbGravAssetRef> refs = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return refs;
        }
        for (CbGravAssetRef ref : gravAssetRefMapper.selectBatchIds(ids)) {
            if (ref != null && StrUtil.isNotBlank(ref.getId())) {
                refs.put(ref.getId(), ref);
            }
        }
        return refs;
    }

    /** 库 = 分层 + 业务域，例如 ods_trade。下面直接挂表。 */
    private static Map<String, Object> newDatabase(String layer, String domain, boolean open) {
        Map<String, Object> node = new LinkedHashMap<>();
        String name = databaseName(layer, domain);
        node.put("id", "db:" + name);
        node.put("type", "database");
        node.put("name", name);
        node.put("layer", layer.toUpperCase(Locale.ROOT));
        node.put("open", open);
        node.put("locked", false);
        node.put("children", new ArrayList<Map<String, Object>>());
        node.put("tableCount", 0);
        return node;
    }

    private static String databaseName(String layer, String domain) {
        String l = layer.toLowerCase(Locale.ROOT);
        String d = domain.toLowerCase(Locale.ROOT);
        if (d.equals(l) || d.startsWith(l + "_")) {
            return d;
        }
        return l + "_" + d;
    }

    private static Map<String, Object> newTable(GovAsset asset, String layer, String domain,
                                                boolean locked, boolean runnable, CbGravAssetRef ref) {
        Map<String, Object> node = new LinkedHashMap<>();
        String shown = displayName(asset);
        node.put("id", asset.getId());
        node.put("type", "table");
        node.put("name", shown);
        node.put("assetId", asset.getId());
        node.put("assetCode", asset.getAssetCode());
        node.put("fqn", platformFqn(asset));
        node.put("layer", layer.toUpperCase(Locale.ROOT));
        node.put("locked", locked);
        node.put("runnable", runnable);
        node.put("columnsLazy", !locked && runnable);
        if (asset.getIsGold() != null && asset.getIsGold() == 1) {
            node.put("star", true);
        }
        if (StrUtil.isNotBlank(asset.getAssetCode()) && !asset.getAssetCode().equals(shown)) {
            node.put("hint", asset.getAssetCode());
        }
        if (!locked && runnable && ref != null) {
            node.put("sampleSql", sampleSql(ref, layer));
        }
        return node;
    }

    private static String sampleSql(CbGravAssetRef ref, String layer) {
        String fqn = quoteIdent(ref.getGravCatalog()) + "."
                + quoteIdent(ref.getGravSchema()) + "."
                + quoteIdent(ref.getGravTable());
        if (layer != null && layer.toLowerCase(Locale.ROOT).startsWith("ods")) {
            return "SELECT *\nFROM " + fqn + "\nWHERE dt >= date_add('day', -7, current_date)\nLIMIT 100;";
        }
        return "SELECT *\nFROM " + fqn + "\nLIMIT 100;";
    }

    private CbGravAssetRef resolveRef(GovAsset asset) {
        if (asset == null || StrUtil.isBlank(asset.getGravAssetId())) {
            return null;
        }
        CbGravAssetRef ref = gravAssetRefMapper.selectById(asset.getGravAssetId());
        return runnable(ref) ? ref : null;
    }

    private static boolean runnable(CbGravAssetRef ref) {
        if (ref == null) {
            return false;
        }
        if (StrUtil.isNotBlank(ref.getDeleteFlag()) && !NOT_DELETE.equals(ref.getDeleteFlag())) {
            return false;
        }
        if (StrUtil.equalsAnyIgnoreCase(ref.getStatus(), "missing", "archived")) {
            return false;
        }
        return !StrUtil.hasBlank(ref.getGravCatalog(), ref.getGravSchema(), ref.getGravTable());
    }

    private static String platformFqn(GovAsset asset) {
        return StrUtil.blankToDefault(asset.getLayer(), "other").trim().toLowerCase(Locale.ROOT)
                + "." + StrUtil.blankToDefault(asset.getDomainCode(), "default").trim()
                + "." + StrUtil.blankToDefault(asset.getAssetCode(), asset.getId());
    }

    private static String displayName(GovAsset asset) {
        if (StrUtil.isNotBlank(asset.getCnName())) {
            return asset.getCnName().trim();
        }
        if (StrUtil.isNotBlank(asset.getName())) {
            return asset.getName().trim();
        }
        return StrUtil.blankToDefault(asset.getAssetCode(), asset.getId());
    }

    private static int layerRank(String layer) {
        String key = StrUtil.blankToDefault(layer, "").trim().toLowerCase(Locale.ROOT);
        int i = LAYER_ORDER.indexOf(key);
        return i < 0 ? LAYER_ORDER.size() : i;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> children(Map<String, Object> node) {
        return (List<Map<String, Object>>) node.get("children");
    }

    private static List<Map<String, Object>> parseColumns(String json) {
        if (StrUtil.isBlank(json) || "[]".equals(json.trim()) || "null".equalsIgnoreCase(json.trim())) {
            return new ArrayList<>();
        }
        JSONArray arr;
        try {
            arr = JSONUtil.parseArray(json);
        } catch (Exception e) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> cols = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JSONObject c = arr.getJSONObject(i);
            if (c == null || StrUtil.isBlank(c.getStr("name"))) {
                continue;
            }
            cols.add(columnVo(c.getStr("name"), c.getStr("type"),
                    !Boolean.FALSE.equals(c.getBool("nullable")),
                    c.getStr("comment"), Boolean.TRUE.equals(c.getBool("partition"))));
        }
        return cols;
    }

    private static List<Map<String, Object>> mapGravColumns(GravitinoClient.GravTable gt) {
        List<Map<String, Object>> cols = new ArrayList<>();
        if (gt == null || gt.columns == null) {
            return cols;
        }
        List<String> parts = gt.partitionKeys == null ? List.of() : gt.partitionKeys;
        for (GravitinoClient.GravColumn c : gt.columns) {
            if (c == null || StrUtil.isBlank(c.name)) {
                continue;
            }
            boolean partition = false;
            for (String p : parts) {
                if (c.name.equalsIgnoreCase(p)) {
                    partition = true;
                    break;
                }
            }
            cols.add(columnVo(c.name, c.type, c.nullable, c.comment, partition));
        }
        return cols;
    }

    private static Map<String, Object> columnVo(String name, String type, boolean nullable,
                                                String comment, boolean partition) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", StrUtil.blankToDefault(type, "unknown"));
        m.put("nullable", nullable);
        m.put("comment", StrUtil.nullToEmpty(comment));
        m.put("partition", partition);
        m.put("masked", MASK_HINT.matcher(name).find());
        return m;
    }

    private static String quoteIdent(String id) {
        if (id == null) {
            return "\"\"";
        }
        if (id.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return id;
        }
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }
}
