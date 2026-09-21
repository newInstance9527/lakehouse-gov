package vip.xiaonuo.lh.modular.etl.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.auth.core.util.StpLoginUserUtil;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.auth.LhOwnerGuard;
import vip.xiaonuo.lh.core.engine.GravitinoClient;
import vip.xiaonuo.lh.core.engine.IgEtlNodeTypes;
import vip.xiaonuo.lh.core.engine.TrinoClient;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.catalog.preview.GovAssetPreviewContext;
import vip.xiaonuo.lh.modular.catalog.preview.GovAssetPreviewRouter;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.mapper.LhDatasourceMapper;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlDag;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlNode;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlRun;
import vip.xiaonuo.lh.modular.etl.entity.IgEtlRunNode;
import vip.xiaonuo.lh.modular.etl.mapper.IgEtlDagMapper;
import vip.xiaonuo.lh.modular.etl.mapper.IgEtlNodeMapper;
import vip.xiaonuo.lh.modular.etl.mapper.IgEtlRunMapper;
import vip.xiaonuo.lh.modular.etl.mapper.IgEtlRunNodeMapper;
import vip.xiaonuo.lh.modular.schemasync.entity.CbGravAssetRef;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbGravAssetRefMapper;
import vip.xiaonuo.lh.modular.sec.service.LhTrinoPrincipalService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 试跑/运行成功后预览 sink 目标表样本行（核对结果）。
 * <p>P0：sink_iceberg / sink_ck / sink_rdb；复用目录 JDBC PreviewAdapter；湖表走 Grav+Trino。</p>
 */
@Slf4j
@Component
public class IgEtlRunResultPreview {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final Set<String> PREVIEWABLE = Set.of("sink_iceberg", "sink_ck", "sink_rdb");
    private static final Set<String> EXPORT_TICKET = Set.of(
            "sink_rdb", "sink_ftp", "sink_bi", "sink_search");

    @Resource
    private IgEtlRunMapper runMapper;
    @Resource
    private IgEtlRunNodeMapper runNodeMapper;
    @Resource
    private IgEtlNodeMapper nodeMapper;
    @Resource
    private IgEtlDagMapper dagMapper;
    @Resource
    private IgEtlSinkTargetChecker sinkTargetChecker;
    @Resource
    private LhDatasourceMapper datasourceMapper;
    @Resource
    private GovAssetPreviewRouter previewRouter;
    @Resource
    private GovAssetMapper assetMapper;
    @Resource
    private CbGravAssetRefMapper gravAssetRefMapper;
    @Resource
    private LhTrinoPrincipalService principalService;
    @Resource
    private TrinoClient trinoClient;
    @Resource
    private GravitinoClient gravitinoClient;

    public Map<String, Object> preview(String runId, String nodeKey, Integer limit) {
        if (StrUtil.isBlank(runId) || StrUtil.isBlank(nodeKey)) {
            throw new CommonException("runId / nodeKey 不能为空");
        }
        IgEtlRun run = runMapper.selectOne(new QueryWrapper<IgEtlRun>().lambda()
                .eq(IgEtlRun::getRunId, runId.trim()));
        if (run == null) {
            throw new CommonException("运行不存在: " + runId);
        }
        IgEtlDag dag = dagMapper.selectById(run.getDagId());
        if (dag == null) {
            throw new CommonException("DAG 不存在");
        }
        String key = nodeKey.trim();
        IgEtlNode node = nodeMapper.selectOne(new QueryWrapper<IgEtlNode>().lambda()
                .eq(IgEtlNode::getDagId, run.getDagId())
                .eq(IgEtlNode::getNodeKey, key)
                .last("LIMIT 1"));
        if (node == null) {
            throw new CommonException("节点不存在: " + key);
        }
        String type = StrUtil.blankToDefault(node.getNodeType(), "");
        int lim = limit == null ? 20 : Math.max(1, Math.min(50, limit));

        Map<String, Object> base = new LinkedHashMap<>();
        base.put("runId", run.getRunId());
        base.put("dagId", run.getDagId());
        base.put("nodeKey", key);
        base.put("nodeType", type);
        base.put("nodeName", node.getName());
        base.put("runStatus", run.getStatus());
        base.put("env", run.getEnv());
        base.put("limit", lim);

        if (!IgEtlNodeTypes.SINK.contains(type)) {
            return fail(base, "unsupported", "仅 sink 节点支持结果预览");
        }
        if (!PREVIEWABLE.contains(type)) {
            return fail(base, "unsupported",
                    type + " 暂不支持行级预览（P0 仅 sink_iceberg / sink_ck / sink_rdb）");
        }

        IgEtlRunNode rn = runNodeMapper.selectOne(new QueryWrapper<IgEtlRunNode>().lambda()
                .eq(IgEtlRunNode::getRunId, run.getRunId())
                .eq(IgEtlRunNode::getNodeKey, key)
                .last("LIMIT 1"));
        String nodeStatus = rn == null ? null : rn.getStatus();
        base.put("nodeStatus", nodeStatus);
        if (!isSuccess(run.getStatus()) && !isSuccess(nodeStatus)) {
            return fail(base, "not_ready",
                    "运行尚未成功，请待试跑 SUCCESS 后再核对目标数据（当前 run="
                            + run.getStatus() + ", node=" + StrUtil.blankToDefault(nodeStatus, "—") + "）");
        }

        JSONObject conf = parseConf(node.getConfJson());
        if (EXPORT_TICKET.contains(type) && StrUtil.isBlank(conf.getStr("ticketNo"))) {
            return fail(base, "ticket", "出湖节点缺少 ticketNo，禁止预览目标库");
        }

        IgEtlSinkTargetChecker.TargetRef ref = sinkTargetChecker.resolveSinkTarget(type, conf);
        if (ref == null || StrUtil.isBlank(ref.table)) {
            return fail(base, "no_target", "无法解析目标表：请检查节点 table / schema / dsId");
        }
        base.put("target", ref.fqn());
        base.put("targetCatalog", ref.catalog);
        base.put("targetSchema", ref.schema);
        base.put("targetTable", ref.table);

        GovAsset asset = findLinkedAsset(ref);
        if (asset != null) {
            base.put("assetId", asset.getId());
            base.put("assetCode", asset.getAssetCode());
            if (!canReadAsset(asset, dag)) {
                base.put("ok", false);
                base.put("source", "denied");
                base.put("needApply", true);
                base.put("message", "看见≠能查：无目标资产读授权且非 DAG 拥有者，请走申请中心");
                base.put("columns", List.of());
                base.put("rows", List.of());
                base.put("rowCount", 0);
                return base;
            }
        }

        if ("sink_rdb".equals(type) || ("sink_ck".equals(type) && StrUtil.isNotBlank(conf.getStr("dsId")))) {
            return previewJdbc(base, conf, ref, lim);
        }
        return previewLake(base, ref, lim);
    }

    private Map<String, Object> previewJdbc(Map<String, Object> base, JSONObject conf,
                                            IgEtlSinkTargetChecker.TargetRef ref, int lim) {
        String dsId = conf.getStr("dsId");
        if (StrUtil.isBlank(dsId)) {
            return fail(base, "no_ds", "sink 未绑定 dsId，无法 JDBC 预览");
        }
        LhDatasource ds = datasourceMapper.selectById(dsId);
        if (ds == null) {
            return fail(base, "no_ds", "数据源不存在: " + dsId);
        }
        String objectName = StrUtil.isNotBlank(ref.schema)
                ? ref.schema + "." + ref.table
                : ref.table;
        base.put("dsId", ds.getId());
        base.put("dsName", StrUtil.blankToDefault(ds.getName(), ds.getDsCode()));
        GovAssetPreviewContext ctx = GovAssetPreviewContext.builder()
                .primaryDs(ds)
                .objectName(objectName)
                .limit(lim)
                .base(base)
                .build();
        Map<String, Object> r = previewRouter.preview(ctx);
        if (r != null) {
            r.putIfAbsent("previewMode", "etl_result");
            r.putIfAbsent("message", StrUtil.blankToDefault(
                    str(r.get("message")), "试跑结果 JDBC 样本预览（LIMIT " + lim + "）"));
        }
        return r;
    }

    private Map<String, Object> previewLake(Map<String, Object> base,
                                            IgEtlSinkTargetChecker.TargetRef ref, int lim) {
        String qualified = quote(ref.catalog) + "." + quote(ref.schema) + "." + quote(ref.table);
        base.put("qualifiedName", qualified);
        List<String> columns = new ArrayList<>();
        String gravMsg = null;
        try {
            GravitinoClient.GravTable gt = gravitinoClient.loadTable(
                    ref.metalake, ref.catalog, ref.schema, ref.table);
            if (gt != null && gt.columns != null) {
                for (GravitinoClient.GravColumn c : gt.columns) {
                    if (c != null && StrUtil.isNotBlank(c.name)) {
                        columns.add(c.name);
                    }
                }
            }
        } catch (Exception e) {
            gravMsg = StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
            log.warn("ETL result Grav load soft-fail {}: {}", qualified, gravMsg);
        }

        String sql = "SELECT * FROM " + qualified + " LIMIT " + lim;
        base.put("sql", sql);
        try {
            TrinoClient.ExecuteOptions opts = lakeReadOptions(lim);
            Map<String, Object> exec = trinoClient.execute(sql, opts);
            if (Boolean.TRUE.equals(exec.get("degraded"))) {
                Map<String, Object> fail = fail(base, "trino",
                        "Trino 不可用: " + StrUtil.blankToDefault(str(exec.get("message")), "degraded")
                                + (gravMsg == null ? "" : "；Grav: " + gravMsg));
                if (!columns.isEmpty()) {
                    fail.put("columns", columns);
                    fail.put("message", fail.get("message") + "（已返回 Grav 列结构）");
                }
                fail.put("degraded", true);
                return fail;
            }
            @SuppressWarnings("unchecked")
            List<String> cols = (List<String>) exec.get("columns");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) exec.get("rows");
            if (cols != null && !cols.isEmpty()) {
                columns = cols;
            }
            base.put("ok", true);
            base.put("source", "trino");
            base.put("previewMode", "etl_result");
            base.put("columns", columns);
            base.put("rows", rows == null ? List.of() : rows);
            base.put("rowCount", rows == null ? 0 : rows.size());
            base.put("message", "试跑结果湖表样本预览（Grav 坐标 + Trino LIMIT "
                    + lim + (gravMsg == null ? "" : "；Grav 提示: " + gravMsg) + "）");
            return base;
        } catch (Exception e) {
            log.warn("ETL result Trino preview fail {}: {}", qualified, e.getMessage());
            Map<String, Object> fail = fail(base, "trino",
                    "湖表预览失败: " + StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName())
                            + (gravMsg == null ? "" : "；Grav: " + gravMsg));
            if (!columns.isEmpty()) {
                fail.put("columns", columns);
            }
            fail.put("degraded", true);
            return fail;
        }
    }

    private GovAsset findLinkedAsset(IgEtlSinkTargetChecker.TargetRef ref) {
        if (ref == null || StrUtil.isBlank(ref.table)) {
            return null;
        }
        try {
            CbGravAssetRef grav = gravAssetRefMapper.selectOne(new QueryWrapper<CbGravAssetRef>().lambda()
                    .eq(CbGravAssetRef::getGravCatalog, ref.catalog)
                    .eq(CbGravAssetRef::getGravSchema, ref.schema)
                    .eq(CbGravAssetRef::getGravTable, ref.table)
                    .eq(CbGravAssetRef::getDeleteFlag, NOT_DELETE)
                    .last("LIMIT 1"));
            if (grav == null) {
                return null;
            }
            return assetMapper.selectOne(new QueryWrapper<GovAsset>().lambda()
                    .eq(GovAsset::getGravAssetId, grav.getId())
                    .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                    .last("LIMIT 1"));
        } catch (Exception e) {
            log.debug("findLinkedAsset soft-fail: {}", e.getMessage());
            return null;
        }
    }

    private boolean canReadAsset(GovAsset asset, IgEtlDag dag) {
        if (asset == null) {
            return true;
        }
        SaBaseLoginUser user = null;
        try {
            user = StpLoginUserUtil.getLoginUser();
        } catch (Exception ignored) {
            // 独立前端无登录：与 previewSchema 一致，允许作业路径预览
        }
        if (user == null || user.getId() == null) {
            return true;
        }
        if (principalService.findActive(user.getId()) != null) {
            return true;
        }
        Set<String> ids = LhOwnerGuard.identitiesOf(user);
        if (dag != null) {
            if (LhOwnerGuard.matchesOwnerField(dag.getOwner(), ids)) {
                return true;
            }
            if (LhOwnerGuard.matchesOwnerField(dag.getCreateUser(), ids)) {
                return true;
            }
        }
        return false;
    }

    /** 登录用户按映射主体代执行；无登录的作业路径用服务账号。 */
    private TrinoClient.ExecuteOptions lakeReadOptions(int lim) {
        SaBaseLoginUser user = null;
        try {
            user = StpLoginUserUtil.getLoginUser();
        } catch (Exception ignored) {
        }
        if (user == null || user.getId() == null) {
            return TrinoClient.ExecuteOptions.job(lim);
        }
        return TrinoClient.ExecuteOptions.human(
                principalService.requireByPortalUserId(user.getId()).getTrinoUser(), lim);
    }

    private static boolean isSuccess(String status) {
        if (StrUtil.isBlank(status)) {
            return false;
        }
        String s = status.trim().toLowerCase(Locale.ROOT);
        return "success".equals(s) || "succeeded".equals(s) || "ok".equals(s);
    }

    private static Map<String, Object> fail(Map<String, Object> base, String source, String message) {
        Map<String, Object> r = base == null ? new LinkedHashMap<>() : base;
        r.put("ok", false);
        r.put("source", source);
        r.put("message", message);
        r.put("columns", List.of());
        r.put("rows", List.of());
        r.put("rowCount", 0);
        return r;
    }

    private static JSONObject parseConf(String confJson) {
        if (StrUtil.isBlank(confJson)) {
            return JSONUtil.createObj();
        }
        try {
            return JSONUtil.parseObj(confJson);
        } catch (Exception e) {
            return JSONUtil.createObj();
        }
    }

    private static String quote(String ident) {
        if (StrUtil.isBlank(ident)) {
            return "\"\"";
        }
        String s = ident.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) {
            return s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
