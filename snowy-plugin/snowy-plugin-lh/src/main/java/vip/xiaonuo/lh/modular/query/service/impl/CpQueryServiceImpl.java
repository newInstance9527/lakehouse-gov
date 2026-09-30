package vip.xiaonuo.lh.modular.query.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.auth.core.util.StpLoginUserUtil;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.core.engine.TrinoClient;
import vip.xiaonuo.lh.modular.query.entity.CpQueryDataset;
import vip.xiaonuo.lh.modular.query.entity.CpQueryExec;
import vip.xiaonuo.lh.modular.query.entity.CpQuerySaved;
import vip.xiaonuo.lh.modular.query.mapper.CpQueryDatasetMapper;
import vip.xiaonuo.lh.modular.query.mapper.CpQueryExecMapper;
import vip.xiaonuo.lh.modular.query.mapper.CpQuerySavedMapper;
import vip.xiaonuo.lh.modular.query.param.CpQueryCancelParam;
import vip.xiaonuo.lh.modular.query.param.CpQueryDatasetSaveParam;
import vip.xiaonuo.lh.modular.query.param.CpQueryExecParam;
import vip.xiaonuo.lh.modular.query.param.CpQueryExportParam;
import vip.xiaonuo.lh.modular.query.param.CpQueryHistoryParam;
import vip.xiaonuo.lh.modular.query.param.CpQuerySavedSaveParam;
import vip.xiaonuo.lh.modular.query.service.CpQueryService;
import vip.xiaonuo.lh.modular.query.support.CpQueryAssetCatalog;
import vip.xiaonuo.lh.modular.query.support.CpQueryCatalogGuard;
import vip.xiaonuo.lh.modular.query.support.CpQueryColumnMaskResolver;
import vip.xiaonuo.lh.modular.query.support.CpQueryConcurrencyGuard;
import vip.xiaonuo.lh.modular.query.support.CpQueryDatasetObjectKeys;
import vip.xiaonuo.lh.modular.query.support.CpQueryDatasetObjectStore;
import vip.xiaonuo.lh.modular.query.support.CpQueryElevateGate;
import vip.xiaonuo.lh.modular.query.support.CpQueryParamBinder;
import vip.xiaonuo.lh.modular.query.support.CpQueryPrincipalMapper;
import vip.xiaonuo.lh.modular.query.support.CpQueryRowFilterInjector;
import vip.xiaonuo.lh.modular.query.support.CpQueryScanGuard;
import vip.xiaonuo.lh.modular.query.support.CpTrinoQueryCatalogService;
import vip.xiaonuo.lh.modular.sec.entity.LhTrinoPrincipal;
import vip.xiaonuo.lh.modular.sec.service.LhTrinoPrincipalService;
import vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 即席查询：治理拦截 → Trino → cp_query_exec 审计
 *
 * @author lakehouse
 * @date 2026/9/21
 */
@Service
public class CpQueryServiceImpl implements CpQueryService {

    private static final int DEFAULT_MAX_ROWS = 1000;
    private static final int HISTORY_DEFAULT = 30;

    @Resource
    private CpQueryExecMapper execMapper;
    @Resource
    private CpQueryDatasetMapper datasetMapper;
    @Resource
    private CpQuerySavedMapper savedMapper;
    @Resource
    private TrinoClient trinoClient;
    @Resource
    private LhTrinoPrincipalService principalService;
    @Resource
    private CpQueryAssetCatalog assetCatalog;
    @Resource
    private CpTrinoQueryCatalogService queryCatalogService;
    @Resource
    private CpQueryColumnMaskResolver columnMaskResolver;
    @Resource
    private CpQueryRowFilterInjector rowFilterInjector;
    @Resource
    private SecAuthGrantService secAuthGrantService;
    @Resource
    private CpQueryDatasetObjectStore datasetObjectStore;

    @Override
    public Map<String, Object> exec(CpQueryExecParam param) {
        return exec(param, null);
    }

    /**
     * @param onProgress 可选进度回调（SSE）
     */
    public Map<String, Object> exec(CpQueryExecParam param, TrinoClient.ProgressListener onProgress) {
        String rawSql = StrUtil.trim(param.getSql());
        if (StrUtil.isBlank(rawSql)) {
            throw new CommonException("SQL 不能为空");
        }
        String sql;
        try {
            sql = CpQueryParamBinder.bind(rawSql, param.getParams());
        } catch (IllegalArgumentException e) {
            throw new CommonException(e.getMessage());
        }
        // Trino POST /v1/statement 只接受一条语句，且不能带结尾分号。只去掉末尾一个终结符。
        sql = TrinoClient.singleStatement(sql);
        if (StrUtil.isBlank(sql)) {
            throw new CommonException("SQL 不能为空");
        }
        String ws = StrUtil.blankToDefault(param.getWs(), "default");
        int maxRows = param.getMaxRows() == null || param.getMaxRows() <= 0
                ? DEFAULT_MAX_ROWS : Math.min(param.getMaxRows(), 10000);

        UserSnap user = currentUser();
        String queryId = newQueryId();
        CpQueryExec row = new CpQueryExec();
        row.setId(IdUtil.getSnowflakeNextIdStr());
        row.setQueryId(queryId);
        row.setWs(ws);
        row.setUserId(user.id);
        row.setUserName(user.name);
        row.setSqlText(sql);
        row.setSqlHash(DigestUtil.sha256Hex(sql).substring(0, 32));
        row.setSqlSummary(summarize(sql));
        row.setEngine("trino");
        row.setCatalogName(StrUtil.blankToDefault(param.getCatalog(), "iceberg"));
        row.setSchemaName(StrUtil.blankToDefault(param.getSchema(), "default"));
        row.setStatus("running");
        row.setStatusLabel("执行中");
        row.setDeleteFlag("NOT_DELETE");
        row.setCreateTime(new Date());
        row.setCreateUser(user.id);
        row.setUpdateTime(row.getCreateTime());
        row.setUpdateUser(user.id);
        execMapper.insert(row);

        boolean force = Boolean.TRUE.equals(param.getForce());
        boolean elevatedRequested = Boolean.TRUE.equals(param.getElevated());
        boolean elevatedAllowed = secAuthGrantService.hasScanElevateGrant();
        boolean elevated = elevatedRequested && elevatedAllowed;
        if (elevatedRequested && !elevatedAllowed) {
            String msg = CpQueryElevateGate.denyMessage();
            row.setStatus("blocked");
            row.setStatusLabel("⚠ 抬额未授权");
            row.setErrorMsg(StrUtil.maxLength(msg, 1000));
            row.setDurMs(0L);
            row.setRowCount(0);
            row.setUpdateTime(new Date());
            execMapper.updateById(row);
            Map<String, Object> denied = CpQueryElevateGate.deniedPayload();
            denied.put("queryId", queryId);
            denied.put("id", row.getId());
            return denied;
        }
        // 查询面闸门：会话 catalog + SQL 三元组 catalog 须 ∈ 白名单 ∩ SHOW CATALOGS
        String deniedCat = CpQueryCatalogGuard.firstDenied(sql, row.getCatalogName(), queryCatalogService);
        if (deniedCat != null) {
            String msg = queryCatalogService.denyMessage(deniedCat);
            if (CpQueryCatalogGuard.looksLikeRegistrationOnly(deniedCat)) {
                msg = "禁止使用 Grav 登记名 " + deniedCat
                        + " 作为 Trino catalog；湖表请用 iceberg.schema.table，联邦源须先开通映射";
            }
            row.setStatus("blocked");
            row.setStatusLabel("⚠ 数据源未进入查询面");
            row.setErrorMsg(StrUtil.maxLength(msg, 1000));
            row.setDurMs(0L);
            row.setRowCount(0);
            row.setUpdateTime(new Date());
            execMapper.updateById(row);
            Map<String, Object> blocked = CpQueryScanGuard.blockedPayload(msg);
            blocked.put("queryId", queryId);
            blocked.put("id", row.getId());
            blocked.put("errorCode", "CATALOG_NOT_IN_QUERY_SURFACE");
            blocked.put("deniedCatalog", deniedCat);
            blocked.put("queryable", new ArrayList<>(queryCatalogService.queryableCatalogs()));
            return blocked;
        }
        String block = force ? null : CpQueryScanGuard.blockReason(sql);
        if (block != null) {
            row.setStatus("blocked");
            row.setStatusLabel("⚠ " + block);
            row.setErrorMsg(block);
            row.setDurMs(0L);
            row.setRowCount(0);
            row.setUpdateTime(new Date());
            execMapper.updateById(row);
            Map<String, Object> blocked = CpQueryScanGuard.blockedPayload(block);
            blocked.put("queryId", queryId);
            blocked.put("id", row.getId());
            blocked.put("errorCode", "SCAN_RULE_BLOCKED");
            return blocked;
        }

        if (!force && !CpQueryConcurrencyGuard.tryAcquire()) {
            String msg = CpQueryConcurrencyGuard.rejectMessage();
            row.setStatus("blocked");
            row.setStatusLabel("⚠ 并发限额");
            row.setErrorMsg(msg);
            row.setDurMs(0L);
            row.setRowCount(0);
            row.setUpdateTime(new Date());
            execMapper.updateById(row);
            Map<String, Object> blocked = CpQueryScanGuard.blockedPayload(msg);
            blocked.put("queryId", queryId);
            blocked.put("id", row.getId());
            blocked.put("errorCode", "CONCURRENCY_LIMIT");
            blocked.put("adhocConcurrent", CpQueryConcurrencyGuard.current());
            blocked.put("adhocMaxConcurrent", CpQueryConcurrencyGuard.max());
            return blocked;
        }

        long t0 = System.currentTimeMillis();
        CpQueryPrincipalMapper.Principal principal = resolvePrincipal(user);
        String subjectForPolicy = StrUtil.blankToDefault(user.id, principal.trinoUser);
        CpQueryRowFilterInjector.InjectResult rowFilter =
                rowFilterInjector.inject(sql, subjectForPolicy);
        String sqlOriginal = sql;
        if (rowFilter.applied && StrUtil.isNotBlank(rowFilter.sql) && !rowFilter.sql.equals(sql)) {
            sql = rowFilter.sql;
            row.setSqlText(sql);
            row.setSqlHash(DigestUtil.sha256Hex(sql).substring(0, 32));
            row.setSqlSummary(summarize(sqlOriginal) + " · 行级注入");
            row.setUpdateTime(new Date());
            execMapper.updateById(row);
        }

        TrinoClient.ExecuteOptions opts = TrinoClient.ExecuteOptions.human(principal.trinoUser, maxRows);
        opts.catalog = row.getCatalogName();
        opts.schema = row.getSchemaName();
        opts.trinoUser = principal.trinoUser;
        opts.source = "lakehouse-portal-adhoc";
        opts.clientTags = CpQueryPrincipalMapper.clientTags(principal, ws);
        opts.maxScanBytes = elevated
                ? CpQueryScanGuard.PLATFORM_HARD_SCAN_BYTES
                : CpQueryScanGuard.ADHOC_DEFAULT_SCAN_BYTES;
        opts.sessionProps = CpQueryPrincipalMapper.sessionProps(principal, ws, elevated, opts.maxScanBytes);
        opts.onProgress = onProgress;

        Map<String, Object> exec;
        try {
            exec = trinoClient.execute(sql, opts);
        } catch (CommonException e) {
            long dur = System.currentTimeMillis() - t0;
            String msg = StrUtil.blankToDefault(e.getMessage(), "执行失败");
            boolean sessionInvalid = CpQueryScanGuard.isInvalidSessionProperty(msg);
            boolean scanHard = CpQueryScanGuard.isEngineScanLimitExceeded(msg);
            boolean denied = isAccessDenied(msg);
            boolean impersonationDenied = isImpersonationDenied(msg);
            row.setStatus(scanHard ? "blocked" : (denied ? "blocked" : "failed"));
            row.setStatusLabel(scanHard ? "⚠ 超扫描限额（引擎硬拒）"
                    : (impersonationDenied ? "⚠ 代执行未开通"
                    : (denied ? "⚠ 未授权"
                    : (sessionInvalid ? "⚠ Trino会话属性无效" : "失败"))));
            row.setErrorMsg(StrUtil.maxLength(msg, 1000));
            row.setDurMs(dur);
            row.setUpdateTime(new Date());
            execMapper.updateById(row);
            if (sessionInvalid) {
                Map<String, Object> failed = new LinkedHashMap<>();
                failed.put("queryId", queryId);
                failed.put("id", row.getId());
                failed.put("blocked", false);
                failed.put("status", "failed");
                failed.put("statusLabel", row.getStatusLabel());
                failed.put("message", msg);
                failed.put("errorCode", "TRINO_SESSION");
                failed.put("scanOverLimit", false);
                failed.put("scanLimitBytes", opts.maxScanBytes);
                failed.put("scanHardLimitBytes", CpQueryScanGuard.PLATFORM_HARD_SCAN_BYTES);
                return failed;
            }
            if (scanHard) {
                Map<String, Object> blocked = CpQueryScanGuard.blockedPayload(msg);
                blocked.put("queryId", queryId);
                blocked.put("id", row.getId());
                blocked.put("scanOverLimit", true);
                blocked.put("message", msg);
                blocked.put("errorCode", "SCAN_LIMIT");
                return blocked;
            }
            if (impersonationDenied) {
                Map<String, Object> blocked = CpQueryScanGuard.blockedPayload(
                        "Trino 服务账号无法代执行映射主体。请配置 lh.trino.impersonation-rules-path 并 POST /lh/sec/principals/impersonation-sync（或见 deploy/trino/README-impersonation.md）。"
                                + "申请 SELECT 无法解决此错误。");
                blocked.put("queryId", queryId);
                blocked.put("id", row.getId());
                blocked.put("errorCode", "IMPERSONATION_DENIED");
                blocked.put("applyHint", false);
                blocked.put("message", msg);
                blocked.put("trinoUser", principal.trinoUser);
                return blocked;
            }
            if (denied) {
                Map<String, Object> blocked = CpQueryScanGuard.blockedPayload(msg);
                blocked.put("queryId", queryId);
                blocked.put("id", row.getId());
                blocked.put("errorCode", "ACCESS_DENIED");
                blocked.put("applyHint", true);
                blocked.put("applyPath", "/apply?type=perm&privilege=SELECT");
                blocked.put("message", msg);
                return blocked;
            }
            throw e;
        } finally {
            CpQueryConcurrencyGuard.release();
        }

        long dur = System.currentTimeMillis() - t0;
        boolean degraded = Boolean.TRUE.equals(exec.get("degraded"));
        @SuppressWarnings("unchecked")
        List<String> columns = (List<String>) exec.getOrDefault("columns", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) exec.getOrDefault("rows", List.of());
        CpQueryColumnMaskResolver.MaskResult mask = columnMaskResolver.resolve(
                columns,
                sql,
                StrUtil.blankToDefault(user.id, principal.trinoUser),
                CpQueryColumnMaskResolver.engineMaskColsFromExec(exec));
        List<String> maskCols = mask.maskCols;

        String trinoQid = str(exec.get("trinoQueryId"));
        Long scanBytes = asLong(exec.get("scanBytes"));
        Long elapsed = asLong(exec.get("elapsedMs"));
        if (elapsed != null && elapsed > 0) {
            dur = elapsed;
        }

        long limitBytes = elevated
                ? CpQueryScanGuard.PLATFORM_HARD_SCAN_BYTES
                : CpQueryScanGuard.ADHOC_DEFAULT_SCAN_BYTES;
        String scanOver = force ? null : CpQueryScanGuard.scanOverLimitReason(scanBytes, elevated);
        boolean scanOverLimit = scanOver != null;

        if (degraded) {
            row.setStatus("failed");
            row.setStatusLabel("Trino 不可达");
            row.setErrorMsg(StrUtil.maxLength(str(exec.get("message")), 1000));
        } else if (scanOverLimit) {
            // Phase A：查询已完成，按限额标记阻断态并保留结果供排查；正式可接 Trino session 硬拒
            row.setStatus("blocked");
            row.setStatusLabel("⚠ 超扫描限额");
            row.setErrorMsg(StrUtil.maxLength(scanOver, 1000));
        } else {
            StringBuilder label = new StringBuilder("✓");
            if (!maskCols.isEmpty()) {
                label.append(" · 脱敏 ").append(maskCols.size()).append("列");
            }
            if (rowFilter.applied && !rowFilter.predicates.isEmpty()) {
                label.append(" · 行级 ").append(rowFilter.predicates.size()).append("表");
            }
            row.setStatus("ok");
            row.setStatusLabel(label.toString());
        }
        row.setTrinoQueryId(trinoQid);
        row.setRowCount(rows == null ? 0 : rows.size());
        row.setScanBytes(scanBytes);
        row.setDurMs(dur);
        row.setMaskCols(maskCols.isEmpty() ? null : String.join(",", maskCols));
        row.setUpdateTime(new Date());
        execMapper.updateById(row);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queryId", queryId);
        out.put("id", row.getId());
        out.put("trinoQueryId", trinoQid);
        out.put("status", row.getStatus());
        out.put("statusLabel", row.getStatusLabel());
        out.put("columns", columns);
        out.put("columnMeta", columnMeta(columns, maskCols));
        out.put("rows", rows);
        out.put("rowCount", row.getRowCount());
        out.put("scanBytes", scanBytes);
        out.put("scan", formatBytes(scanBytes));
        out.put("scanLimitBytes", limitBytes);
        out.put("scanHardLimitBytes", CpQueryScanGuard.PLATFORM_HARD_SCAN_BYTES);
        out.put("scanLimit", formatBytes(limitBytes));
        out.put("scanOverLimit", scanOverLimit);
        out.put("elevated", elevated);
        out.put("elevatedAllowed", elevatedAllowed);
        out.put("durMs", dur);
        out.put("duration", formatDur(dur));
        out.put("truncated", exec.get("truncated"));
        out.put("maxRows", maxRows);
        out.put("maskCols", maskCols);
        out.put("masked", !maskCols.isEmpty());
        out.put("maskSource", mask.maskSource);
        out.put("maskDegraded", mask.maskDegraded);
        if (StrUtil.isNotBlank(mask.maskMessage)) {
            out.put("maskMessage", mask.maskMessage);
        }
        out.put("rowFilterApplied", rowFilter.applied);
        out.put("rowFilterSource", rowFilter.source);
        out.put("rowFilterDegraded", rowFilter.degraded);
        out.put("rowFilterPredicates", rowFilter.predicates);
        if (StrUtil.isNotBlank(rowFilter.message)) {
            out.put("rowFilterMessage", rowFilter.message);
        }
        if (rowFilter.applied) {
            out.put("sqlOriginal", sqlOriginal);
            out.put("sqlRewritten", true);
        }
        out.put("degraded", degraded);
        if (degraded) {
            out.put("message", exec.get("message"));
        } else if (scanOverLimit) {
            out.put("message", scanOver);
            out.put("blocked", true);
        }
        out.put("authHint", "X-Trino-User=" + principal.trinoUser
                + " · Basic=服务账号仅代执行 · 表权限由 Gravitino 裁决");
        out.put("trinoUser", principal.trinoUser);
        out.put("adhocConcurrent", CpQueryConcurrencyGuard.current());
        out.put("adhocMaxConcurrent", CpQueryConcurrencyGuard.max());
        out.put("stages", exec.get("stages"));
        out.put("paramNames", CpQueryParamBinder.detectParams(rawSql));
        String ui = trinoClient.resolveUiUrl();
        if (StrUtil.isNotBlank(ui) && StrUtil.isNotBlank(trinoQid)) {
            out.put("trinoUiUrl", ui + "/ui/query.html?" + trinoQid);
        } else if (StrUtil.isNotBlank(ui)) {
            out.put("trinoUiUrl", ui + "/ui/");
        }
        return out;
    }

    @Override
    public Map<String, Object> cancel(CpQueryCancelParam param) {
        String queryId = StrUtil.trim(param.getQueryId());
        String trinoQid = StrUtil.trim(param.getTrinoQueryId());
        CpQueryExec row = null;
        if (StrUtil.isNotBlank(queryId)) {
            row = execMapper.selectOne(new QueryWrapper<CpQueryExec>()
                    .eq("query_id", queryId)
                    .eq("delete_flag", "NOT_DELETE")
                    .last("limit 1"));
            if (row != null && StrUtil.isBlank(trinoQid)) {
                trinoQid = row.getTrinoQueryId();
            }
        }
        Map<String, Object> cancel = trinoClient.cancel(trinoQid);
        if (row != null) {
            row.setStatus("cancelled");
            row.setStatusLabel("已取消");
            row.setUpdateTime(new Date());
            execMapper.updateById(row);
        }
        Map<String, Object> out = new LinkedHashMap<>(cancel);
        out.put("queryId", queryId);
        return out;
    }

    @Override
    public List<Map<String, Object>> history(CpQueryHistoryParam param) {
        int limit = param.getLimit() == null || param.getLimit() <= 0
                ? HISTORY_DEFAULT : Math.min(param.getLimit(), 100);
        boolean mineOnly = param.getMineOnly() == null || Boolean.TRUE.equals(param.getMineOnly());
        if (!mineOnly) {
            vip.xiaonuo.lh.core.ws.LhDataScope.requireScopeAll();
        } else if (vip.xiaonuo.lh.core.ws.LhDataScope.forceMineOnly()) {
            mineOnly = true;
        }
        UserSnap user = currentUser();
        QueryWrapper<CpQueryExec> qw = new QueryWrapper<CpQueryExec>()
                .eq("delete_flag", "NOT_DELETE")
                .orderByDesc("create_time")
                .last("limit " + limit);
        if (StrUtil.isNotBlank(param.getWs())) {
            qw.eq("ws", param.getWs());
        }
        if (mineOnly && StrUtil.isNotBlank(user.id)) {
            qw.eq("user_id", user.id);
        }
        List<CpQueryExec> list = execMapper.selectList(qw);
        List<Map<String, Object>> out = new ArrayList<>();
        for (CpQueryExec e : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("queryId", e.getQueryId());
            m.put("time", formatTime(e.getCreateTime()));
            m.put("user", StrUtil.blankToDefault(e.getUserName(), e.getUserId()));
            m.put("summary", e.getSqlSummary());
            m.put("sql", e.getSqlText());
            m.put("duration", formatDur(e.getDurMs()));
            m.put("durMs", e.getDurMs());
            m.put("scan", formatBytes(e.getScanBytes()));
            m.put("scanBytes", e.getScanBytes());
            m.put("scanDanger", CpQueryScanGuard.isScanDanger(e.getScanBytes(), false));
            m.put("scanLimitBytes", CpQueryScanGuard.ADHOC_DEFAULT_SCAN_BYTES);
            m.put("rows", e.getRowCount() == null ? "—" : String.valueOf(e.getRowCount()));
            m.put("status", e.getStatus());
            m.put("statusLabel", e.getStatusLabel());
            m.put("tagClass", tagClass(e.getStatus()));
            m.put("errorMsg", e.getErrorMsg());
            out.add(m);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> schemaTree(String ws) {
        return assetCatalog.schemaTree(ws);
    }

    @Override
    public Map<String, Object> exportAudit(CpQueryExportParam param) {
        UserSnap user = currentUser();
        String queryId = StrUtil.trim(param.getQueryId());
        CpQueryExec row = null;
        if (StrUtil.isNotBlank(queryId)) {
            row = execMapper.selectOne(new QueryWrapper<CpQueryExec>()
                    .eq("query_id", queryId)
                    .eq("delete_flag", "NOT_DELETE")
                    .last("limit 1"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("queryId", queryId);
        out.put("userId", user.id);
        out.put("userName", user.name);
        out.put("rowCount", param.getRowCount());
        out.put("masked", true);
        out.put("filename", "query_result_masked_" + (StrUtil.isBlank(queryId) ? "export" : queryId) + ".csv");
        out.put("message", "导出已登记审计（脱敏集）；正文由客户端生成");
        if (row != null) {
            out.put("maskCols", row.getMaskCols());
            out.put("sqlHash", row.getSqlHash());
            // 轻量留痕：追加状态标签后缀（不改主状态）
            String label = StrUtil.blankToDefault(row.getStatusLabel(), "");
            if (!label.contains("已导出")) {
                row.setStatusLabel(StrUtil.maxLength(label + " · 已导出", 128));
                row.setUpdateTime(new Date());
                execMapper.updateById(row);
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> tableColumns(String assetId, String fqn) {
        return assetCatalog.tableColumns(assetId, fqn);
    }

    @Override
    public Map<String, Object> explain(CpQueryExecParam param) {
        String raw = StrUtil.trim(param.getSql());
        if (StrUtil.isBlank(raw)) {
            throw new CommonException("SQL 不能为空");
        }
        String bound;
        try {
            bound = CpQueryParamBinder.bind(raw, param.getParams());
        } catch (IllegalArgumentException e) {
            throw new CommonException(e.getMessage());
        }
        String explainSql = bound.trim().toUpperCase(Locale.ROOT).startsWith("EXPLAIN")
                ? bound
                : "EXPLAIN (TYPE DISTRIBUTED) " + bound;
        CpQueryExecParam p = new CpQueryExecParam();
        p.setSql(explainSql);
        p.setWs(param.getWs());
        p.setCatalog(param.getCatalog());
        p.setSchema(param.getSchema());
        p.setMaxRows(500);
        p.setForce(true);
        p.setElevated(param.getElevated());
        Map<String, Object> res = exec(p, null);
        Map<String, Object> out = new LinkedHashMap<>(res);
        out.put("explain", true);
        out.put("sourceSql", bound);
        String ui = trinoClient.resolveUiUrl();
        if (StrUtil.isNotBlank(ui)) {
            out.put("trinoUiHome", ui + "/ui/");
            Object tqid = res.get("trinoQueryId");
            if (tqid != null) {
                out.put("trinoUiUrl", ui + "/ui/query.html?" + tqid);
            }
        }
        // 把结果拼成文本计划
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) res.getOrDefault("rows", List.of());
        StringBuilder plan = new StringBuilder();
        for (Map<String, Object> r : rows) {
            if (r == null || r.isEmpty()) {
                continue;
            }
            Object first = r.values().iterator().next();
            if (first != null) {
                plan.append(first).append('\n');
            }
        }
        out.put("planText", plan.toString());
        return out;
    }

    @Override
    public Map<String, Object> detectParams(String sql) {
        return CpQueryParamBinder.preview(sql, Map.of());
    }

    @Override
    public Map<String, Object> saveDataset(CpQueryDatasetSaveParam param) {
        UserSnap user = currentUser();
        String ws = StrUtil.blankToDefault(param.getWs(), "default");
        String name = StrUtil.blankToDefault(param.getName(), "dataset_" + System.currentTimeMillis());
        List<Map<String, Object>> rows = param.getRows() == null ? List.of() : param.getRows();
        if (rows.size() > 200) {
            rows = rows.subList(0, 200);
        }
        String sampleJson = JSONUtil.toJsonStr(rows);
        CpQueryDataset ds = new CpQueryDataset();
        ds.setId(IdUtil.getSnowflakeNextIdStr());
        ds.setDsCode("ds_" + IdUtil.getSnowflakeNextIdStr());
        ds.setName(name);
        ds.setWs(ws);
        ds.setUserId(user.id);
        ds.setUserName(user.name);
        ds.setQueryId(param.getQueryId());
        ds.setSqlText(param.getSql());
        if (StrUtil.isNotBlank(param.getSql())) {
            ds.setSqlHash(DigestUtil.sha256Hex(param.getSql()).substring(0, 32));
        }
        ds.setColumnsJson(JSONUtil.toJsonStr(param.getColumns() == null ? List.of() : param.getColumns()));
        ds.setRowCount(rows.size());
        ds.setScanBytes(param.getScanBytes());
        ds.setStatus("active");
        ds.setDeleteFlag("NOT_DELETE");
        ds.setCreateTime(new Date());
        ds.setCreateUser(user.id);
        ds.setUpdateTime(ds.getCreateTime());
        ds.setUpdateUser(user.id);

        boolean objectOk = false;
        String degradeReason = null;
        try {
            CpQueryDatasetObjectStore.PutResult put = datasetObjectStore.putSampleJson(ws, ds.getDsCode(), sampleJson);
            ds.setSampleBucket(put.getBucket());
            ds.setSampleObjectKey(put.getObjectKey());
            ds.setSampleUri(put.getUri());
            ds.setSampleSha256(put.getSha256());
            ds.setSampleStorage(CpQueryDatasetObjectKeys.STORAGE_OBJECT);
            ds.setSampleJson(null);
            objectOk = true;
        } catch (Exception e) {
            degradeReason = StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
            ds.setSampleJson(sampleJson);
            ds.setSampleStorage(CpQueryDatasetObjectKeys.STORAGE_DB);
            ds.setSampleSha256(DigestUtil.sha256Hex(sampleJson));
        }

        datasetMapper.insert(ds);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", ds.getId());
        out.put("dsCode", ds.getDsCode());
        out.put("name", ds.getName());
        out.put("rowCount", ds.getRowCount());
        out.put("sampleStorage", ds.getSampleStorage());
        out.put("sampleUri", ds.getSampleUri());
        out.put("sampleObjectKey", ds.getSampleObjectKey());
        out.put("sampleBucket", ds.getSampleBucket());
        out.put("sampleSha256", ds.getSampleSha256());
        out.put("sampleDegraded", !objectOk);
        if (objectOk) {
            out.put("message", "已保存抽样数据集至对象存储（≤200 行，门户库仅元数据）");
        } else {
            out.put("message", "对象存储不可用，已降级写入门户 sample_json（≤200 行）");
            out.put("degradeReason", degradeReason);
        }
        return out;
    }

    @Override
    public Map<String, Object> getDataset(String id) {
        if (StrUtil.isBlank(id)) {
            throw new CommonException("数据集 id 不能为空");
        }
        UserSnap user = currentUser();
        CpQueryDataset d = datasetMapper.selectById(id);
        if (d == null || "DELETE".equals(d.getDeleteFlag()) || !user.id.equals(d.getUserId())) {
            throw new CommonException("数据集不存在或无权访问");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", d.getId());
        out.put("dsCode", d.getDsCode());
        out.put("name", d.getName());
        out.put("ws", d.getWs());
        out.put("queryId", d.getQueryId());
        out.put("sql", d.getSqlText());
        out.put("rowCount", d.getRowCount());
        out.put("scanBytes", d.getScanBytes());
        out.put("sampleStorage", d.getSampleStorage());
        out.put("sampleUri", d.getSampleUri());
        out.put("sampleObjectKey", d.getSampleObjectKey());
        out.put("sampleBucket", d.getSampleBucket());
        out.put("sampleSha256", d.getSampleSha256());
        out.put("createTime", formatTime(d.getCreateTime()));
        try {
            out.put("columns", JSONUtil.parseArray(
                    StrUtil.blankToDefault(d.getColumnsJson(), "[]")));
        } catch (Exception e) {
            out.put("columns", List.of());
        }

        String sampleBody = null;
        boolean sampleDegraded = false;
        String sampleSource = null;
        if (CpQueryDatasetObjectKeys.STORAGE_OBJECT.equals(d.getSampleStorage())
                && StrUtil.isNotBlank(d.getSampleBucket())
                && StrUtil.isNotBlank(d.getSampleObjectKey())) {
            try {
                sampleBody = datasetObjectStore.getSampleJson(d.getSampleBucket(), d.getSampleObjectKey());
                sampleSource = CpQueryDatasetObjectKeys.STORAGE_OBJECT;
            } catch (Exception e) {
                sampleDegraded = true;
                if (StrUtil.isNotBlank(d.getSampleJson())) {
                    sampleBody = d.getSampleJson();
                    sampleSource = CpQueryDatasetObjectKeys.STORAGE_DB;
                }
                out.put("sampleReadError", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName()));
            }
        } else if (StrUtil.isNotBlank(d.getSampleJson())) {
            sampleBody = d.getSampleJson();
            sampleSource = CpQueryDatasetObjectKeys.STORAGE_DB;
            sampleDegraded = !CpQueryDatasetObjectKeys.STORAGE_OBJECT.equals(d.getSampleStorage());
        }
        try {
            out.put("rows", sampleBody == null ? List.of() : JSONUtil.parseArray(sampleBody));
        } catch (Exception e) {
            out.put("rows", List.of());
            sampleDegraded = true;
        }
        out.put("sampleSource", sampleSource);
        out.put("sampleDegraded", sampleDegraded);
        return out;
    }

    @Override
    public List<Map<String, Object>> listDatasets(String ws, Integer limit) {
        int lim = limit == null || limit <= 0 ? 30 : Math.min(limit, 100);
        UserSnap user = currentUser();
        QueryWrapper<CpQueryDataset> qw = new QueryWrapper<CpQueryDataset>()
                .eq("delete_flag", "NOT_DELETE")
                .eq("user_id", user.id)
                .orderByDesc("create_time")
                .last("limit " + lim);
        if (StrUtil.isNotBlank(ws)) {
            qw.eq("ws", ws);
        }
        List<CpQueryDataset> list = datasetMapper.selectList(qw);
        List<Map<String, Object>> out = new ArrayList<>();
        for (CpQueryDataset d : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("dsCode", d.getDsCode());
            m.put("name", d.getName());
            m.put("queryId", d.getQueryId());
            m.put("rowCount", d.getRowCount());
            m.put("scanBytes", d.getScanBytes());
            m.put("sampleStorage", d.getSampleStorage());
            m.put("sampleUri", d.getSampleUri());
            m.put("sampleDegraded", CpQueryDatasetObjectKeys.STORAGE_DB.equals(d.getSampleStorage()));
            m.put("createTime", formatTime(d.getCreateTime()));
            m.put("sql", d.getSqlText());
            out.add(m);
        }
        return out;
    }

    @Override
    public Map<String, Object> saveSavedScript(CpQuerySavedSaveParam param) {
        if (param == null) {
            throw new CommonException("参数不能为空");
        }
        String sql = StrUtil.trim(param.getSql());
        if (StrUtil.isBlank(sql)) {
            throw new CommonException("SQL 不能为空");
        }
        if (sql.length() > 512_000) {
            throw new CommonException("SQL 过长，请改为开发脚本（Git）保存");
        }
        UserSnap user = currentUser();
        String ws = StrUtil.blankToDefault(param.getWs(), "default");
        String name = normalizeSavedName(param.getName());
        Date now = new Date();
        CpQuerySaved row = null;
        if (StrUtil.isNotBlank(param.getId())) {
            row = savedMapper.selectById(param.getId());
            if (row == null || "DELETE".equals(row.getDeleteFlag()) || !user.id.equals(row.getUserId())) {
                throw new CommonException("脚本不存在");
            }
        } else {
            row = savedMapper.selectOne(new QueryWrapper<CpQuerySaved>().lambda()
                    .eq(CpQuerySaved::getUserId, user.id)
                    .eq(CpQuerySaved::getWs, ws)
                    .eq(CpQuerySaved::getName, name)
                    .eq(CpQuerySaved::getDeleteFlag, "NOT_DELETE")
                    .last("LIMIT 1"));
        }
        boolean create = row == null;
        if (create) {
            row = new CpQuerySaved();
            row.setId(IdUtil.getSnowflakeNextIdStr());
            row.setUserId(user.id);
            row.setCreateTime(now);
            row.setCreateUser(user.id);
            row.setDeleteFlag("NOT_DELETE");
            row.setStatus("active");
        }
        row.setWs(ws);
        row.setUserName(user.name);
        row.setName(name);
        row.setSqlText(sql);
        row.setSqlHash(DigestUtil.sha256Hex(sql).substring(0, 32));
        row.setSqlSummary(summarizeSql(sql));
        row.setEngine(StrUtil.blankToDefault(param.getEngine(), "trino").toLowerCase(Locale.ROOT));
        row.setUpdateTime(now);
        row.setUpdateUser(user.id);
        if (create) {
            savedMapper.insert(row);
        } else {
            savedMapper.updateById(row);
        }
        Map<String, Object> out = savedView(row);
        out.put("message", create ? "已保存脚本" : "已更新脚本");
        return out;
    }

    @Override
    public List<Map<String, Object>> listSavedScripts(String ws, Integer limit) {
        int lim = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        UserSnap user = currentUser();
        QueryWrapper<CpQuerySaved> qw = new QueryWrapper<CpQuerySaved>()
                .eq("delete_flag", "NOT_DELETE")
                .eq("user_id", user.id)
                .orderByDesc("update_time")
                .last("limit " + lim);
        if (StrUtil.isNotBlank(ws)) {
            qw.eq("ws", ws);
        }
        List<CpQuerySaved> list = savedMapper.selectList(qw);
        List<Map<String, Object>> out = new ArrayList<>();
        for (CpQuerySaved row : list) {
            out.add(savedView(row));
        }
        return out;
    }

    @Override
    public Map<String, Object> getSavedScript(String id) {
        CpQuerySaved row = requireOwnedSaved(id);
        return savedView(row);
    }

    @Override
    public void deleteSavedScript(String id) {
        CpQuerySaved row = requireOwnedSaved(id);
        row.setDeleteFlag("DELETE");
        row.setStatus("archived");
        row.setUpdateTime(new Date());
        row.setUpdateUser(currentUser().id);
        savedMapper.updateById(row);
    }

    private CpQuerySaved requireOwnedSaved(String id) {
        if (StrUtil.isBlank(id)) {
            throw new CommonException("缺少脚本 id");
        }
        UserSnap user = currentUser();
        CpQuerySaved row = savedMapper.selectById(id);
        if (row == null || "DELETE".equals(row.getDeleteFlag()) || !user.id.equals(row.getUserId())) {
            throw new CommonException("脚本不存在");
        }
        return row;
    }

    private static Map<String, Object> savedView(CpQuerySaved row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("name", row.getName());
        m.put("ws", row.getWs());
        m.put("sql", row.getSqlText());
        m.put("sqlHash", row.getSqlHash());
        m.put("sqlSummary", row.getSqlSummary());
        m.put("engine", row.getEngine());
        m.put("status", row.getStatus());
        m.put("updateTime", formatTime(row.getUpdateTime()));
        m.put("createTime", formatTime(row.getCreateTime()));
        return m;
    }

    private static String normalizeSavedName(String raw) {
        String name = StrUtil.trim(raw);
        if (StrUtil.isBlank(name)) {
            name = "query_" + System.currentTimeMillis() + ".sql";
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".sql")) {
            name = name + ".sql";
        }
        if (name.length() > 200) {
            name = name.substring(0, 196) + ".sql";
        }
        return name;
    }

    private static String summarizeSql(String sql) {
        String one = StrUtil.blankToDefault(sql, "").replaceAll("\\s+", " ").trim();
        return one.length() > 200 ? one.substring(0, 200) + "…" : one;
    }

    @Override
    public Map<String, Object> querySurface() {
        return queryCatalogService.surfaceSnapshot();
    }

    @Override
    public List<Map<String, Object>> listCatalogMaps(String ws) {
        return queryCatalogService.listMaps(ws);
    }

    @Override
    public Map<String, Object> upsertCatalogMap(Map<String, Object> body) {
        if (body == null) {
            throw new CommonException("body 不能为空");
        }
        Object en = body.get("enabled");
        Boolean enabled = null;
        if (en != null) {
            enabled = Boolean.TRUE.equals(en)
                    || "1".equals(String.valueOf(en))
                    || "true".equalsIgnoreCase(String.valueOf(en));
        }
        return queryCatalogService.upsertMap(
                str(body.get("ws")),
                str(body.get("gravCatalog")),
                str(body.get("trinoCatalog")),
                str(body.get("kind")),
                enabled,
                body.get("remark") == null ? null : String.valueOf(body.get("remark")));
    }

    @Override
    public Map<String, Object> govOverview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("querySurface", queryCatalogService.surfaceSnapshot());
        // 规则：与即席同源
        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(rule("R0", "查询面 catalog", "BLOCK",
                "SQL catalog 须 ∈ lh.trino.query-catalogs ∩ SHOW CATALOGS；禁止 ds_* 登记名直查"));
        rules.add(rule("R1", "禁写语句", "BLOCK", "INSERT/UPDATE/DELETE/DDL 等写操作禁止"));
        rules.add(rule("R2", "全表扫描防护", "BLOCK", "FROM 无 WHERE 且无 LIMIT"));
        rules.add(rule("R3", "ODS 分区", "BLOCK", "ods_* 缺 dt/partition 且无 LIMIT"));
        rules.add(rule("R4", "扫描限额", "BLOCK",
                "adhoc 默认 ≤" + formatBytes(CpQueryScanGuard.ADHOC_DEFAULT_SCAN_BYTES)
                        + "；硬顶 " + formatBytes(CpQueryScanGuard.PLATFORM_HARD_SCAN_BYTES)));
        rules.add(rule("R5", "并发限额", "REJECT",
                "adhoc 并发 ≤" + CpQueryConcurrencyGuard.max() + "（当前 "
                        + CpQueryConcurrencyGuard.current() + "）"));
        out.put("rules", rules);

        List<Map<String, Object>> queues = new ArrayList<>();
        queues.add(queue("dashboard", "📊", "高", 20, "无限制", "Superset 看板查询"));
        Map<String, Object> adhoc = queue("adhoc", "💻", "中", CpQueryConcurrencyGuard.max(),
                "≤ 10 GB（硬顶 50GB）", "即席查询 / 分析师");
        adhoc.put("concurrentLive", CpQueryConcurrencyGuard.current());
        queues.add(adhoc);
        queues.add(queue("etl", "🔄", "低", 10, "无限制", "ETL 批处理作业"));
        out.put("queues", queues);

        // KPI from cp_query_exec
        long todayOk = countStatus(null);
        long blocked = countStatus("blocked");
        long failed = countStatus("failed");
        List<Map<String, Object>> kpis = new ArrayList<>();
        kpis.add(kpi("queues", "3", "个", "Trino 队列", "dashboard 优先"));
        kpis.add(kpi("volume", String.valueOf(Math.max(todayOk, 0)), "条", "审计记录(近)", "cp_query_exec"));
        kpis.add(kpi("blocked", String.valueOf(blocked), "次", "超限/规则阻断", "含并发拒绝"));
        kpis.add(kpi("failed", String.valueOf(failed), "次", "执行失败", "Trino/网络"));
        kpis.add(kpi("adhocLive", String.valueOf(CpQueryConcurrencyGuard.current()),
                "/" + CpQueryConcurrencyGuard.max(), "adhoc 在途", "实时并发"));
        out.put("kpis", kpis);

        CpQueryHistoryParam hp = new CpQueryHistoryParam();
        hp.setLimit(10);
        hp.setMineOnly(false);
        List<Map<String, Object>> audits = history(hp);
        // 按扫描字节粗排
        audits.sort((a, b) -> Long.compare(asLong(b.get("scanBytes")) == null ? 0 : asLong(b.get("scanBytes")),
                asLong(a.get("scanBytes")) == null ? 0 : asLong(a.get("scanBytes"))));
        out.put("audits", audits.size() > 10 ? audits.subList(0, 10) : audits);
        out.put("scanDefaultBytes", CpQueryScanGuard.ADHOC_DEFAULT_SCAN_BYTES);
        out.put("scanHardBytes", CpQueryScanGuard.PLATFORM_HARD_SCAN_BYTES);
        out.put("scanElevateAllowed", secAuthGrantService.hasScanElevateGrant());
        out.put("scanElevateApplyPath", CpQueryElevateGate.APPLY_PATH);
        out.put("adhocMaxConcurrent", CpQueryConcurrencyGuard.max());
        out.put("adhocConcurrent", CpQueryConcurrencyGuard.current());
        return out;
    }

    private long countStatus(String status) {
        QueryWrapper<CpQueryExec> qw = new QueryWrapper<CpQueryExec>()
                .eq("delete_flag", "NOT_DELETE");
        if (StrUtil.isNotBlank(status)) {
            qw.eq("status", status);
        }
        Long n = execMapper.selectCount(qw);
        return n == null ? 0 : n;
    }

    private static Map<String, Object> rule(String id, String name, String action, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("action", action);
        m.put("desc", desc);
        m.put("source", "CpQueryScanGuard");
        return m;
    }

    private static Map<String, Object> queue(String name, String icon, String priority,
                                            int concurrent, String scanLimit, String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("icon", icon);
        m.put("priority", priority);
        m.put("concurrent", concurrent);
        m.put("scanLimit", scanLimit);
        m.put("desc", desc);
        return m;
    }

    private static Map<String, Object> kpi(String key, String value, String unit, String label, String trend) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("value", value);
        m.put("unit", unit);
        m.put("label", label);
        m.put("trend", trend);
        return m;
    }

    private CpQueryPrincipalMapper.Principal resolvePrincipal(UserSnap user) {
        LhTrinoPrincipal mapped = principalService.requireCurrent();
        SaBaseLoginUser login = null;
        try {
            login = StpLoginUserUtil.getLoginUser();
        } catch (Exception ignored) {
        }
        CpQueryPrincipalMapper.Principal principal = CpQueryPrincipalMapper.resolve(login, user == null ? null : user.account);
        principal.trinoUser = mapped.getTrinoUser();
        return principal;
    }

    private static boolean isAccessDenied(String msg) {
        if (msg == null) {
            return false;
        }
        if (isImpersonationDenied(msg)) {
            return false;
        }
        String m = msg.toLowerCase(Locale.ROOT);
        return m.contains("access denied")
                || m.contains("permission denied")
                || m.contains("not authorized")
                || m.contains("unauthorized")
                || m.contains("无权")
                || m.contains("未授权")
                || m.contains("forbidden");
    }

    private static boolean isImpersonationDenied(String msg) {
        if (msg == null) {
            return false;
        }
        String m = msg.toLowerCase(Locale.ROOT);
        return m.contains("cannot impersonate") || m.contains("impersonat");
    }

    private UserSnap currentUser() {
        UserSnap u = new UserSnap();
        try {
            SaBaseLoginUser login = StpLoginUserUtil.getLoginUser();
            if (login != null) {
                u.id = login.getId();
                u.account = login.getAccount();
                u.name = StrUtil.blankToDefault(login.getName(),
                        StrUtil.blankToDefault(login.getNickname(), login.getAccount()));
                return u;
            }
        } catch (Exception ignored) {
        }
        try {
            u.id = LhLoginUsers.requireUserId();
            u.name = u.id;
        } catch (Exception e) {
            u.id = "anonymous";
            u.name = "anonymous";
            u.account = "lakehouse";
        }
        return u;
    }

    private static String newQueryId() {
        String day = java.time.LocalDate.now().toString().replace("-", "");
        String sid = IdUtil.getSnowflakeNextIdStr();
        String suffix = sid.length() > 8 ? sid.substring(sid.length() - 8) : sid;
        return day + "_" + suffix;
    }

    private static String summarize(String sql) {
        String one = sql.replaceAll("\\s+", " ").trim();
        return one.length() > 80 ? one.substring(0, 80) + "…" : one;
    }

    private static List<Map<String, Object>> columnMeta(List<String> columns, List<String> maskCols) {
        List<Map<String, Object>> meta = new ArrayList<>();
        if (columns == null) {
            return meta;
        }
        for (String c : columns) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", c);
            m.put("label", c);
            m.put("masked", maskCols != null && maskCols.contains(c));
            meta.add(m);
        }
        return meta;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long asLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }

    private static String formatBytes(Long bytes) {
        if (bytes == null || bytes < 0) {
            return "—";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ROOT, "%.1f MB", mb);
        }
        double gb = mb / 1024.0;
        return String.format(Locale.ROOT, "%.2f GB", gb);
    }

    private static String formatDur(Long ms) {
        if (ms == null) {
            return "—";
        }
        if (ms < 1000) {
            return ms + "ms";
        }
        return String.format(Locale.ROOT, "%.2fs", ms / 1000.0);
    }

    private static String formatTime(Date d) {
        if (d == null) {
            return "—";
        }
        java.time.LocalDateTime ldt = d.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
        return String.format("%02d-%02d %02d:%02d",
                ldt.getMonthValue(), ldt.getDayOfMonth(), ldt.getHour(), ldt.getMinute());
    }

    private static String tagClass(String status) {
        if (status == null) {
            return "tag-gray";
        }
        return switch (status) {
            case "ok" -> "tag-green";
            case "blocked" -> "tag-red";
            case "failed" -> "tag-red";
            case "cancelled" -> "tag-orange";
            case "running" -> "tag-blue";
            default -> "tag-gray";
        };
    }

    private static class UserSnap {
        String id;
        String name;
        String account;
    }
}
