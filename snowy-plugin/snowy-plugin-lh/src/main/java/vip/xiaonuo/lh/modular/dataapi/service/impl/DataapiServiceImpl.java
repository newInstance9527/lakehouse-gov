package vip.xiaonuo.lh.modular.dataapi.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.common.page.CommonPageRequest;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.ApisixClient;
import vip.xiaonuo.lh.core.engine.SqlrestClient;
import vip.xiaonuo.lh.core.ws.ExternalBindingGuard;
import vip.xiaonuo.lh.core.ws.ExternalName;
import vip.xiaonuo.lh.modular.apply.service.ApplyTicketService;
import vip.xiaonuo.lh.modular.dataapi.entity.DataapiApiBinding;
import vip.xiaonuo.lh.modular.dataapi.entity.DataapiApiKeyMeta;
import vip.xiaonuo.lh.modular.dataapi.mapper.DataapiApiBindingMapper;
import vip.xiaonuo.lh.modular.dataapi.mapper.DataapiApiKeyMetaMapper;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiBindingParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiGatewayProbeParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiIdParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiPageParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiParseParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiTrialParam;
import vip.xiaonuo.lh.modular.dataapi.service.DataapiService;
import vip.xiaonuo.lh.modular.dataapi.support.DataapiCallStatsSupport;
import vip.xiaonuo.lh.modular.dataapi.support.DataapiOpenApiBuilder;
import vip.xiaonuo.lh.modular.datasource.service.LhDatasourceSqlrestProjector;
import vip.xiaonuo.lh.modular.datasource.support.ApiBuildTableAccess;
import vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DataapiServiceImpl implements DataapiService {

    private static final String WS_DEFAULT = "default";
    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private DataapiApiBindingMapper bindingMapper;
    @Resource
    private DataapiApiKeyMetaMapper keyMetaMapper;
    @Resource
    private SqlrestClient sqlrestClient;
    @Resource
    private ApisixClient apisixClient;
    @Resource
    private LhDatasourceSqlrestProjector sqlrestProjector;
    @Resource
    private ApplyTicketService applyTicketService;
    @Resource
    private SecAuthGrantService secAuthGrantService;
    @Resource
    private ApiBuildTableAccess apiBuildTableAccess;
    @Resource
    private LhProperties lhProperties;
    @Resource
    private ExternalBindingGuard externalBindingGuard;

    @Override
    public Map<String, Object> overview(String ws) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        long published = bindingMapper.selectCount(baseQw(workspace).eq("state", "published"));
        long draft = bindingMapper.selectCount(baseQw(workspace).eq("state", "draft"));
        long keys = keyMetaMapper.selectCount(new QueryWrapper<DataapiApiKeyMeta>().lambda()
                .eq(DataapiApiKeyMeta::getWs, workspace)
                .eq(DataapiApiKeyMeta::getDeleteFlag, NOT_DELETE)
                .eq(DataapiApiKeyMeta::getStatus, "active"));
        long pendingKeys = keyMetaMapper.selectCount(new QueryWrapper<DataapiApiKeyMeta>().lambda()
                .eq(DataapiApiKeyMeta::getWs, workspace)
                .eq(DataapiApiKeyMeta::getDeleteFlag, NOT_DELETE)
                .eq(DataapiApiKeyMeta::getStatus, "pending"));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("publishedApis", published);
        m.put("draftApis", draft);
        m.put("activeSubscribers", keys);
        m.put("pendingSubscribers", pendingKeys);
        m.put("calls24h", null);
        m.put("avgLatencyMs", null);
        m.put("callsNote", "调用量见 SQLREST overview（Gateway 日志）");
        Map<String, Object> stats = callStats(7);
        DataapiCallStatsSupport.enrichOverview(m, stats);
        Map<?, ?> counter = stats.get("counter") instanceof Map<?, ?> c ? c : Map.of();
        if (!counter.isEmpty()) {
            m.put("sqlrestTotal", counter.get("totalCount"));
            m.put("sqlrestOnline", counter.get("publishCount"));
            m.put("sqlrestOpen", counter.get("openCount"));
            m.put("sqlrestDatasourceCount", counter.get("datasourceCount"));
        }
        return m;
    }

    @Override
    public Page<DataapiApiBinding> page(DataapiPageParam param) {
        String ws = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);
        QueryWrapper<DataapiApiBinding> qw = baseQw(ws);
        String q = StrUtil.blankToDefault(param.getQ(), param.getKeyword());
        if (StrUtil.isNotBlank(q)) {
            qw.and(w -> w.like("name", q).or().like("public_path", q).or().like("domain_code", q)
                    .or().like("source_ref", q));
        }
        if (StrUtil.isNotBlank(param.getState())) {
            qw.eq("state", param.getState());
        }
        if (StrUtil.isNotBlank(param.getDomain())) {
            qw.eq("domain_code", param.getDomain());
        }
        qw.orderByDesc("update_time");
        return bindingMapper.selectPage(CommonPageRequest.defaultPage(), qw);
    }

    @Override
    public List<Map<String, Object>> listApis(DataapiPageParam param) {
        Page<DataapiApiBinding> page = page(param);
        List<Map<String, Object>> list = new ArrayList<>();
        for (DataapiApiBinding b : page.getRecords()) {
            list.add(toPortalCard(b, false));
        }
        return list;
    }

    @Override
    public Map<String, Object> detail(String id, boolean withSqlrest) {
        DataapiApiBinding b = requireBinding(id);
        Map<String, Object> vo = toPortalCard(b, true);
        Map<String, Object> pubTicket = applyTicketService.findLatestApiPublishTicket(b.getId());
        if (pubTicket != null) {
            Object existingNo = vo.get("publishTicketNo");
            if (existingNo == null || StrUtil.isBlank(String.valueOf(existingNo))) {
                vo.put("publishTicketNo", pubTicket.get("ticketNo"));
            }
            vo.put("publishTicketStatus", pubTicket.get("status"));
            vo.put("publishTicketRemark", pubTicket.get("remark"));
            vo.put("publishTicketId", pubTicket.get("id"));
        }
        if (withSqlrest && StrUtil.isNotBlank(b.getSqlrestApiId())) {
            Map<String, Object> sr = sqlrestClient.detail(b.getSqlrestApiId());
            vo.put("sqlrest", sr);
            if (Boolean.TRUE.equals(sr.get("ok")) && sr.get("data") != null) {
                cn.hutool.json.JSONObject data = JSONUtil.parseObj(sr.get("data"));
                vo.put("engine", data.getStr("engine"));
                cn.hutool.json.JSONArray sqlList = data.getJSONArray("sqlList");
                if (sqlList != null && !sqlList.isEmpty()) {
                    vo.put("sql", sqlList.getJSONObject(0).getStr("sqlText"));
                    List<String> ctx = new ArrayList<>();
                    for (int i = 0; i < sqlList.size(); i++) {
                        String t = sqlList.getJSONObject(i).getStr("sqlText");
                        if (StrUtil.isNotBlank(t)) {
                            ctx.add(t);
                        }
                    }
                    if (!ctx.isEmpty()) {
                        vo.put("contextList", ctx);
                    }
                }
                if (StrUtil.isNotBlank(data.getStr("script"))) {
                    vo.put("script", data.getStr("script"));
                }
                vo.put("managerDeepLink", sqlrestClient.embedUrl() + "/#/interface/detail?id=" + b.getSqlrestApiId());
            }
        }
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DataapiApiBinding add(DataapiBindingParam param) {
        DataapiApiBinding b = newBinding(param);
        bindingMapper.insert(b);
        return b;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DataapiApiBinding edit(DataapiBindingParam param) {
        if (StrUtil.isBlank(param.getId())) {
            throw new CommonException("id 不能为空");
        }
        DataapiApiBinding b = requireBinding(param.getId());
        applyParam(b, param);
        b.setRevision(b.getRevision() == null ? 1 : b.getRevision() + 1);
        bindingMapper.updateById(b);
        return b;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> build(DataapiBindingParam param) {
        boolean hasCtx = param.getContextList() != null && !param.getContextList().isEmpty();
        if (StrUtil.isBlank(param.getSql()) && !hasCtx
                && StrUtil.isBlank(param.getSqlrestApiId()) && StrUtil.isBlank(param.getId())) {
            throw new CommonException("请提供 SQL/Groovy 脚本，或已有 sqlrestApiId");
        }
        DataapiApiBinding binding;
        if (StrUtil.isNotBlank(param.getId())) {
            binding = requireBinding(param.getId());
            applyParam(binding, param);
        } else {
            QueryWrapper<DataapiApiBinding> existQw = baseQw(StrUtil.blankToDefault(param.getWs(), WS_DEFAULT))
                    .eq("public_path", normalizePath(param.getPublicPath()))
                    .eq("method", StrUtil.blankToDefault(param.getMethod(), "GET").toUpperCase());
            DataapiApiBinding exist = bindingMapper.selectOne(existQw.last("LIMIT 1"));
            if (exist != null) {
                binding = exist;
                applyParam(binding, param);
            } else {
                binding = newBinding(param);
            }
        }

        Long sqlrestId = parseLong(binding.getSqlrestApiId());
        String sql = param.getSql();
        if (StrUtil.isBlank(sql) && param.getContextList() != null && !param.getContextList().isEmpty()) {
            sql = param.getContextList().get(0);
        }
        if (StrUtil.isBlank(sql) && sqlrestId != null) {
            Map<String, Object> detail = sqlrestClient.detail(String.valueOf(sqlrestId));
            if (Boolean.TRUE.equals(detail.get("ok")) && detail.get("data") != null) {
                cn.hutool.json.JSONObject data = JSONUtil.parseObj(detail.get("data"));
                cn.hutool.json.JSONArray sqlList = data.getJSONArray("sqlList");
                if (sqlList != null && !sqlList.isEmpty()) {
                    sql = sqlList.getJSONObject(0).getStr("sqlText");
                }
            }
        }
        if (StrUtil.isBlank(sql) && (param.getContextList() == null || param.getContextList().isEmpty())) {
            throw new CommonException("构建需要 SQL 或 Groovy 脚本正文（经 SQLREST Manager API 写入）");
        }

        String engine = StrUtil.blankToDefault(param.getEngine(), "SQL").trim().toUpperCase();
        if (!"GROOVY".equals(engine)) {
            engine = "SQL";
        }

        Long srDsId = resolveSqlrestDatasourceId(param, binding);
        String portalDsCheck = StrUtil.blankToDefault(param.getPortalDsId(),
                StrUtil.blankToDefault(param.getDsId(), binding.getPortalDsId()));
        if (StrUtil.isNotBlank(portalDsCheck) && !secAuthGrantService.canUseDatasource(portalDsCheck)) {
            throw new CommonException("无权使用该数据源构建 API（须为拥有者或持有 EDIT/MANAGE）");
        }
        if (srDsId == null && StrUtil.isNotBlank(portalDsCheck)) {
            throw new CommonException("数据源尚未投影到 SQLREST，请先在数据源中心完成登记投影（或点击「投影到 SQLREST」补齐）");
        }
        List<Map<String, Object>> srParams = sqlrestClient.toSqlrestParams(param.getParams(), binding.getMethod());
        Map<String, Object> opts = assignmentOptsFromParam(param);
        String sqlForBody = sql;
        if (param.getContextList() != null && !param.getContextList().isEmpty()) {
            sqlForBody = param.getContextList().get(0);
            opts.put("contextList", param.getContextList());
        }
        // 连接级通过后：对最终 SQL 正文做表级可读校验（多段 context 逐段）
        if (param.getContextList() != null && !param.getContextList().isEmpty()) {
            for (String c : param.getContextList()) {
                apiBuildTableAccess.assertSqlTablesSelectable(portalDsCheck, engine, c);
            }
        } else {
            apiBuildTableAccess.assertSqlTablesSelectable(portalDsCheck, engine, sqlForBody);
        }
        ensureProjectionExtId(binding);
        String srName = ExternalName.of(binding.getWs(), binding.getId());
        Map<String, Object> body = sqlrestClient.buildSaveBody(
                srName,
                StrUtil.blankToDefault(param.getDescription(), binding.getName()),
                binding.getMethod(),
                binding.getPublicPath(),
                sqlForBody,
                srParams,
                sqlrestId,
                binding.getContentType(),
                srDsId,
                engine,
                opts);

        Map<String, Object> srResp = sqlrestId == null
                ? sqlrestClient.createAssignment(body)
                : sqlrestClient.updateAssignment(body);

        boolean degraded = !Boolean.TRUE.equals(srResp.get("ok"));
        if (!degraded && srResp.get("data") != null) {
            Object data = srResp.get("data");
            if (data instanceof Number n) {
                binding.setSqlrestApiId(String.valueOf(n.longValue()));
            } else if (data instanceof Map<?, ?> map && map.get("id") != null) {
                binding.setSqlrestApiId(String.valueOf(map.get("id")));
            } else if (sqlrestId != null) {
                binding.setSqlrestApiId(String.valueOf(sqlrestId));
            }
        }
        if (degraded) {
            binding.setLastError(String.valueOf(srResp.get("message")));
        } else {
            binding.setLastError(null);
            if (StrUtil.isBlank(binding.getState()) || "retired".equals(binding.getState())) {
                binding.setState("draft");
            }
        }

        if (StrUtil.isBlank(binding.getId())) {
            bindingMapper.insert(binding);
        } else {
            binding.setRevision(binding.getRevision() == null ? 1 : binding.getRevision() + 1);
            bindingMapper.updateById(binding);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("binding", toPortalCard(binding, true));
        result.put("sqlrest", srResp);
        result.put("degraded", degraded);
        // 门户草稿已落库即算保存成功；SQLREST 同步失败用 degraded 表达，勿与 ok 混为一谈
        result.put("ok", true);
        result.put("sqlrestOk", !degraded);
        result.put("portalSaved", true);
        return result;
    }

    @Override
    public Map<String, Object> trial(DataapiTrialParam param) {
        String sql = param.getSql();
        List<Map<String, Object>> portalParams = param.getParams();
        Long dsId = param.getDatasourceId();
        String portalDsId = StrUtil.blankToDefault(param.getPortalDsId(), param.getDsId());
        if (StrUtil.isBlank(portalDsId) && StrUtil.isNotBlank(param.getId())) {
            DataapiApiBinding early = bindingMapper.selectById(param.getId());
            if (early != null) {
                portalDsId = early.getPortalDsId();
            }
        }
        if (StrUtil.isNotBlank(portalDsId) && !secAuthGrantService.canUseDatasource(portalDsId)) {
            throw new CommonException("无权使用该数据源构建 API（须为拥有者或持有 EDIT/MANAGE）");
        }
        if (StrUtil.isNotBlank(portalDsId)) {
            Long resolved = sqlrestProjector.resolveSqlrestDatasourceId(portalDsId);
            if (resolved == null) {
                throw new CommonException("数据源尚未投影到 SQLREST，请先在数据源中心完成登记投影");
            }
            dsId = resolved;
        }
        if (dsId == null) {
            dsId = sqlrestClient.defaultDatasourceId();
        }
        String engine = StrUtil.blankToDefault(param.getEngine(), "SQL").trim().toUpperCase();
        if (!"GROOVY".equals(engine)) {
            engine = "SQL";
        }

        if (StrUtil.isNotBlank(param.getId())) {
            DataapiApiBinding b = requireBinding(param.getId());
            if (StrUtil.isBlank(sql) && StrUtil.isNotBlank(b.getSqlrestApiId())) {
                return enrichTrial(sqlrestClient.trial(b.getSqlrestApiId()), null);
            }
            if (portalParams == null && StrUtil.isNotBlank(b.getParamJson())) {
                portalParams = new ArrayList<>();
                for (Object o : JSONUtil.parseArray(b.getParamJson())) {
                    portalParams.add(JSONUtil.parseObj(o));
                }
            }
        }
        List<String> contexts = param.getContextList();
        if ((contexts == null || contexts.isEmpty()) && StrUtil.isNotBlank(sql)) {
            contexts = List.of(sql);
        }
        if (contexts == null || contexts.isEmpty()) {
            throw new CommonException("试跑需要 SQL 或 Groovy 脚本");
        }
        // 连接级通过后：对试跑各段 SQL 做表级可读校验
        for (String c : contexts) {
            apiBuildTableAccess.assertSqlTablesSelectable(portalDsId, engine, c);
        }
        List<String> contextList = new ArrayList<>();
        for (String c : contexts) {
            contextList.add("GROOVY".equals(engine) ? c : SqlrestClient.toSqlrestSql(c));
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("dataSourceId", dsId);
        req.put("engine", engine);
        req.put("namingStrategy", StrUtil.blankToDefault(param.getNamingStrategy(), "CAMEL_CASE"));
        req.put("formatMap", param.getFormatMap() != null ? param.getFormatMap() : List.of());
        req.put("contextList", contextList);
        req.put("paramValues", sqlrestClient.toDebugParamValues(portalParams));
        return enrichTrial(sqlrestClient.debug(req), contextList);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> publish(DataapiIdParam param) {
        DataapiApiBinding b = requireBinding(param.getId());
        if (StrUtil.isBlank(b.getSqlrestApiId())) {
            throw new CommonException("请先构建 SQLREST 接口（build）");
        }
        boolean requireTicket = lhProperties.getDataapi() != null && lhProperties.getDataapi().isRequirePublishTicket();
        String ticketNo = StrUtil.blankToDefault(param.getPublishTicketNo(), b.getPublishTicketNo());
        if (StrUtil.isBlank(ticketNo) && requireTicket) {
            // 审批通过后可不手填：自动取该绑定最近一张已通过的 api_publish
            ticketNo = applyTicketService.findLatestApprovedApiPublishTicketNo(b.getId());
        }
        if (requireTicket) {
            applyTicketService.assertApprovedApiPublishTicket(ticketNo, b.getId());
            b.setPublishTicketNo(ticketNo);
        } else if (StrUtil.isNotBlank(ticketNo)) {
            // 已填单号则必须已审批（避免「提交申请后立刻点发布」踩坑时提示不清）
            applyTicketService.assertApprovedApiPublishTicket(ticketNo, b.getId());
            b.setPublishTicketNo(ticketNo);
        }
        long apiId = Long.parseLong(b.getSqlrestApiId());
        Map<String, Object> pub = sqlrestClient.publish(apiId, "lakehouse " + b.getName());
        boolean degraded = !Boolean.TRUE.equals(pub.get("ok"));

        Long commitId = null;
        Integer version = null;
        if (!degraded) {
            Map<String, Object> versions = sqlrestClient.listVersions(apiId);
            if (Boolean.TRUE.equals(versions.get("ok")) && versions.get("data") instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                cn.hutool.json.JSONObject v = JSONUtil.parseObj(first);
                commitId = v.getLong("commitId");
                version = v.getInt("version");
            }
        }
        Map<String, Object> deploy = Map.of("ok", false, "skipped", true);
        if (commitId != null) {
            deploy = sqlrestClient.deploy(apiId, commitId);
            if (!Boolean.TRUE.equals(deploy.get("ok"))) {
                degraded = true;
            }
        } else {
            degraded = true;
        }

        String routeId = ExternalName.of(b.getWs(), b.getId());
        int qps = b.getQpsLimit() == null ? 100 : b.getQpsLimit();
        int burst = b.getBurstLimit() == null ? qps * 2 : b.getBurstLimit();
        Map<String, Object> apisix = Map.of("ok", true, "skipped", true, "edgeMode", sqlrestClient.edgeMode());
        if (sqlrestClient.useApisixEdge()) {
            externalBindingGuard.assertApisixRouteIdFree(routeId, b.getId());
            apisix = apisixClient.upsertRoute(
                    routeId, b.getPublicPath(), b.getMethod(), sqlrestClient.executorUpstream(), qps, burst);
            if (!Boolean.TRUE.equals(apisix.get("ok"))) {
                degraded = true;
            }
            b.setApisixRouteId(routeId);
        } else {
            // gateway 模式：不写 APISIX；对外入口 = SQLREST Gateway
            b.setApisixRouteId(null);
        }

        if (commitId != null) {
            b.setSqlrestCommitId(String.valueOf(commitId));
        }
        if (version != null) {
            b.setSqlrestVersion(version);
        }
        b.setState(degraded ? b.getState() : "published");
        if (!degraded) {
            b.setState("published");
            b.setLastPublishAt(new Date());
            b.setLastError(null);
        } else {
            b.setLastError(StrUtil.join(" | ",
                    String.valueOf(pub.get("message")),
                    String.valueOf(deploy.get("message")),
                    String.valueOf(apisix.get("message"))));
            // 部分成功也标记 published，便于门户展示（与 degraded 标志并存）
            if (Boolean.TRUE.equals(pub.get("ok")) && Boolean.TRUE.equals(deploy.get("ok"))) {
                b.setState("published");
                b.setLastPublishAt(new Date());
            }
        }
        b.setRevision(b.getRevision() == null ? 1 : b.getRevision() + 1);
        bindingMapper.updateById(b);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", !degraded);
        result.put("degraded", degraded);
        result.put("edgeMode", sqlrestClient.edgeMode());
        result.put("gatewayUrl", sqlrestClient.gatewayUrl());
        result.put("binding", toPortalCard(b, true));
        result.put("sqlrestPublish", pub);
        result.put("sqlrestDeploy", deploy);
        result.put("apisix", apisix);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> retire(DataapiIdParam param) {
        DataapiApiBinding b = requireBinding(param.getId());
        Map<String, Object> sr = Map.of("ok", true, "skipped", true);
        if (StrUtil.isNotBlank(b.getSqlrestApiId())) {
            sr = sqlrestClient.retire(Long.parseLong(b.getSqlrestApiId()));
        }
        Map<String, Object> ax = Map.of("ok", true, "skipped", true, "edgeMode", sqlrestClient.edgeMode());
        if (sqlrestClient.useApisixEdge() && StrUtil.isNotBlank(b.getApisixRouteId())) {
            ax = apisixClient.deleteRoute(b.getApisixRouteId());
        }
        b.setState("retired");
        b.setRevision(b.getRevision() == null ? 1 : b.getRevision() + 1);
        bindingMapper.updateById(b);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", Boolean.TRUE.equals(sr.get("ok")) && Boolean.TRUE.equals(ax.get("ok")));
        result.put("degraded", Boolean.TRUE.equals(sr.get("degraded")) || Boolean.TRUE.equals(ax.get("degraded")));
        result.put("sqlrest", sr);
        result.put("apisix", ax);
        result.put("binding", toPortalCard(b, true));
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(DataapiIdParam param) {
        DataapiApiBinding b = requireBinding(param.getId());
        if ("published".equals(b.getState())) {
            throw new CommonException("已发布接口请先下线再删除");
        }
        b.setDeleteFlag("DELETED");
        bindingMapper.updateById(b);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> routes() {
        String edge = sqlrestClient.edgeMode();
        List<Map<String, Object>> fromBinding = new ArrayList<>();
        List<DataapiApiBinding> published = bindingMapper.selectList(
                baseQw(WS_DEFAULT).eq("state", "published"));
        String gw = sqlrestClient.gatewayUrl();
        for (DataapiApiBinding b : published) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", b.getPublicPath());
            if (sqlrestClient.useApisixEdge()) {
                row.put("upstream", "APISIX → " + sqlrestClient.executorUpstream() + " → SQLREST");
                row.put("status", StrUtil.isNotBlank(b.getApisixRouteId()) ? "ok" : "warn");
                row.put("note", b.getApisixRouteId());
            } else {
                row.put("upstream", "SQLREST Gateway → JDBC 源");
                row.put("status", "ok");
                row.put("note", gw + StrUtil.blankToDefault(b.getPublicPath(), ""));
            }
            row.put("auth", StrUtil.blankToDefault(b.getAuthMode(), "Token"));
            row.put("rate", (b.getQpsLimit() == null ? 100 : b.getQpsLimit()) + "/s");
            row.put("breaker", "✓");
            row.put("meter", "✓");
            row.put("edgeMode", edge);
            fromBinding.add(row);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("edgeMode", edge);
        m.put("gatewayUrl", gw);
        m.put("bindings", fromBinding);
        if (sqlrestClient.useApisixEdge()) {
            Map<String, Object> live = apisixClient.listRoutes();
            m.put("ok", live.get("ok"));
            m.put("degraded", live.get("degraded"));
            m.put("message", live.get("message"));
            m.put("apisix", live.get("list"));
        } else {
            m.put("ok", true);
            m.put("apisix", List.of());
            m.put("message", "边缘仅 SQLREST Gateway（不做 APISIX）");
        }
        return m;
    }

    @Override
    public Map<String, Object> syncApisix(String ws) {
        if (!sqlrestClient.useApisixEdge()) {
            Map<String, Object> skip = new LinkedHashMap<>();
            skip.put("ok", true);
            skip.put("skipped", true);
            skip.put("synced", 0);
            skip.put("failed", 0);
            skip.put("edgeMode", sqlrestClient.edgeMode());
            skip.put("message", "数据服务不做 APISIX：边缘仅 SQLREST Gateway；请在工作台/SQLREST 发版上线");
            return skip;
        }
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        List<DataapiApiBinding> list = bindingMapper.selectList(baseQw(workspace).eq("state", "published"));
        int ok = 0;
        int fail = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (DataapiApiBinding b : list) {
            String routeId = ExternalName.of(b.getWs(), b.getId());
            int qps = b.getQpsLimit() == null ? 100 : b.getQpsLimit();
            int burst = b.getBurstLimit() == null ? qps * 2 : b.getBurstLimit();
            try {
                externalBindingGuard.assertApisixRouteIdFree(routeId, b.getId());
            } catch (CommonException ex) {
                fail++;
                details.add(Map.of("ok", false, "routeId", routeId, "message", ex.getMessage()));
                continue;
            }
            Map<String, Object> r = apisixClient.upsertRoute(
                    routeId, b.getPublicPath(), b.getMethod(), sqlrestClient.executorUpstream(), qps, burst);
            if (Boolean.TRUE.equals(r.get("ok"))) {
                ok++;
                b.setApisixRouteId(routeId);
                bindingMapper.updateById(b);
            } else {
                fail++;
            }
            details.add(r);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", fail == 0);
        m.put("synced", ok);
        m.put("failed", fail);
        m.put("details", details);
        return m;
    }

    @Override
    public List<Map<String, Object>> keys(String ws) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        List<DataapiApiKeyMeta> metas = keyMetaMapper.selectList(new QueryWrapper<DataapiApiKeyMeta>().lambda()
                .eq(DataapiApiKeyMeta::getWs, workspace)
                .eq(DataapiApiKeyMeta::getDeleteFlag, NOT_DELETE)
                .orderByDesc(DataapiApiKeyMeta::getCreateTime));
        List<Map<String, Object>> list = new ArrayList<>();
        for (DataapiApiKeyMeta k : metas) {
            DataapiApiBinding b = bindingMapper.selectById(k.getBindingId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", k.getId());
            row.put("name", k.getConsumerName());
            row.put("app", k.getConsumerName());
            row.put("appKey", k.getAppKey());
            row.put("api", b != null ? b.getPublicPath() : "-");
            row.put("apiName", b != null ? b.getName() : null);
            row.put("method", b != null ? b.getMethod() : "-");
            row.put("bindingId", k.getBindingId());
            row.put("user", k.getApplicant());
            row.put("applicant", k.getApplicant());
            row.put("status", k.getStatus());
            row.put("cls", statusTag(k.getStatus()));
            row.put("keyHint", k.getKeyHint());
            row.put("qps", k.getQpsLimit());
            row.put("qpsLimit", k.getQpsLimit());
            row.put("ticketId", k.getTicketId());
            row.put("ticketNo", ticketNoFromKeyRemark(k.getRemark()));
            row.put("expireAt", k.getExpireAt() == null ? null : k.getExpireAt().toString());
            row.put("createTime", k.getCreateTime() == null ? null : k.getCreateTime().toString());
            row.put("remark", k.getRemark());
            row.put("description", "末位 ···" + StrUtil.blankToDefault(k.getKeyHint(), "????")
                    + (k.getQpsLimit() != null ? " · " + k.getQpsLimit() + " QPS" : ""));
            list.add(row);
        }
        return list;
    }

    /** remark 约定：api_subscribe {ticketNo} */
    private static String ticketNoFromKeyRemark(String remark) {
        if (StrUtil.isBlank(remark)) {
            return null;
        }
        String prefix = "api_subscribe ";
        if (remark.startsWith(prefix)) {
            String no = remark.substring(prefix.length()).trim();
            return StrUtil.blankToDefault(no, null);
        }
        return null;
    }

    @Override
    public Map<String, Object> embedUrl() {
        String root = sqlrestClient.embedUrl();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sqlrest", root);
        m.put("interfaceList", root + "/#/interface/list");
        m.put("interfaceCreate", root + "/#/interface/create");
        m.put("datasource", root + "/#/datasource");
        m.put("client", root + "/#/setting/client");
        m.put("online", root + "/#/service/search");
        m.put("gateway", sqlrestClient.gatewayUrl());
        m.put("edgeMode", sqlrestClient.edgeMode());
        m.put("superset", null);
        return m;
    }

    @Override
    public Map<String, Object> workbench() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("embed", embedUrl());
        m.put("edgeMode", sqlrestClient.edgeMode());
        m.put("gatewayUrl", sqlrestClient.gatewayUrl());
        Map<String, Object> stats = callStats(7);
        m.put("callStats", stats);
        m.put("counter", Map.of("ok", true, "data", stats.get("counter")));
        m.put("trend", Map.of("ok", true, "data", stats.get("trend")));
        m.put("topPath", Map.of("ok", true, "data", stats.get("topPath")));
        m.put("assignments", sqlrestClient.listAssignments("", 1, 50));
        m.put("clients", sqlrestClient.listClients());
        m.put("authGroups", sqlrestClient.listAuthGroups());
        return m;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> syncFromSqlrest(String ws) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        Map<String, Object> listResp = sqlrestClient.listAssignments("", 1, 200);
        int upserted = 0;
        int skipped = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        if (Boolean.TRUE.equals(listResp.get("ok")) && listResp.get("data") instanceof List<?> rows) {
            for (Object row : rows) {
                cn.hutool.json.JSONObject o = JSONUtil.parseObj(row);
                String apiId = String.valueOf(o.get("id"));
                DataapiApiBinding exist = bindingMapper.selectOne(baseQw(workspace)
                        .eq("sqlrest_api_id", apiId).last("LIMIT 1"));
                String path = normalizePublicFromSqlrest(o.getStr("path"));
                String method = StrUtil.blankToDefault(o.getStr("method"), "GET").toUpperCase();
                boolean online = Boolean.TRUE.equals(o.getBool("status"));
                if (exist == null) {
                    DataapiApiBinding b = new DataapiApiBinding();
                    b.setId(IdUtil.getSnowflakeNextIdStr());
                    b.setRevision(1);
                    b.setStatus("active");
                    b.setWs(workspace);
                    b.setDeleteFlag(NOT_DELETE);
                    b.setName(StrUtil.blankToDefault(o.getStr("name"), path));
                    b.setPublicPath(path);
                    b.setMethod(method);
                    b.setSqlrestApiId(apiId);
                    if (o.get("datasourceId") != null) {
                        b.setSqlrestDatasourceId(String.valueOf(o.get("datasourceId")));
                    }
                    b.setSourceKind("sql");
                    b.setState(online ? "published" : "draft");
                    b.setAuthMode("Token");
                    b.setQpsLimit(100);
                    b.setBurstLimit(200);
                    b.setPublishEnv("stg");
                    b.setRemark(o.getStr("description"));
                    bindingMapper.insert(b);
                    upserted++;
                    details.add(Map.of("action", "insert", "sqlrestApiId", apiId, "path", path));
                } else {
                    exist.setName(StrUtil.blankToDefault(o.getStr("name"), exist.getName()));
                    exist.setPublicPath(path);
                    exist.setMethod(method);
                    if (online && !"published".equals(exist.getState())) {
                        exist.setState("draft");
                    }
                    if (!online && "published".equals(exist.getState())) {
                        /* keep published until retire via portal */
                    }
                    if (o.get("datasourceId") != null) {
                        exist.setSqlrestDatasourceId(String.valueOf(o.get("datasourceId")));
                    }
                    exist.setRevision(exist.getRevision() == null ? 1 : exist.getRevision() + 1);
                    bindingMapper.updateById(exist);
                    upserted++;
                    details.add(Map.of("action", "update", "id", exist.getId(), "sqlrestApiId", apiId));
                }
            }
        } else {
            skipped++;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", Boolean.TRUE.equals(listResp.get("ok")));
        m.put("degraded", listResp.get("degraded"));
        m.put("message", listResp.get("message"));
        m.put("upserted", upserted);
        m.put("skipped", skipped);
        m.put("details", details);
        m.put("sqlrest", listResp);
        return m;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> register(DataapiBindingParam param) {
        if (StrUtil.isBlank(param.getSqlrestApiId())) {
            throw new CommonException("请提供 sqlrestApiId（在 SQLREST Manager 构建后登记）");
        }
        Map<String, Object> detail = sqlrestClient.detail(param.getSqlrestApiId());
        if (!Boolean.TRUE.equals(detail.get("ok")) || detail.get("data") == null) {
            throw new CommonException("无法读取 SQLREST 接口: " + detail.get("message"));
        }
        cn.hutool.json.JSONObject data = JSONUtil.parseObj(detail.get("data"));
        if (StrUtil.isBlank(param.getPublicPath())) {
            param.setPublicPath(normalizePublicFromSqlrest(data.getStr("path")));
        }
        if (StrUtil.isBlank(param.getName())) {
            param.setName(StrUtil.blankToDefault(data.getStr("name"), param.getPublicPath()));
        }
        if (StrUtil.isBlank(param.getMethod())) {
            param.setMethod(data.getStr("method"));
        }
        if (data.get("datasourceId") != null) {
            param.setSqlrestDatasourceId(String.valueOf(data.get("datasourceId")));
        }
        DataapiApiBinding exist = bindingMapper.selectOne(baseQw(StrUtil.blankToDefault(param.getWs(), WS_DEFAULT))
                .eq("sqlrest_api_id", param.getSqlrestApiId()).last("LIMIT 1"));
        DataapiApiBinding b;
        if (exist != null) {
            applyParam(exist, param);
            exist.setRevision(exist.getRevision() == null ? 1 : exist.getRevision() + 1);
            bindingMapper.updateById(exist);
            b = exist;
        } else {
            b = newBinding(param);
            bindingMapper.insert(b);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("binding", toPortalCard(b, true));
        m.put("engine", data.getStr("engine"));
        m.put("hint", "SQL/Groovy 由门户经 SQLREST Manager API 构建；此处登记绑定与边缘发布");
        return m;
    }

    @Override
    public Map<String, Object> parseParams(DataapiParseParam param) {
        if (param == null || StrUtil.isBlank(param.getSql())) {
            throw new CommonException("sql 不能为空");
        }
        Map<String, Object> sr = sqlrestClient.parseParams(param.getSql());
        Map<String, Object> m = new LinkedHashMap<>(sr);
        if (Boolean.TRUE.equals(sr.get("ok")) && sr.get("data") instanceof List<?> list) {
            List<Map<String, Object>> params = new ArrayList<>();
            for (Object o : list) {
                cn.hutool.json.JSONObject row = JSONUtil.parseObj(o);
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("name", row.getStr("name"));
                // SQLREST parse 无类型；门户默认 string，trial/build 经 mapParamType → STRING
                String parsedType = StrUtil.blankToDefault(row.getStr("type"), "string");
                p.put("type", parsedType);
                p.put("required", false);
                p.put("isArray", Boolean.TRUE.equals(row.getBool("isArray")));
                p.put("example", "");
                p.put("desc", "");
                params.add(p);
            }
            m.put("params", params);
        }
        return m;
    }

    @Override
    public Map<String, Object> sqlrestOptions() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> naming = sqlrestClient.responseNamingStrategies();
        Map<String, Object> formats = sqlrestClient.responseTypeFormats();
        Map<String, Object> comps = sqlrestClient.completions();
        Map<String, Object> modules = sqlrestClient.listModules();
        Map<String, Object> groups = sqlrestClient.listAuthGroups();
        m.put("namingStrategies", Boolean.TRUE.equals(naming.get("ok")) ? naming.get("data") : List.of());
        m.put("typeFormats", Boolean.TRUE.equals(formats.get("ok")) ? formats.get("data") : List.of());
        m.put("completions", Boolean.TRUE.equals(comps.get("ok")) ? comps.get("data") : List.of());
        m.put("modules", unwrapSqlrestList(modules));
        m.put("authGroups", unwrapSqlrestList(groups));
        LhProperties.Sqlrest cfg = lhProperties.getSqlrest();
        m.put("defaultModuleId", cfg != null ? cfg.getDefaultModuleId() : 1L);
        m.put("defaultGroupId", cfg != null ? cfg.getDefaultGroupId() : 1L);
        m.put("ok", Boolean.TRUE.equals(naming.get("ok")) || Boolean.TRUE.equals(formats.get("ok")));
        m.put("degraded", !Boolean.TRUE.equals(m.get("ok")));
        return m;
    }

    private static List<Map<String, Object>> unwrapSqlrestList(Map<String, Object> resp) {
        if (resp == null) {
            return List.of();
        }
        Object data = resp.get("data");
        if (!(data instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object row : list) {
            if (row instanceof Map<?, ?> map) {
                Map<String, Object> item = new LinkedHashMap<>();
                Object id = map.get("id") != null ? map.get("id") : map.get("moduleId");
                if (id == null) {
                    id = map.get("groupId");
                }
                item.put("id", id);
                Object name = map.get("name");
                if (name == null) {
                    name = map.get("moduleName");
                }
                if (name == null) {
                    name = map.get("groupName");
                }
                item.put("name", name != null ? String.valueOf(name) : String.valueOf(id));
                out.add(item);
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> gatewayProbe(DataapiGatewayProbeParam param) {
        String path = param != null ? param.getPath() : null;
        String method = param != null ? StrUtil.blankToDefault(param.getMethod(), "GET") : "GET";
        DataapiApiBinding binding = null;
        if (param != null && StrUtil.isNotBlank(param.getId())) {
            binding = requireBinding(param.getId());
            path = binding.getPublicPath();
            method = StrUtil.blankToDefault(binding.getMethod(), "GET");
        }
        if (StrUtil.isBlank(path)) {
            throw new CommonException("请提供绑定 id 或 path");
        }
        String base = sqlrestClient.gatewayUrl();
        if (StrUtil.isBlank(base)) {
            throw new CommonException("未配置 lh.sqlrest.gateway-url");
        }
        String p = path.trim();
        String srPath = SqlrestClient.toSqlrestPath(p);
        if (StrUtil.isBlank(srPath)) {
            throw new CommonException("路径无效：请填写如 /api/demo 的对外路径（勿只填 /api/）");
        }
        String url = SqlrestClient.toGatewayRequestUrl(base, p);
        int timeout = lhProperties.getDataapi() != null
                ? Math.max(2000, lhProperties.getDataapi().getGatewayProbeTimeoutMs())
                : 8000;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("edgeMode", sqlrestClient.edgeMode());
        m.put("gatewayUrl", base);
        m.put("publicPath", p.startsWith("/") ? p : "/" + p);
        m.put("sqlrestPath", srPath);
        m.put("requestUrl", url);
        m.put("method", method.toUpperCase());
        enrichProbeBinding(m, binding);
        long t0 = System.currentTimeMillis();
        try {
            HttpRequest req = "POST".equalsIgnoreCase(method)
                    ? HttpRequest.post(url)
                    : "PUT".equalsIgnoreCase(method)
                    ? HttpRequest.put(url)
                    : "DELETE".equalsIgnoreCase(method)
                    ? HttpRequest.delete(url)
                    : HttpRequest.get(url);
            HttpResponse resp = req.timeout(timeout).execute();
            String body = resp.body();
            int status = resp.getStatus();
            // 路由可达且业务成功才 ok；4xx（含 404）一律视为失败，避免 toast 把 404 当成功
            boolean ok = status >= 200 && status < 300;
            m.put("ok", ok);
            m.put("httpStatus", status);
            m.put("latencyMs", System.currentTimeMillis() - t0);
            m.put("bodyPreview", body == null ? "" : body.substring(0, Math.min(800, body.length())));
            applyProbeDiagnosis(m, status, null);
        } catch (Exception e) {
            m.put("ok", false);
            m.put("latencyMs", System.currentTimeMillis() - t0);
            m.put("message", e.getMessage());
            applyProbeDiagnosis(m, null, e.getMessage());
        }
        return m;
    }

    /** 探针附带绑定 / SQLREST 在线态，便于 404 时给出可读原因 */
    private void enrichProbeBinding(Map<String, Object> m, DataapiApiBinding binding) {
        if (binding == null) {
            return;
        }
        m.put("bindingId", binding.getId());
        m.put("bindingState", binding.getState());
        m.put("sqlrestApiId", binding.getSqlrestApiId());
        Boolean sqlrestOnline = null;
        String sqlrestPathStored = null;
        String sqlrestMethod = null;
        if (StrUtil.isNotBlank(binding.getSqlrestApiId())) {
            try {
                Map<String, Object> detail = sqlrestClient.detail(binding.getSqlrestApiId());
                if (Boolean.TRUE.equals(detail.get("ok")) && detail.get("data") != null) {
                    cn.hutool.json.JSONObject data = JSONUtil.parseObj(detail.get("data"));
                    if (data.containsKey("status")) {
                        sqlrestOnline = data.getBool("status");
                    } else if (data.containsKey("online")) {
                        sqlrestOnline = data.getBool("online");
                    }
                    sqlrestPathStored = data.getStr("path");
                    sqlrestMethod = data.getStr("method");
                } else if (detail.get("message") != null) {
                    m.put("sqlrestDetailMessage", String.valueOf(detail.get("message")));
                }
            } catch (Exception e) {
                m.put("sqlrestDetailMessage", e.getMessage());
            }
        }
        m.put("sqlrestOnline", sqlrestOnline);
        if (sqlrestPathStored != null) {
            m.put("sqlrestPathStored", sqlrestPathStored);
        }
        if (sqlrestMethod != null) {
            m.put("sqlrestMethod", sqlrestMethod);
        }
    }

    private static void applyProbeDiagnosis(Map<String, Object> m, Integer httpStatus, String transportError) {
        String bindingState = m.get("bindingState") == null ? null : String.valueOf(m.get("bindingState"));
        Boolean sqlrestOnline = m.get("sqlrestOnline") instanceof Boolean b ? b : null;
        String method = String.valueOf(m.getOrDefault("method", "GET"));
        String requestUrl = String.valueOf(m.getOrDefault("requestUrl", ""));
        String hint;
        String suggestion;
        if (transportError != null) {
            hint = "无法连接 Gateway：" + transportError;
            suggestion = "检查 lh.sqlrest.gateway-url 与网络连通性";
        } else if (httpStatus != null && httpStatus >= 200 && httpStatus < 300) {
            hint = "Gateway 路由可达";
            suggestion = null;
        } else if (httpStatus != null && (httpStatus == 401 || httpStatus == 403)) {
            hint = "鉴权失败（通常不是路径问题）；open=false 时需订阅 Key（X-App-Key + Bearer）";
            suggestion = "在数据服务密钥区签发 Key，或将接口设为 open 后再探";
        } else if (httpStatus != null && httpStatus == 405) {
            hint = "方法不允许：当前 " + method + "，请与绑定 / SQLREST method 一致";
            suggestion = "核对接口配置中的 HTTP 方法后重试";
        } else if (httpStatus != null && httpStatus == 404) {
            boolean hasBinding = m.get("bindingId") != null;
            Object apiIdObj = m.get("sqlrestApiId");
            boolean missingApiId = hasBinding && (apiIdObj == null || StrUtil.isBlank(String.valueOf(apiIdObj)));
            if (hasBinding && bindingState != null && !"published".equalsIgnoreCase(bindingState)) {
                hint = "Gateway HTTP 404：绑定状态为 " + bindingState + "，接口可能尚未发布到 Gateway";
                suggestion = "先保存并发布（SQLREST publish + deploy），再点 Gateway 探针";
            } else if (hasBinding && Boolean.FALSE.equals(sqlrestOnline)) {
                hint = "Gateway HTTP 404：绑定已 published，但 SQLREST 侧未 online";
                suggestion = "在工作台重新发布，或到 SQLREST Manager 确认已 deploy";
            } else if (missingApiId) {
                hint = "Gateway HTTP 404：尚未构建 SQLREST 接口（无 sqlrestApiId）";
                suggestion = "先保存（build）再发布，然后重试探针";
            } else {
                hint = "Gateway HTTP 404：无此路由（" + method + " " + requestUrl + "）";
                suggestion = hasBinding
                        ? "核对路径/方法是否与 SQLREST 一致；若刚改过路径请重新发布"
                        : "确认路径已在 SQLREST 发布上线；工作台可先保存并发布后再探";
            }
        } else if (httpStatus != null) {
            hint = "Gateway HTTP " + httpStatus;
            suggestion = "查看 bodyPreview；确认接口已发布且方法正确";
        } else {
            hint = "探针未得到 HTTP 状态";
            suggestion = "检查 Gateway 配置";
        }
        m.put("hint", hint);
        m.put("message", hint);
        if (suggestion != null) {
            m.put("suggestion", suggestion);
        }
    }

    @Override
    public Map<String, Object> callStats(Integer days) {
        int d = days == null ? 7 : Math.max(1, Math.min(days, 90));
        Map<String, Object> counter;
        Map<String, Object> trend;
        Map<String, Object> top;
        try {
            counter = sqlrestClient.overviewCounter();
        } catch (Exception e) {
            counter = Map.of("ok", false, "message", e.getMessage());
        }
        try {
            trend = sqlrestClient.overviewTrend(d);
        } catch (Exception e) {
            trend = Map.of("ok", false, "message", e.getMessage());
        }
        try {
            top = sqlrestClient.overviewTopPath(d, 10);
        } catch (Exception e) {
            top = Map.of("ok", false, "message", e.getMessage());
        }
        Map<String, Object> stats = DataapiCallStatsSupport.assemble(d, counter, trend, top);
        stats.put("gatewayUrl", sqlrestClient.gatewayUrl());
        stats.put("edgeMode", sqlrestClient.edgeMode());
        return stats;
    }

    @Override
    public Map<String, Object> openapi(String ws, String id) {
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        List<DataapiApiBinding> bindings = new ArrayList<>();
        if (StrUtil.isNotBlank(id)) {
            DataapiApiBinding one = requireBinding(id);
            bindings.add(one);
        } else {
            bindings.addAll(bindingMapper.selectList(baseQw(workspace)
                    .eq("state", "published")
                    .orderByAsc("public_path")));
            if (bindings.isEmpty()) {
                // 无 published 时导出全部非 retired，便于草稿验收
                bindings.addAll(bindingMapper.selectList(baseQw(workspace)
                        .ne("state", "retired")
                        .orderByAsc("public_path")));
            }
        }
        Map<String, Map<String, Object>> sqlrestById = new LinkedHashMap<>();
        for (DataapiApiBinding b : bindings) {
            if (StrUtil.isBlank(b.getSqlrestApiId())) {
                continue;
            }
            try {
                sqlrestById.put(b.getSqlrestApiId(), sqlrestClient.detail(b.getSqlrestApiId()));
            } catch (Exception e) {
                sqlrestById.put(b.getSqlrestApiId(), Map.of("ok", false, "message", e.getMessage()));
            }
        }
        String title = StrUtil.isNotBlank(id) && !bindings.isEmpty()
                ? bindings.get(0).getName()
                : "Lakehouse Data API (" + workspace + ")";
        return DataapiOpenApiBuilder.build(bindings, sqlrestById, sqlrestClient.gatewayUrl(), title);
    }

    private static Map<String, Object> assignmentOptsFromParam(DataapiBindingParam param) {
        Map<String, Object> opts = new LinkedHashMap<>();
        if (param.getOpen() != null) {
            opts.put("open", param.getOpen());
        }
        if (param.getAlarm() != null) {
            opts.put("alarm", param.getAlarm());
        }
        if (param.getFlowStatus() != null) {
            opts.put("flowStatus", param.getFlowStatus());
        }
        if (param.getFlowGrade() != null) {
            opts.put("flowGrade", param.getFlowGrade());
        }
        if (param.getFlowCount() != null) {
            opts.put("flowCount", param.getFlowCount());
        }
        if (StrUtil.isNotBlank(param.getCacheKeyType())) {
            opts.put("cacheKeyType", param.getCacheKeyType());
        }
        if (param.getCacheKeyExpr() != null) {
            opts.put("cacheKeyExpr", param.getCacheKeyExpr());
        }
        if (param.getCacheExpireSeconds() != null) {
            opts.put("cacheExpireSeconds", param.getCacheExpireSeconds());
        }
        if (StrUtil.isNotBlank(param.getNamingStrategy())) {
            opts.put("namingStrategy", param.getNamingStrategy());
        }
        if (param.getOutputs() != null) {
            opts.put("outputs", param.getOutputs());
        }
        if (param.getModuleId() != null) {
            opts.put("moduleId", param.getModuleId());
        }
        if (param.getGroupId() != null) {
            opts.put("groupId", param.getGroupId());
        }
        if (param.getFormatMap() != null && !param.getFormatMap().isEmpty()) {
            opts.put("formatMap", param.getFormatMap());
        } else if (StrUtil.isNotBlank(param.getResponseFormat())) {
            boolean useSystem = !"origin".equalsIgnoreCase(param.getResponseFormat())
                    && !"nil".equalsIgnoreCase(param.getResponseFormat());
            opts.put("formatMap", List.of(Map.of(
                    "key", "USE_SYSTEM_RESPONSE_FORMAT",
                    "value", String.valueOf(useSystem),
                    "remark", "Response format")));
        }
        return opts;
    }

    private Long resolveSqlrestDatasourceId(DataapiBindingParam param, DataapiApiBinding binding) {
        String portalDsId = StrUtil.blankToDefault(param.getPortalDsId(), param.getDsId());
        if (StrUtil.isBlank(portalDsId)) {
            portalDsId = binding.getPortalDsId();
        }
        Long srDsId = null;
        if (StrUtil.isNotBlank(portalDsId)) {
            binding.setPortalDsId(portalDsId);
            srDsId = sqlrestProjector.resolveSqlrestDatasourceId(portalDsId);
            // 不在构建路径临投影：登记时应已写入 SQLREST
            if (srDsId != null) {
                binding.setSqlrestDatasourceId(String.valueOf(srDsId));
            }
        }
        if (srDsId == null && StrUtil.isNotBlank(param.getSqlrestDatasourceId())) {
            srDsId = parseLong(param.getSqlrestDatasourceId());
            binding.setSqlrestDatasourceId(param.getSqlrestDatasourceId());
        }
        if (srDsId == null && StrUtil.isNotBlank(binding.getSqlrestDatasourceId())) {
            srDsId = parseLong(binding.getSqlrestDatasourceId());
        }
        return srDsId;
    }

    private static String normalizePublicFromSqlrest(String path) {
        String p = StrUtil.blankToDefault(path, "").trim();
        if (p.isEmpty()) {
            return "/api/unnamed";
        }
        if (p.startsWith("/")) {
            return p;
        }
        if (p.startsWith("api/")) {
            return "/" + p;
        }
        return "/api/" + p;
    }

    private Map<String, Object> enrichTrial(Map<String, Object> sr, List<String> sentContexts) {
        Map<String, Object> m = new LinkedHashMap<>(sr);
        if (Boolean.TRUE.equals(sr.get("ok")) && sr.get("data") instanceof Map<?, ?> data) {
            m.put("sample", data.get("answer"));
            m.put("logs", data.get("logs"));
            m.put("types", data.get("types"));
        }
        if (sentContexts != null && !sentContexts.isEmpty()) {
            m.put("sqlPreview", SqlrestClient.sqlPreview(sentContexts, 480));
            m.put("contextCount", sentContexts.size());
        }
        String msg = StrUtil.blankToDefault(String.valueOf(m.getOrDefault("message", "")), "");
        if (msg.contains("could not determine data type of parameter")) {
            m.put("hint",
                    "PostgreSQL 无法推断绑定参数类型：在 concat/LIKE 中请写 #{name}::text 或 CAST(#{name} AS text)；"
                            + "并确认入参类型为 string。工作台可一键「加 ::text」。");
        } else if (msg.toLowerCase().contains("syntax error at or near \"limit\"")) {
            m.put("hint",
                    "SQLREST 会对 SELECT 自动追加 LIMIT/OFFSET。请去掉语句末尾的 LIMIT 与分号（门户已尽量自动剥离）；"
                            + "请核对返回的 sqlPreview。多 SQL 窗口时调试仅用当前窗口。");
        }
        return m;
    }

    private DataapiApiBinding newBinding(DataapiBindingParam param) {
        DataapiApiBinding b = new DataapiApiBinding();
        b.setId(IdUtil.getSnowflakeNextIdStr());
        b.setRevision(1);
        b.setStatus("active");
        b.setWs(StrUtil.blankToDefault(param.getWs(), WS_DEFAULT));
        b.setState("draft");
        b.setDeleteFlag(NOT_DELETE);
        applyParam(b, param);
        ensureProjectionExtId(b);
        return b;
    }

    /** SQLREST/APISIX 投影外部名：写入 ext_id 并做全局唯一校验 */
    private void ensureProjectionExtId(DataapiApiBinding b) {
        String ws = StrUtil.blankToDefault(b.getWs(), WS_DEFAULT);
        String code = StrUtil.blankToDefault(b.getId(), "api");
        String extId = ExternalName.extId(ExternalName.KIND_SQLREST_API, ws, code);
        externalBindingGuard.assertDataapiExtIdFree(extId, b.getId());
        b.setExtId(extId);
    }

    private void applyParam(DataapiApiBinding b, DataapiBindingParam param) {
        if (StrUtil.isNotBlank(param.getName())) {
            b.setName(param.getName());
        }
        if (StrUtil.isNotBlank(param.getPublicPath())) {
            b.setPublicPath(normalizePath(param.getPublicPath()));
        }
        b.setMethod(StrUtil.blankToDefault(param.getMethod(), "GET").toUpperCase());
        if (StrUtil.isNotBlank(param.getSqlrestApiId())) {
            b.setSqlrestApiId(param.getSqlrestApiId());
        }
        b.setSourceKind(StrUtil.blankToDefault(param.getSourceKind(), "sql"));
        b.setSourceRef(param.getSourceRef());
        String portalDsId = StrUtil.blankToDefault(param.getPortalDsId(), param.getDsId());
        if (StrUtil.isNotBlank(portalDsId)) {
            b.setPortalDsId(portalDsId);
        }
        if (StrUtil.isNotBlank(param.getSqlrestDatasourceId())) {
            b.setSqlrestDatasourceId(param.getSqlrestDatasourceId());
        }
        b.setAuthMode(StrUtil.blankToDefault(param.getAuthMode(), "Token"));
        if (param.getQpsLimit() != null) {
            b.setQpsLimit(param.getQpsLimit());
        }
        if (param.getBurstLimit() != null) {
            b.setBurstLimit(param.getBurstLimit());
        }
        b.setDomainCode(param.getDomainCode());
        b.setOwnerUser(param.getOwnerUser());
        b.setPublishEnv(StrUtil.blankToDefault(param.getPublishEnv(), "stg"));
        b.setContentType(StrUtil.blankToDefault(param.getContentType(),
                "GET".equalsIgnoreCase(b.getMethod())
                        ? "application/x-www-form-urlencoded"
                        : "application/json"));
        b.setRemark(param.getRemark());
        if (param.getParams() != null) {
            b.setParamJson(JSONUtil.toJsonStr(param.getParams()));
        }
        if (param.getResponses() != null || param.getResponseFormat() != null || param.getResponseShape() != null) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("fields", param.getResponses());
            resp.put("format", param.getResponseFormat());
            resp.put("shape", param.getResponseShape());
            b.setResponseJson(JSONUtil.toJsonStr(resp));
        }
    }

    private Map<String, Object> toPortalCard(DataapiApiBinding b, boolean detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("name", b.getName());
        m.put("path", b.getPublicPath());
        m.put("method", b.getMethod());
        m.put("domain", StrUtil.blankToDefault(b.getDomainCode(), "—"));
        m.put("state", b.getState());
        m.put("auth", b.getAuthMode());
        m.put("owner", b.getOwnerUser());
        m.put("publishEnv", b.getPublishEnv());
        m.put("qps", b.getQpsLimit() == null ? "—" : String.valueOf(b.getQpsLimit()));
        m.put("burst", b.getBurstLimit());
        m.put("sqlrestApiId", b.getSqlrestApiId());
        m.put("apisixRouteId", b.getApisixRouteId());
        m.put("extId", b.getExtId());
        m.put("sourceKind", b.getSourceKind());
        m.put("sourceRef", b.getSourceRef());
        m.put("portalDsId", b.getPortalDsId());
        m.put("sqlrestDatasourceId", b.getSqlrestDatasourceId());
        m.put("asset", "asset".equals(b.getSourceKind()) ? bare(b.getSourceRef()) : "-");
        m.put("metric", "metric".equals(b.getSourceKind()) ? b.getSourceRef() : "-");
        m.put("desc", StrUtil.blankToDefault(b.getRemark(), b.getName()));
        m.put("sub", "0");
        m.put("rt", "—");
        m.put("level", levelOf(b));
        m.put("levelCls", levelCls(b));
        m.put("publishedAt", b.getLastPublishAt() == null ? null : b.getLastPublishAt().toString());
        m.put("createTime", b.getCreateTime() == null ? null : b.getCreateTime().toString());
        m.put("updateTime", b.getUpdateTime() == null ? null : b.getUpdateTime().toString());
        m.put("lastError", b.getLastError());
        m.put("publishTicketNo", b.getPublishTicketNo());
        if (detail) {
            if (StrUtil.isNotBlank(b.getParamJson())) {
                m.put("params", JSONUtil.parseArray(b.getParamJson()));
            }
            if (StrUtil.isNotBlank(b.getResponseJson())) {
                cn.hutool.json.JSONObject rj = JSONUtil.parseObj(b.getResponseJson());
                m.put("responses", rj.get("fields"));
                m.put("responseFormat", rj.getStr("format"));
                m.put("responseShape", rj.getStr("shape"));
            }
        }
        return m;
    }

    private static String levelOf(DataapiApiBinding b) {
        if ("published".equals(b.getState())) {
            return "prod".equalsIgnoreCase(b.getPublishEnv()) ? "L2" : "stg";
        }
        if ("retired".equals(b.getState())) {
            return "已下线";
        }
        return "草稿";
    }

    private static String levelCls(DataapiApiBinding b) {
        if ("published".equals(b.getState())) {
            return "prod".equalsIgnoreCase(b.getPublishEnv()) ? "tag-orange" : "tag-green";
        }
        return "tag-gray";
    }

    private static String statusTag(String status) {
        if ("active".equals(status)) {
            return "tag-green";
        }
        if ("pending".equals(status)) {
            return "tag-orange";
        }
        return "tag-gray";
    }

    private static String bare(String ref) {
        if (StrUtil.isBlank(ref)) {
            return "-";
        }
        int i = ref.lastIndexOf('.');
        return i >= 0 ? ref.substring(i + 1) : ref;
    }

    private static String normalizePath(String path) {
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return p;
    }

    private static Long parseLong(String s) {
        if (StrUtil.isBlank(s)) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private QueryWrapper<DataapiApiBinding> baseQw(String ws) {
        return new QueryWrapper<DataapiApiBinding>()
                .eq("ws", ws)
                .eq("delete_flag", NOT_DELETE);
    }

    private DataapiApiBinding requireBinding(String id) {
        DataapiApiBinding b = bindingMapper.selectById(id);
        if (b == null || "DELETED".equals(b.getDeleteFlag())) {
            throw new CommonException("绑定不存在");
        }
        return b;
    }
}
