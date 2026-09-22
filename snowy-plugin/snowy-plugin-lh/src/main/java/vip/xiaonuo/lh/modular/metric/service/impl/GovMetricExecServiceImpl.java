package vip.xiaonuo.lh.modular.metric.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.auth.core.util.StpLoginUserUtil;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.core.engine.TrinoClient;
import vip.xiaonuo.lh.modular.metric.entity.GovMetric;
import vip.xiaonuo.lh.modular.metric.entity.GovMetricVer;
import vip.xiaonuo.lh.modular.metric.mapper.GovMetricMapper;
import vip.xiaonuo.lh.modular.metric.mapper.GovMetricVerMapper;
import vip.xiaonuo.lh.modular.metric.param.GovMetricQueryParam;
import vip.xiaonuo.lh.modular.metric.param.GovMetricTrialParam;
import vip.xiaonuo.lh.modular.metric.service.GovMetricExecService;
import vip.xiaonuo.lh.modular.metric.support.GovMetricSqlCompiler;
import vip.xiaonuo.lh.modular.metric.support.MetricExecGuard;
import vip.xiaonuo.lh.modular.metric.support.MetricParamBinder;
import vip.xiaonuo.lh.modular.metric.support.MetricQueryCache;
import vip.xiaonuo.lh.modular.query.entity.CpQueryExec;
import vip.xiaonuo.lh.modular.query.mapper.CpQueryExecMapper;
import vip.xiaonuo.lh.modular.sec.entity.LhTrinoPrincipal;
import vip.xiaonuo.lh.modular.sec.service.LhTrinoPrincipalService;
import vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * M1：编译 → 参数绑定 → TrinoClient → cp_query_exec；
 * M2：query Redis 短缓存；采样 JOB 身份。
 */
@Service
public class GovMetricExecServiceImpl implements GovMetricExecService {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String WS_DEFAULT = "default";
    private static final Set<String> TRIAL_OK = Set.of(
            "draft", "review", "active", "version_review");

    @Resource
    private GovMetricMapper metricMapper;
    @Resource
    private GovMetricVerMapper verMapper;
    @Resource
    private TrinoClient trinoClient;
    @Resource
    private CpQueryExecMapper execMapper;
    @Resource
    private LhTrinoPrincipalService principalService;
    @Resource
    private SecAuthGrantService secAuthGrantService;
    @Resource
    private MetricQueryCache metricQueryCache;

    @Override
    public Map<String, Object> query(GovMetricQueryParam param) {
        GovMetric head = requireMetric(param.getMetricCode(), param.getWs());
        secAuthGrantService.assertCanReadMetric(head);
        if (!"active".equals(head.getStatus())) {
            throw new CommonException("仅已启用指标可查询");
        }
        GovMetricVer ver = resolveVer(head, param.getVer());
        int maxRows = param.getMaxRows() == null ? 1000 : param.getMaxRows();
        String cacheKey = metricQueryCache.cacheKey(
                head.getMetricCode(), ver == null ? null : ver.getVer(), param.getParams(), maxRows);
        Map<String, Object> hit = metricQueryCache.get(cacheKey);
        if (hit != null) {
            Map<String, Object> cached = new LinkedHashMap<>(hit);
            cached.put("cached", true);
            return cached;
        }
        Map<String, Object> out = execute(head, ver, param.getParams(), maxRows,
                "metric", param.getPrefer(), false, false);
        if (Boolean.TRUE.equals(out.get("executed")) && !Boolean.TRUE.equals(out.get("degraded"))
                && !"blocked".equals(String.valueOf(out.get("status")))
                && !"failed".equals(String.valueOf(out.get("status")))) {
            metricQueryCache.put(cacheKey, out);
        }
        return out;
    }

    @Override
    public Map<String, Object> trial(String metricCode, GovMetricTrialParam param) {
        GovMetricTrialParam p = param == null ? new GovMetricTrialParam() : param;
        GovMetric head = requireMetric(metricCode, p.getWs());
        secAuthGrantService.assertCanReadMetric(head);
        if (!TRIAL_OK.contains(head.getStatus())) {
            throw new CommonException("当前状态不可试跑: " + head.getStatus());
        }
        int maxRows = p.getMaxRows() == null ? 200 : Math.min(p.getMaxRows(), 1000);
        return execute(head, resolveVer(head, null), p.getParams(), maxRows,
                "metric_trial", "trino", true, false);
    }

    @Override
    public Map<String, Object> sampleQuery(String metricCode, String ws,
                                           Map<String, Object> params, int maxRows) {
        GovMetric head = requireMetric(metricCode, ws);
        if (!"active".equals(head.getStatus())) {
            throw new CommonException("仅已启用指标可采样: " + metricCode);
        }
        int n = Math.max(1, Math.min(maxRows, 200));
        return execute(head, resolveVer(head, null), params, n,
                "metric_sample", "trino", false, true);
    }

    private Map<String, Object> execute(GovMetric head, GovMetricVer ver, Map<String, Object> params,
                                        int maxRows, String source, String prefer,
                                        boolean trial, boolean jobIdentity) {
        if (ver == null) {
            throw new CommonException("指标版本不存在");
        }
        String preferNorm = StrUtil.blankToDefault(prefer, "trino");
        boolean hotRequested = "hot".equalsIgnoreCase(preferNorm);
        // M1/M2：hot 回退 Trino（M3 再接 CK）
        String engine = "trino";
        String fallback = hotRequested ? "trino" : null;

        GovMetricSqlCompiler.CompileOut compiled;
        try {
            compiled = GovMetricSqlCompiler.compile(head, ver, "trino",
                    code -> resolveNode(code, head.getWs()),
                    params == null ? Map.of() : params);
        } catch (IllegalArgumentException e) {
            throw new CommonException(e.getMessage());
        }

        MetricParamBinder.BindOut bound;
        try {
            bound = MetricParamBinder.bind(compiled.sqlText(), params,
                    compiled.timeWindow(), compiled.grainKeys());
        } catch (IllegalArgumentException e) {
            throw new CommonException(e.getMessage());
        }

        String sql = MetricExecGuard.ensureLimit(bound.sql(), maxRows);
        String block = MetricExecGuard.blockReason(sql);
        UserSnap user = jobIdentity ? jobUser() : currentUser();
        String queryId = "mq_" + IdUtil.getSnowflakeNextIdStr();

        CpQueryExec row = newAuditRow(queryId, user, head, ver, sql, source, engine);
        execMapper.insert(row);

        if (block != null) {
            row.setStatus("blocked");
            row.setStatusLabel("⚠ " + block);
            row.setErrorMsg(block);
            row.setDurMs(0L);
            row.setRowCount(0);
            row.setUpdateTime(new Date());
            execMapper.updateById(row);
            Map<String, Object> blocked = MetricExecGuard.blockedPayload(block);
            blocked.put("queryId", queryId);
            blocked.put("metricCode", head.getMetricCode());
            blocked.put("ver", ver.getVer());
            blocked.put("sqlText", sql);
            blocked.put("dialect", "trino");
            blocked.put("engine", engine);
            blocked.put("cached", false);
            return blocked;
        }

        long t0 = System.currentTimeMillis();
        TrinoClient.ExecuteOptions opts;
        if (jobIdentity) {
            opts = TrinoClient.ExecuteOptions.job(maxRows);
            opts.source = "lakehouse-metric-sample";
        } else {
            LhTrinoPrincipal principal = principalService.requireCurrent();
            opts = TrinoClient.ExecuteOptions.human(principal.getTrinoUser(), maxRows);
            opts.source = trial ? "lakehouse-metric-trial" : "lakehouse-metric";
        }
        opts.catalog = "iceberg";
        opts.schema = "default";

        Map<String, Object> exec;
        try {
            exec = trinoClient.execute(sql, opts);
        } catch (CommonException e) {
            long dur = System.currentTimeMillis() - t0;
            row.setStatus("failed");
            row.setStatusLabel("失败");
            row.setErrorMsg(StrUtil.maxLength(e.getMessage(), 1000));
            row.setDurMs(dur);
            row.setUpdateTime(new Date());
            execMapper.updateById(row);
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("metricCode", head.getMetricCode());
            fail.put("ver", ver.getVer());
            fail.put("executed", false);
            fail.put("status", "failed");
            fail.put("message", e.getMessage());
            fail.put("queryId", queryId);
            fail.put("sqlText", sql);
            fail.put("engine", engine);
            fail.put("dialect", "trino");
            fail.put("rows", List.of());
            fail.put("columns", List.of());
            fail.put("rowCount", 0);
            fail.put("durMs", dur);
            fail.put("cached", false);
            return fail;
        }

        long dur = System.currentTimeMillis() - t0;
        boolean degraded = Boolean.TRUE.equals(exec.get("degraded"));
        @SuppressWarnings("unchecked")
        List<String> columns = (List<String>) exec.getOrDefault("columns", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) exec.getOrDefault("rows", List.of());
        Long scanBytes = asLong(exec.get("scanBytes"));
        Long elapsed = asLong(exec.get("elapsedMs"));
        if (elapsed != null && elapsed > 0) {
            dur = elapsed;
        }
        String trinoQid = str(exec.get("trinoQueryId"));

        if (degraded) {
            row.setStatus("failed");
            row.setStatusLabel("Trino 不可达");
            row.setErrorMsg(StrUtil.maxLength(str(exec.get("message")), 1000));
        } else {
            row.setStatus("ok");
            row.setStatusLabel(trial ? "✓ 试跑" : (jobIdentity ? "✓ 采样" : "✓"));
        }
        row.setTrinoQueryId(trinoQid);
        row.setRowCount(rows == null ? 0 : rows.size());
        row.setScanBytes(scanBytes);
        row.setDurMs(dur);
        row.setUpdateTime(new Date());
        execMapper.updateById(row);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metricCode", head.getMetricCode());
        out.put("ver", ver.getVer());
        out.put("dialect", "trino");
        out.put("engine", engine);
        out.put("executed", !degraded);
        out.put("degraded", degraded);
        out.put("queryId", queryId);
        out.put("trinoQueryId", trinoQid);
        out.put("sqlText", sql);
        out.put("columns", columns);
        out.put("rows", rows == null ? List.of() : rows);
        out.put("rowCount", rows == null ? 0 : rows.size());
        out.put("scanBytes", scanBytes);
        out.put("durMs", dur);
        out.put("cached", false);
        out.put("materialized", false);
        out.put("status", row.getStatus());
        out.put("statusLabel", row.getStatusLabel());
        out.put("source", source);
        out.put("bound", Map.of("dt", bound.dt(), "from", bound.from(), "to", bound.to()));
        if (fallback != null) {
            out.put("fallback", fallback);
            out.put("message", hotRequested ? "prefer=hot 暂回退 Trino（M3 接 CK）" : null);
        }
        if (degraded) {
            out.put("message", exec.get("message"));
        }
        return out;
    }

    private UserSnap jobUser() {
        UserSnap u = new UserSnap();
        u.id = "job.metric.sample";
        u.name = "metric-sample";
        u.account = "job.metric.sample";
        return u;
    }

    private CpQueryExec newAuditRow(String queryId, UserSnap user, GovMetric head, GovMetricVer ver,
                                    String sql, String source, String engine) {
        CpQueryExec row = new CpQueryExec();
        row.setId(IdUtil.getSnowflakeNextIdStr());
        row.setQueryId(queryId);
        row.setWs(StrUtil.blankToDefault(head.getWs(), WS_DEFAULT));
        row.setUserId(user.id);
        row.setUserName(user.name);
        row.setSqlText(sql);
        row.setSqlHash(DigestUtil.sha256Hex(sql).substring(0, 32));
        row.setSqlSummary(StrUtil.maxLength(
                "[" + source + "] " + head.getMetricCode() + "@" + ver.getVer(), 500));
        row.setStatus("running");
        row.setStatusLabel("执行中");
        row.setEngine(engine);
        row.setSource(source);
        row.setMetricCode(head.getMetricCode());
        row.setMetricVer(ver.getVer());
        row.setCatalogName("iceberg");
        row.setSchemaName("default");
        row.setDeleteFlag(NOT_DELETE);
        row.setCreateTime(new Date());
        row.setCreateUser(user.id);
        row.setUpdateTime(row.getCreateTime());
        row.setUpdateUser(user.id);
        return row;
    }

    private GovMetricVer resolveVer(GovMetric head, String verNo) {
        if (StrUtil.isNotBlank(verNo)) {
            GovMetricVer v = verMapper.selectOne(new QueryWrapper<GovMetricVer>().lambda()
                    .eq(GovMetricVer::getMetricId, head.getId())
                    .eq(GovMetricVer::getVer, verNo.trim())
                    .eq(GovMetricVer::getDeleteFlag, NOT_DELETE)
                    .last("LIMIT 1"));
            if (v == null) {
                throw new CommonException("版本不存在: " + verNo);
            }
            return v;
        }
        return verMapper.selectById(head.getCurrentVerId());
    }

    private GovMetricSqlCompiler.MetricNode resolveNode(String code, String ws) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        GovMetric h = findMetric(code, ws);
        if (h == null) {
            return null;
        }
        GovMetricVer v = verMapper.selectById(h.getCurrentVerId());
        return new GovMetricSqlCompiler.MetricNode(h.getMetricCode(), h, v);
    }

    private GovMetric requireMetric(String code, String ws) {
        GovMetric m = findMetric(code, ws);
        if (m == null) {
            throw new CommonException("指标不存在: " + code);
        }
        return m;
    }

    private GovMetric findMetric(String code, String ws) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        QueryWrapper<GovMetric> qw = new QueryWrapper<>();
        qw.lambda().eq(GovMetric::getDeleteFlag, NOT_DELETE)
                .eq(GovMetric::getMetricCode, code.trim().toUpperCase(Locale.ROOT));
        if (StrUtil.isNotBlank(ws)) {
            qw.lambda().eq(GovMetric::getWs, ws);
        }
        return metricMapper.selectOne(qw.last("LIMIT 1"));
    }

    private UserSnap currentUser() {
        UserSnap u = new UserSnap();
        try {
            SaBaseLoginUser login = StpLoginUserUtil.getLoginUser();
            if (login != null) {
                u.id = login.getId();
                u.name = StrUtil.blankToDefault(login.getName(), login.getAccount());
                u.account = login.getAccount();
                return u;
            }
        } catch (Exception ignored) {
        }
        try {
            u.id = LhLoginUsers.requireUserId();
            u.name = u.id;
            u.account = u.id;
        } catch (Exception e) {
            u.id = "anonymous";
            u.name = "anonymous";
            u.account = "anonymous";
        }
        return u;
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

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static class UserSnap {
        String id;
        String name;
        String account;
    }
}
