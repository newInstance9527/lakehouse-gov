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

/**
 * 即席目录：门户资产 + 查询面过滤。树节点 name / sampleSql 使用 Trino 查询 FQN，不是 Grav 登记名。
 */
@Slf4j
@Component
public class CpQueryAssetCatalog {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final List<String> VISIBLE_STATUS = List.of("active", "syncing", "degraded");

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
    @Resource
    private CpTrinoQueryCatalogService queryCatalogService;
    @Resource
    private CpQueryColumnMaskResolver columnMaskResolver;

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
                .orderByAsc(GovAsset::getName));
        Map<String, CbGravAssetRef> refs = loadRefs(assets);
        boolean showUnrunnable = queryCatalogService.showUnrunnableInTree();

        List<TreeRow> rows = new ArrayList<>();
        for (GovAsset asset : assets) {
            boolean allowed = SecAuthGrantServiceImpl.isAssetOwner(asset, user)
                    || granted.contains(asset.getId());
            if (!allowed) {
                continue;
            }
            CbGravAssetRef ref = StrUtil.isBlank(asset.getGravAssetId()) ? null : refs.get(asset.getGravAssetId());
            if (!attached(ref)) {
                continue;
            }
            CpTrinoQueryCatalogService.QueryFqn qf = queryCatalogService.resolveQueryFqn(asset, ref);
            boolean runnable = qf != null;
            if (!runnable && !showUnrunnable) {
                continue;
            }
            rows.add(new TreeRow(asset, ref, qf, runnable));
        }
        rows.sort((a, b) -> {
            String ca = a.qf != null ? a.qf.catalog : StrUtil.blankToDefault(a.ref.getGravCatalog(), "");
            String cb = b.qf != null ? b.qf.catalog : StrUtil.blankToDefault(b.ref.getGravCatalog(), "");
            int c = ca.compareToIgnoreCase(cb);
            if (c != 0) {
                return c;
            }
            String sa = a.qf != null ? a.qf.schema : StrUtil.blankToDefault(a.ref.getGravSchema(), "");
            String sb = b.qf != null ? b.qf.schema : StrUtil.blankToDefault(b.ref.getGravSchema(), "");
            c = sa.compareToIgnoreCase(sb);
            if (c != 0) {
                return c;
            }
            String ta = a.qf != null ? a.qf.table : StrUtil.blankToDefault(a.ref.getGravTable(), displayName(a.asset));
            String tb = b.qf != null ? b.qf.table : StrUtil.blankToDefault(b.ref.getGravTable(), displayName(b.asset));
            return ta.compareToIgnoreCase(tb);
        });

        List<Map<String, Object>> tree = new ArrayList<>();
        Map<String, Object> dsNode = null;
        Map<String, Object> schNode = null;
        String dsKey = null;
        String schKey = null;
        for (TreeRow row : rows) {
            String catalog = row.qf != null ? row.qf.catalog : row.ref.getGravCatalog().trim();
            String schema = row.qf != null ? row.qf.schema : row.ref.getGravSchema().trim();
            if (!catalog.equalsIgnoreCase(dsKey)) {
                dsNode = newDatasource(catalog, row.asset.getEngine(), tree.isEmpty(), row.runnable);
                tree.add(dsNode);
                dsKey = catalog;
                schKey = null;
                schNode = null;
            }
            if (!schema.equalsIgnoreCase(schKey)) {
                schNode = newSchema(catalog, schema);
                children(dsNode).add(schNode);
                schKey = schema;
            }
            children(schNode).add(newTable(row));
            schNode.put("tableCount", children(schNode).size());
            dsNode.put("tableCount", countTables(dsNode));
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
        out.put("platformFqn", platformFqn(asset));
        String layer = StrUtil.blankToDefault(asset.getLayer(), "");
        if (StrUtil.isNotBlank(layer)) {
            out.put("layer", layer.toUpperCase(Locale.ROOT));
        }
        if (!secAuthGrantService.hasTableReadGrant(asset.getId())) {
            out.put("locked", true);
            out.put("source", "platform");
            out.put("columns", List.of());
            out.put("runnable", false);
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
        out.put("gravFqn", gravFqn(ref));
        CpTrinoQueryCatalogService.QueryFqn qf = queryCatalogService.resolveQueryFqn(asset, ref);
        boolean runnable = qf != null;
        out.put("runnable", runnable);
        if (qf != null) {
            out.put("fqn", qf.fqn());
            out.put("queryFqn", qf.fqn());
            out.put("queryCatalog", qf.catalog);
        } else {
            out.put("fqn", gravFqn(ref));
            out.put("message", "已登记但未进入即席查询面（登记 catalog="
                    + ref.getGravCatalog() + "；须映射到 Trino 实况 ∩ 白名单）");
        }
        boolean drift = ref.getDriftFlag() != null && ref.getDriftFlag() == 1;
        List<Map<String, Object>> cols = parseColumns(ref.getColumnsJson(), ref.getId());
        if (drift || cols.isEmpty()) {
            cols = refreshColumns(ref, cols);
        }
        out.put("source", "platform");
        out.put("columns", cols);
        if (StrUtil.isNotBlank(ref.getRemark())) {
            out.put("comment", ref.getRemark());
        }
        return out;
    }

    private List<Map<String, Object>> refreshColumns(CbGravAssetRef ref, List<Map<String, Object>> fallback) {
        try {
            GravitinoClient.GravTable gt = gravitinoClient.loadTable(
                    ref.getGravMetalake(), ref.getGravCatalog(), ref.getGravSchema(), ref.getGravTable());
            List<Map<String, Object>> fresh = mapGravColumns(gt, ref.getId());
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
        String catHint = parts[0].trim();
        String schema = parts[1].trim();
        String table = parts[2].trim();
        List<CbGravAssetRef> candidates = gravAssetRefMapper.selectList(new QueryWrapper<CbGravAssetRef>().lambda()
                .eq(CbGravAssetRef::getGravSchema, schema)
                .eq(CbGravAssetRef::getGravTable, table)
                .eq(CbGravAssetRef::getDeleteFlag, NOT_DELETE));
        CbGravAssetRef chosen = null;
        for (CbGravAssetRef c : candidates) {
            if (catHint.equalsIgnoreCase(c.getGravCatalog())) {
                chosen = c;
                break;
            }
        }
        if (chosen == null) {
            for (CbGravAssetRef c : candidates) {
                String mapped = queryCatalogService.resolveQueryCatalog(c.getGravCatalog(), null, "default");
                if (StrUtil.equalsIgnoreCase(catHint, mapped)) {
                    chosen = c;
                    break;
                }
            }
        }
        if (chosen == null && !candidates.isEmpty()) {
            chosen = candidates.get(0);
        }
        if (chosen == null) {
            return null;
        }
        return assetMapper.selectOne(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getGravAssetId, chosen.getId())
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

    private static Map<String, Object> newDatasource(String catalog, String engine, boolean open, boolean runnableHint) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "ds:" + catalog);
        node.put("type", "datasource");
        node.put("name", catalog);
        node.put("engine", StrUtil.blankToDefault(engine, inferEngine(catalog)));
        node.put("open", open);
        node.put("locked", false);
        node.put("runnable", runnableHint);
        node.put("children", new ArrayList<Map<String, Object>>());
        node.put("tableCount", 0);
        return node;
    }

    private static Map<String, Object> newSchema(String catalog, String schema) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", catalog + "." + schema);
        node.put("type", "schema");
        node.put("name", schema);
        node.put("open", false);
        node.put("locked", false);
        node.put("children", new ArrayList<Map<String, Object>>());
        node.put("tableCount", 0);
        return node;
    }

    private static Map<String, Object> newTable(TreeRow row) {
        Map<String, Object> node = new LinkedHashMap<>();
        String shown = row.qf != null
                ? row.qf.table
                : StrUtil.blankToDefault(row.ref.getGravTable(), displayName(row.asset));
        node.put("id", row.asset.getId());
        node.put("type", "table");
        node.put("name", shown);
        node.put("assetId", row.asset.getId());
        node.put("assetCode", row.asset.getAssetCode());
        node.put("gravFqn", gravFqn(row.ref));
        node.put("locked", false);
        node.put("runnable", row.runnable);
        node.put("columnsLazy", true);
        if (row.asset.getIsGold() != null && row.asset.getIsGold() == 1) {
            node.put("star", true);
        }
        String label = displayName(row.asset);
        if (StrUtil.isNotBlank(label) && !label.equalsIgnoreCase(shown)) {
            node.put("hint", label);
        }
        if (row.qf != null) {
            node.put("fqn", row.qf.fqn());
            node.put("queryFqn", row.qf.fqn());
            node.put("sampleSql", row.qf.sampleSql());
        } else {
            node.put("fqn", gravFqn(row.ref));
            node.put("message", "未进入即席查询面");
        }
        return node;
    }

    private static String gravFqn(CbGravAssetRef ref) {
        return ref.getGravCatalog() + "." + ref.getGravSchema() + "." + ref.getGravTable();
    }

    private static int countTables(Map<String, Object> dsNode) {
        int n = 0;
        for (Map<String, Object> sch : children(dsNode)) {
            n += children(sch).size();
        }
        return n;
    }

    private static String inferEngine(String catalog) {
        String c = catalog == null ? "" : catalog.toLowerCase(Locale.ROOT);
        if (c.contains("clickhouse") || c.startsWith("ck")) {
            return "ClickHouse";
        }
        if (c.contains("mysql")) {
            return "MySQL";
        }
        if (c.contains("hive")) {
            return "Hive";
        }
        if (c.contains("iceberg") || c.contains("lake")) {
            return "Iceberg";
        }
        return "Trino";
    }

    private CbGravAssetRef resolveRef(GovAsset asset) {
        if (asset == null || StrUtil.isBlank(asset.getGravAssetId())) {
            return null;
        }
        CbGravAssetRef ref = gravAssetRefMapper.selectById(asset.getGravAssetId());
        return attached(ref) ? ref : null;
    }

    private static boolean attached(CbGravAssetRef ref) {
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> children(Map<String, Object> node) {
        return (List<Map<String, Object>>) node.get("children");
    }

    private List<Map<String, Object>> parseColumns(String json, String gravAssetId) {
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
            String name = c.getStr("name");
            boolean masked = Boolean.TRUE.equals(c.getBool("masked"))
                    || columnMaskResolver.isColumnMasked(gravAssetId, name);
            cols.add(columnVo(name, c.getStr("type"),
                    !Boolean.FALSE.equals(c.getBool("nullable")),
                    c.getStr("comment"), Boolean.TRUE.equals(c.getBool("partition")), masked));
        }
        return cols;
    }

    private List<Map<String, Object>> mapGravColumns(GravitinoClient.GravTable gt, String gravAssetId) {
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
            boolean masked = c.masked || columnMaskResolver.isColumnMasked(gravAssetId, c.name);
            cols.add(columnVo(c.name, c.type, c.nullable, c.comment, partition, masked));
        }
        return cols;
    }

    private static Map<String, Object> columnVo(String name, String type, boolean nullable,
                                                String comment, boolean partition, boolean masked) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", StrUtil.blankToDefault(type, "unknown"));
        m.put("nullable", nullable);
        m.put("comment", StrUtil.nullToEmpty(comment));
        m.put("partition", partition);
        m.put("masked", masked);
        return m;
    }

    private static final class TreeRow {
        final GovAsset asset;
        final CbGravAssetRef ref;
        final CpTrinoQueryCatalogService.QueryFqn qf;
        final boolean runnable;

        TreeRow(GovAsset asset, CbGravAssetRef ref, CpTrinoQueryCatalogService.QueryFqn qf, boolean runnable) {
            this.asset = asset;
            this.ref = ref;
            this.qf = qf;
            this.runnable = runnable;
        }
    }
}
