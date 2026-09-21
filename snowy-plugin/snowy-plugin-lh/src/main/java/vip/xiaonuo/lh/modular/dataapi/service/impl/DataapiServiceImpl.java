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
import vip.xiaonuo.lh.core.engine.ApisixClient;
import vip.xiaonuo.lh.core.engine.SqlrestClient;
import vip.xiaonuo.lh.modular.dataapi.entity.DataapiApiBinding;
import vip.xiaonuo.lh.modular.dataapi.entity.DataapiApiKeyMeta;
import vip.xiaonuo.lh.modular.dataapi.mapper.DataapiApiBindingMapper;
import vip.xiaonuo.lh.modular.dataapi.mapper.DataapiApiKeyMetaMapper;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiBindingParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiIdParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiPageParam;
import vip.xiaonuo.lh.modular.dataapi.param.DataapiTrialParam;
import vip.xiaonuo.lh.modular.dataapi.service.DataapiService;
import vip.xiaonuo.lh.modular.datasource.param.LhDatasourceIdParam;
import vip.xiaonuo.lh.modular.datasource.service.LhDatasourceService;
import vip.xiaonuo.lh.modular.datasource.service.LhDatasourceSqlrestProjector;

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
    private LhDatasourceService datasourceService;

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
        m.put("callsNote", "调用量见 SQLREST overview / APISIX 审计");
        Map<String, Object> counter = sqlrestClient.overviewCounter();
        if (Boolean.TRUE.equals(counter.get("ok")) && counter.get("data") instanceof Map<?, ?> c) {
            m.put("sqlrestTotal", c.get("totalCount"));
            m.put("sqlrestOnline", c.get("publishCount"));
            m.put("sqlrestOpen", c.get("openCount"));
            m.put("sqlrestDatasourceCount", c.get("datasourceCount"));
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
        if (withSqlrest && StrUtil.isNotBlank(b.getSqlrestApiId())) {
            Map<String, Object> sr = sqlrestClient.detail(b.getSqlrestApiId());
            vo.put("sqlrest", sr);
            if (Boolean.TRUE.equals(sr.get("ok")) && sr.get("data") != null) {
                cn.hutool.json.JSONObject data = JSONUtil.parseObj(sr.get("data"));
                vo.put("engine", data.getStr("engine"));
                cn.hutool.json.JSONArray sqlList = data.getJSONArray("sqlList");
                if (sqlList != null && !sqlList.isEmpty()) {
                    vo.put("sql", sqlList.getJSONObject(0).getStr("sqlText"));
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
        if (StrUtil.isBlank(param.getSql()) && StrUtil.isBlank(param.getSqlrestApiId()) && StrUtil.isBlank(param.getId())) {
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
        if (StrUtil.isBlank(sql)) {
            throw new CommonException("构建需要 SQL 或 Groovy 脚本正文（经 SQLREST Manager API 写入）");
        }

        String engine = StrUtil.blankToDefault(param.getEngine(), "SQL").trim().toUpperCase();
        if (!"GROOVY".equals(engine)) {
            engine = "SQL";
        }

        Long srDsId = resolveSqlrestDatasourceId(param, binding);
        List<Map<String, Object>> srParams = sqlrestClient.toSqlrestParams(param.getParams(), binding.getMethod());
        Map<String, Object> body = sqlrestClient.buildSaveBody(
                binding.getName(),
                StrUtil.blankToDefault(param.getDescription(), binding.getName()),
                binding.getMethod(),
                binding.getPublicPath(),
                sql,
                srParams,
                sqlrestId,
                binding.getContentType(),
                srDsId,
                engine);

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
        result.put("ok", !degraded);
        return result;
    }

    @Override
    public Map<String, Object> trial(DataapiTrialParam param) {
        String sql = param.getSql();
        List<Map<String, Object>> portalParams = param.getParams();
        Long dsId = param.getDatasourceId();
        String portalDsId = StrUtil.blankToDefault(param.getPortalDsId(), param.getDsId());
        if (StrUtil.isNotBlank(portalDsId)) {
            Long resolved = sqlrestProjector.resolveSqlrestDatasourceId(portalDsId);
            if (resolved == null) {
                LhDatasourceIdParam idp = new LhDatasourceIdParam();
                idp.setId(portalDsId);
                datasourceService.projectToSqlrest(List.of(idp));
                resolved = sqlrestProjector.resolveSqlrestDatasourceId(portalDsId);
            }
            if (resolved != null) {
                dsId = resolved;
            }
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
                return enrichTrial(sqlrestClient.trial(b.getSqlrestApiId()));
            }
            if (portalParams == null && StrUtil.isNotBlank(b.getParamJson())) {
                portalParams = new ArrayList<>();
                for (Object o : JSONUtil.parseArray(b.getParamJson())) {
                    portalParams.add(JSONUtil.parseObj(o));
                }
            }
        }
        if (StrUtil.isBlank(sql)) {
            throw new CommonException("试跑需要 SQL 或 Groovy 脚本");
        }
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("dataSourceId", dsId);
        req.put("engine", engine);
        req.put("namingStrategy", "CAMEL_CASE");
        req.put("formatMap", List.of());
        req.put("contextList", List.of("GROOVY".equals(engine) ? sql : SqlrestClient.toSqlrestSql(sql)));
        req.put("paramValues", sqlrestClient.toDebugParamValues(portalParams));
        return enrichTrial(sqlrestClient.debug(req));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> publish(DataapiIdParam param) {
        DataapiApiBinding b = requireBinding(param.getId());
        if (StrUtil.isBlank(b.getSqlrestApiId())) {
            throw new CommonException("请先构建 SQLREST 接口（build）");
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

        String routeId = StrUtil.blankToDefault(b.getApisixRouteId(), "lh-dataapi-" + b.getId());
        int qps = b.getQpsLimit() == null ? 100 : b.getQpsLimit();
        int burst = b.getBurstLimit() == null ? qps * 2 : b.getBurstLimit();
        Map<String, Object> apisix = Map.of("ok", true, "skipped", true, "edgeMode", sqlrestClient.edgeMode());
        if (sqlrestClient.useApisixEdge()) {
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
            m.put("message", "edge-mode=gateway：对外入口为 SQLREST Gateway，未同步 APISIX");
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
            skip.put("message", "edge-mode=gateway：无需同步 APISIX；请在 SQLREST Manager 发版/上线");
            return skip;
        }
        String workspace = StrUtil.blankToDefault(ws, WS_DEFAULT);
        List<DataapiApiBinding> list = bindingMapper.selectList(baseQw(workspace).eq("state", "published"));
        int ok = 0;
        int fail = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (DataapiApiBinding b : list) {
            String routeId = StrUtil.blankToDefault(b.getApisixRouteId(), "lh-dataapi-" + b.getId());
            int qps = b.getQpsLimit() == null ? 100 : b.getQpsLimit();
            int burst = b.getBurstLimit() == null ? qps * 2 : b.getBurstLimit();
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
            row.put("app", k.getConsumerName());
            row.put("api", b != null ? b.getPublicPath() : "-");
            row.put("user", k.getApplicant());
            row.put("status", k.getStatus());
            row.put("cls", statusTag(k.getStatus()));
            list.add(row);
        }
        return list;
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
        m.put("counter", sqlrestClient.overviewCounter());
        m.put("trend", sqlrestClient.overviewTrend(7));
        m.put("topPath", sqlrestClient.overviewTopPath(7, 10));
        m.put("assignments", sqlrestClient.listAssignments("", 1, 50));
        m.put("clients", sqlrestClient.listClients());
        m.put("authGroups", sqlrestClient.listAuthGroups());
        m.put("hint", "构建经 SQLREST Manager API（create/debug/publish/deploy）；默认边缘 SQLREST Gateway");
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

    private Long resolveSqlrestDatasourceId(DataapiBindingParam param, DataapiApiBinding binding) {
        String portalDsId = StrUtil.blankToDefault(param.getPortalDsId(), param.getDsId());
        if (StrUtil.isBlank(portalDsId)) {
            portalDsId = binding.getPortalDsId();
        }
        Long srDsId = null;
        if (StrUtil.isNotBlank(portalDsId)) {
            binding.setPortalDsId(portalDsId);
            srDsId = sqlrestProjector.resolveSqlrestDatasourceId(portalDsId);
            if (srDsId == null) {
                LhDatasourceIdParam idp = new LhDatasourceIdParam();
                idp.setId(portalDsId);
                datasourceService.projectToSqlrest(List.of(idp));
                srDsId = sqlrestProjector.resolveSqlrestDatasourceId(portalDsId);
            }
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

    private Map<String, Object> enrichTrial(Map<String, Object> sr) {
        Map<String, Object> m = new LinkedHashMap<>(sr);
        if (Boolean.TRUE.equals(sr.get("ok")) && sr.get("data") instanceof Map<?, ?> data) {
            m.put("sample", data.get("answer"));
            m.put("logs", data.get("logs"));
            m.put("types", data.get("types"));
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
        return b;
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
        m.put("lastError", b.getLastError());
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
