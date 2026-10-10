package vip.xiaonuo.lh.modular.bi.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.engine.SupersetClient;
import vip.xiaonuo.lh.modular.bi.entity.GovBiDashboard;
import vip.xiaonuo.lh.modular.bi.entity.GovBiDataset;
import vip.xiaonuo.lh.modular.bi.mapper.GovBiDashboardMapper;
import vip.xiaonuo.lh.modular.bi.mapper.GovBiDatasetMapper;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.modular.bi.param.GovBiDashboardPinParam;
import vip.xiaonuo.lh.modular.bi.param.GovBiEmbedParam;
import vip.xiaonuo.lh.modular.bi.param.GovBiOpenDatasetParam;
import vip.xiaonuo.lh.modular.bi.param.GovBiPublishMetricParam;
import vip.xiaonuo.lh.modular.bi.service.GovBiService;
import vip.xiaonuo.lh.modular.datasource.entity.LhConsumerBinding;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.enums.LhDatasourceStatusEnum;
import vip.xiaonuo.lh.modular.datasource.mapper.LhDatasourceMapper;
import vip.xiaonuo.lh.modular.datasource.param.LhDatasourceIdParam;
import vip.xiaonuo.lh.modular.datasource.service.LhDatasourceSupersetProjector;
import vip.xiaonuo.lh.modular.metric.entity.GovMetric;
import vip.xiaonuo.lh.modular.metric.mapper.GovMetricMapper;
import vip.xiaonuo.lh.modular.metric.param.GovMetricCompileParam;
import vip.xiaonuo.lh.modular.metric.result.GovMetricVo;
import vip.xiaonuo.lh.modular.metric.service.GovMetricService;
import vip.xiaonuo.lh.modular.metric.support.MetricParamBinder;
import vip.xiaonuo.lh.modular.query.support.CpQueryRowFilterInjector;
import vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GovBiServiceImpl implements GovBiService {

    private static final String NOT_DELETE = "NOT_DELETE";
    /** 未授权数据集 Guest RLS 封堵谓词（Superset 3.x 兼容） */
    private static final String DENY_ALL_CLAUSE = "1 = 0";

    @Resource
    private SupersetClient supersetClient;
    @Resource
    private LhDatasourceSupersetProjector projector;
    @Resource
    private LhDatasourceMapper datasourceMapper;
    @Resource
    private GovBiDatasetMapper biDatasetMapper;
    @Resource
    private GovBiDashboardMapper biDashboardMapper;
    @Resource
    private GovMetricService govMetricService;
    @Resource
    private GovMetricMapper metricMapper;
    @Resource
    private SecAuthGrantService secAuthGrantService;
    @Resource
    private LhProperties lhProperties;

    @Override
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", supersetClient.configured());
        out.put("url", supersetClient.baseUrl());
        out.put("publicUrl", supersetClient.publicBaseUrl());
        out.put("embedEnabled", supersetClient.embedEnabled());
        out.put("oidcEnabled", supersetClient.oidcEnabled());
        out.put("health", supersetClient.health());
        List<Map<String, Object>> proj = projectionStatus();
        long synced = proj.stream().filter(r -> "synced".equalsIgnoreCase(str(r.get("syncState")))).count();
        long stale = proj.stream().filter(r -> "stale".equalsIgnoreCase(str(r.get("syncState")))).count();
        long error = proj.stream().filter(r -> "error".equalsIgnoreCase(str(r.get("syncState")))).count();
        out.put("projectionTotal", proj.size());
        out.put("projectionSynced", synced);
        out.put("projectionStale", stale);
        out.put("projectionError", error);
        Long dsCount = biDatasetMapper.selectCount(new QueryWrapper<GovBiDataset>().lambda()
                .eq(GovBiDataset::getDeleteFlag, NOT_DELETE));
        out.put("metricDatasetCount", dsCount == null ? 0 : dsCount);
        Long dashCount = biDashboardMapper.selectCount(new QueryWrapper<GovBiDashboard>().lambda()
                .eq(GovBiDashboard::getDeleteFlag, NOT_DELETE));
        Long pinnedCount = biDashboardMapper.selectCount(new QueryWrapper<GovBiDashboard>().lambda()
                .eq(GovBiDashboard::getDeleteFlag, NOT_DELETE)
                .eq(GovBiDashboard::getPinned, 1));
        out.put("dashboardCount", dashCount == null ? 0 : dashCount);
        out.put("dashboardPinned", pinnedCount == null ? 0 : pinnedCount);
        boolean enforce = embedEnforceMetricAuth();
        out.put("embedEnforceMetricAuth", enforce);
        out.put("contract", Map.of(
                "datasource", "只投影 Trino query_gateway → Superset Database",
                "metric", "Dataset SQL=compile(trino)，钉 metric_code@version",
                "filters", "filters.dims ≡ params.dims",
                "view", "Guest Token 内嵌（无 AUTH_DB 裸深链）",
                "isolation", enforce
                        ? "门户 sec_auth_grant：指标可读闸门 + row_filter/维筛选 → Guest RLS；未授权 dataset 1=0"
                        : "未强制门户授权隔离（lh.superset.embed-enforce-metric-auth=false）",
                "authoring", "仅 OIDC 同 IdP",
                "dashboard", "Superset dashboard 回写 gov_bi_dashboard；pinned=我的看板"));
        return out;
    }

    @Override
    public List<Map<String, Object>> projectionStatus() {
        List<LhDatasource> trinos = datasourceMapper.selectList(new QueryWrapper<LhDatasource>().lambda()
                .eq(LhDatasource::getType, "trino")
                .like(LhDatasource::getPurposes, "query_gateway")
                .ne(LhDatasource::getStatus, LhDatasourceStatusEnum.REVOKED.getValue()));
        Map<String, LhConsumerBinding> byDs = projector.listBindings().stream()
                .collect(Collectors.toMap(LhConsumerBinding::getDsId, b -> b, (a, b) -> a));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LhDatasource ds : trinos) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dsId", ds.getId());
            row.put("dsCode", ds.getDsCode());
            row.put("name", ds.getName());
            row.put("ws", ds.getWs());
            row.put("status", ds.getStatus());
            row.put("projectable", LhDatasourceSupersetProjector.isProjectable(ds));
            LhConsumerBinding b = byDs.get(ds.getId());
            if (b == null) {
                row.put("projected", false);
                row.put("syncState", "pending");
            } else {
                row.put("projected", "synced".equalsIgnoreCase(b.getSyncState()));
                row.put("syncState", b.getSyncState());
                row.put("lastSyncAt", b.getLastSyncAt());
                row.put("lastError", b.getLastError());
                row.put("extId", b.getExtId());
                if (StrUtil.isNotBlank(b.getProjection())) {
                    Map<String, Object> proj = JSONUtil.parseObj(b.getProjection());
                    row.put("supersetDatabaseId", proj.get("supersetDatabaseId"));
                    row.put("supersetDatabaseName", proj.get("supersetDatabaseName"));
                }
            }
            rows.add(row);
        }
        return rows;
    }

    @Override
    public Map<String, Object> projectDatasources(List<LhDatasourceIdParam> ids) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!supersetClient.configured()) {
            out.put("ok", false);
            out.put("skipped", true);
            out.put("message", "未配置 lh.superset.url");
            return out;
        }
        List<LhDatasource> targets;
        if (ids == null || ids.isEmpty()) {
            targets = datasourceMapper.selectList(new QueryWrapper<LhDatasource>().lambda()
                    .eq(LhDatasource::getType, "trino")
                    .like(LhDatasource::getPurposes, "query_gateway")
                    .ne(LhDatasource::getStatus, LhDatasourceStatusEnum.REVOKED.getValue()));
        } else {
            List<String> idList = ids.stream().map(LhDatasourceIdParam::getId).filter(StrUtil::isNotBlank).toList();
            if (idList.isEmpty()) {
                out.put("ok", true);
                out.put("projected", 0);
                out.put("failed", 0);
                out.put("results", List.of());
                return out;
            }
            targets = datasourceMapper.selectBatchIds(idList);
        }
        int ok = 0;
        int fail = 0;
        int skipped = 0;
        List<Map<String, Object>> results = new ArrayList<>();
        for (LhDatasource ds : targets) {
            Map<String, Object> r = projector.project(ds);
            results.add(r);
            if (Boolean.TRUE.equals(r.get("ok"))) {
                ok++;
            } else if (Boolean.TRUE.equals(r.get("skipped"))) {
                skipped++;
            } else {
                fail++;
            }
        }
        out.put("ok", fail == 0);
        out.put("projected", ok);
        out.put("failed", fail);
        out.put("skipped", skipped);
        out.put("results", results);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> publishMetricDataset(GovBiPublishMetricParam param) {
        if (!supersetClient.configured()) {
            throw new CommonException("未配置 lh.superset.url，无法发布到 Superset");
        }
        String ws = StrUtil.blankToDefault(param.getWs(), "default");
        GovMetric metricHead = findMetric(param.getMetricCode(), ws);
        if (metricHead == null) {
            throw new CommonException("指标不存在: " + param.getMetricCode());
        }
        secAuthGrantService.assertCanEditMetric(metricHead);
        GovMetricVo metric = govMetricService.detail(param.getMetricCode(), ws);
        if (metric == null) {
            throw new CommonException("指标不存在: " + param.getMetricCode());
        }
        String ver = StrUtil.blankToDefault(param.getVer(), metric.getVer());
        if (StrUtil.isBlank(ver)) {
            throw new CommonException("指标无可用版本");
        }

        LhDatasource trino = resolveTrinoDs(param.getDsId(), ws);
        Long databaseId = projector.resolveSupersetDatabaseId(trino.getId());
        if (databaseId == null) {
            Map<String, Object> proj = projector.project(trino);
            if (!Boolean.TRUE.equals(proj.get("ok"))) {
                throw new CommonException("Trino 未投影到 Superset: " + proj.get("message"));
            }
            databaseId = proj.get("supersetDatabaseId") instanceof Number n ? n.longValue() : null;
        }
        if (databaseId == null) {
            throw new CommonException("缺少 Superset Database id");
        }

        GovMetricCompileParam compileParam = new GovMetricCompileParam();
        compileParam.setMetricCode(param.getMetricCode());
        compileParam.setWs(ws);
        compileParam.setDialect("trino");
        compileParam.setPersist(Boolean.TRUE);
        compileParam.setParams(param.getParams() == null ? Map.of() : param.getParams());
        Map<String, Object> compiled = govMetricService.compile(compileParam);
        String sqlText = str(compiled.get("sqlText"));
        if (StrUtil.isBlank(sqlText)) {
            sqlText = str(compiled.get("sql"));
        }
        if (StrUtil.isBlank(sqlText)) {
            throw new CommonException("编译结果无 SQL");
        }

        String datasetName = sanitizeDatasetName(param.getMetricCode(), ver);
        GovBiDataset existing = biDatasetMapper.selectOne(new QueryWrapper<GovBiDataset>().lambda()
                .eq(GovBiDataset::getWs, ws)
                .eq(GovBiDataset::getMetricCode, param.getMetricCode())
                .eq(GovBiDataset::getVer, ver)
                .last("LIMIT 1"));
        Long datasetId = existing == null ? null : existing.getSupersetDatasetId();

        Map<String, Object> ss = supersetClient.upsertVirtualDataset(datasetId, databaseId, datasetName, sqlText);
        if (!Boolean.TRUE.equals(ss.get("ok"))) {
            if (existing != null) {
                existing.setSyncState("error");
                existing.setLastError(StrUtil.maxLength(str(ss.get("message")), 500));
                existing.setLastSyncAt(new Date());
                biDatasetMapper.updateById(existing);
            }
            throw new CommonException("Superset 数据集同步失败: " + ss.get("message"));
        }
        Long newId = ss.get("id") instanceof Number n ? n.longValue() : null;

        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("filters.dims", "≡ params.dims；省略维键=不切片");
        hint.put("dimKeys", metric.getDimKeys());
        hint.put("metricCode", param.getMetricCode());
        hint.put("ver", ver);

        boolean insert = existing == null;
        if (insert) {
            existing = new GovBiDataset();
            existing.setId(IdUtil.getSnowflakeNextIdStr());
            existing.setRevision(1);
            existing.setStatus("active");
            existing.setWs(ws);
            existing.setMetricCode(param.getMetricCode());
            existing.setVer(ver);
            existing.setDeleteFlag(NOT_DELETE);
        }
        existing.setDsId(trino.getId());
        existing.setSupersetDatabaseId(databaseId);
        existing.setSupersetDatasetId(newId);
        existing.setDatasetName(datasetName);
        existing.setSqlText(sqlText);
        existing.setFiltersHint(JSONUtil.toJsonStr(hint));
        existing.setSyncState("synced");
        existing.setLastSyncAt(new Date());
        existing.setLastError(null);
        if (insert) {
            biDatasetMapper.insert(existing);
        } else {
            existing.setRevision(existing.getRevision() == null ? 1 : existing.getRevision() + 1);
            biDatasetMapper.updateById(existing);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", existing.getId());
        out.put("metricCode", param.getMetricCode());
        out.put("ver", ver);
        out.put("datasetName", datasetName);
        out.put("supersetDatasetId", newId);
        out.put("supersetDatabaseId", databaseId);
        out.put("filtersHint", hint);
        out.put("viewHint", "看数请在门户内嵌看板（embedSession）；勿使用 AUTH_DB 裸深链");
        return out;
    }

    @Override
    public List<Map<String, Object>> listDatasets(String ws) {
        QueryWrapper<GovBiDataset> qw = new QueryWrapper<>();
        qw.lambda().eq(GovBiDataset::getDeleteFlag, NOT_DELETE);
        if (StrUtil.isNotBlank(ws)) {
            qw.lambda().eq(GovBiDataset::getWs, ws);
        }
        qw.lambda().orderByDesc(GovBiDataset::getUpdateTime);
        List<GovBiDataset> list = biDatasetMapper.selectList(qw);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GovBiDataset d : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.getId());
            row.put("ws", d.getWs());
            row.put("metricCode", d.getMetricCode());
            row.put("ver", d.getVer());
            row.put("datasetName", d.getDatasetName());
            row.put("supersetDatasetId", d.getSupersetDatasetId());
            row.put("supersetDatabaseId", d.getSupersetDatabaseId());
            row.put("dsId", d.getDsId());
            row.put("syncState", d.getSyncState());
            row.put("lastSyncAt", d.getLastSyncAt());
            row.put("lastError", d.getLastError());
            row.put("filtersHint", d.getFiltersHint());
            rows.add(row);
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> syncDashboards(String ws) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!supersetClient.configured()) {
            out.put("ok", false);
            out.put("skipped", true);
            out.put("message", "未配置 lh.superset.url");
            return out;
        }
        String home = StrUtil.blankToDefault(ws, "default");
        Map<String, Object> remote = supersetClient.listDashboards(100);
        if (!Boolean.TRUE.equals(remote.get("ok"))) {
            out.put("ok", false);
            out.put("message", remote.get("message"));
            out.put("degraded", remote.get("degraded"));
            return out;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = remote.get("result") instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.of();
        out.put("remoteCount", remote.get("count"));
        out.put("remotePages", remote.get("pages"));
        int upserted = 0;
        int revoked = 0;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Map<String, Object> r : rows) {
            Long ssId = r.get("id") instanceof Number n ? n.longValue() : null;
            if (ssId == null) {
                continue;
            }
            seen.add(ssId);
            GovBiDashboard row = biDashboardMapper.selectOne(new QueryWrapper<GovBiDashboard>().lambda()
                    .eq(GovBiDashboard::getSupersetDashboardId, ssId)
                    .last("LIMIT 1"));
            boolean insert = row == null;
            if (insert) {
                row = new GovBiDashboard();
                row.setId(IdUtil.getSnowflakeNextIdStr());
                row.setRevision(1);
                row.setStatus("active");
                row.setWs(home);
                row.setPinned(0);
                row.setDeleteFlag(NOT_DELETE);
                row.setSupersetDashboardId(ssId);
            }
            row.setDashboardTitle(str(r.get("dashboard_title")));
            row.setDashboardSlug(str(r.get("slug")));
            String path = str(r.get("url"));
            if (StrUtil.isBlank(path)) {
                path = "/superset/dashboard/" + ssId + "/";
            }
            row.setUrlPath(path);
            Boolean pub = r.get("published") instanceof Boolean b ? b : Boolean.TRUE;
            row.setPublished(Boolean.TRUE.equals(pub) ? 1 : 0);
            row.setSyncState("synced");
            row.setLastSyncAt(new Date());
            row.setLastError(null);
            if (insert) {
                biDashboardMapper.insert(row);
            } else {
                row.setRevision(row.getRevision() == null ? 1 : row.getRevision() + 1);
                biDashboardMapper.updateById(row);
            }
            upserted++;
        }
        // 远端消失 → revoked（保留置顶痕迹）
        List<GovBiDashboard> local = biDashboardMapper.selectList(new QueryWrapper<GovBiDashboard>().lambda()
                .eq(GovBiDashboard::getDeleteFlag, NOT_DELETE)
                .eq(GovBiDashboard::getWs, home));
        for (GovBiDashboard d : local) {
            if (d.getSupersetDashboardId() != null && !seen.contains(d.getSupersetDashboardId())
                    && !"revoked".equalsIgnoreCase(d.getSyncState())) {
                d.setSyncState("revoked");
                d.setLastSyncAt(new Date());
                d.setRevision(d.getRevision() == null ? 1 : d.getRevision() + 1);
                biDashboardMapper.updateById(d);
                revoked++;
            }
        }
        out.put("ok", true);
        out.put("upserted", upserted);
        out.put("revoked", revoked);
        out.put("remoteCount", rows.size());
        return out;
    }

    @Override
    public List<Map<String, Object>> listDashboards(String ws, Boolean pinnedOnly) {
        QueryWrapper<GovBiDashboard> qw = new QueryWrapper<>();
        qw.lambda().eq(GovBiDashboard::getDeleteFlag, NOT_DELETE);
        if (StrUtil.isNotBlank(ws)) {
            qw.lambda().eq(GovBiDashboard::getWs, ws);
        }
        if (Boolean.TRUE.equals(pinnedOnly)) {
            qw.lambda().eq(GovBiDashboard::getPinned, 1);
        }
        qw.lambda().orderByDesc(GovBiDashboard::getPinned)
                .orderByDesc(GovBiDashboard::getUpdateTime);
        List<GovBiDashboard> list = biDashboardMapper.selectList(qw);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GovBiDashboard d : list) {
            Map<String, Object> row = toDashboardRow(d);
            rows.add(row);
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> pinDashboard(GovBiDashboardPinParam param) {
        GovBiDashboard d = biDashboardMapper.selectById(param.getId());
        if (d == null || !NOT_DELETE.equals(d.getDeleteFlag())) {
            throw new CommonException("看板不存在: " + param.getId());
        }
        if (StrUtil.isNotBlank(param.getWs()) && !param.getWs().equals(d.getWs())) {
            d.setWs(param.getWs());
        }
        if (param.getPinned() != null) {
            d.setPinned(Boolean.TRUE.equals(param.getPinned()) ? 1 : 0);
        }
        if (param.getMetricCodes() != null) {
            d.setMetricCodes(StrUtil.maxLength(param.getMetricCodes(), 500));
        }
        if (embedEnforceMetricAuth()
                && (Boolean.TRUE.equals(param.getPinned()) || (d.getPinned() != null && d.getPinned() == 1))
                && parseMetricCodes(d.getMetricCodes()).isEmpty()) {
            throw new CommonException("置顶看板须填写关联 metricCodes，否则无法按门户授权隔离看数");
        }
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("filters.dims", "≡ params.dims；省略维键=不切片");
        hint.put("metricCodes", d.getMetricCodes());
        d.setFiltersHint(JSONUtil.toJsonStr(hint));
        d.setRevision(d.getRevision() == null ? 1 : d.getRevision() + 1);
        biDashboardMapper.updateById(d);
        return toDashboardRow(d);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> openDatasetWithDims(GovBiOpenDatasetParam param) {
        if (!supersetClient.configured()) {
            throw new CommonException("未配置 lh.superset.url");
        }
        String ws = StrUtil.blankToDefault(param.getWs(), "default");
        GovBiDataset ds = null;
        if (StrUtil.isNotBlank(param.getId())) {
            ds = biDatasetMapper.selectById(param.getId());
        } else if (StrUtil.isNotBlank(param.getMetricCode())) {
            ds = biDatasetMapper.selectOne(new QueryWrapper<GovBiDataset>().lambda()
                    .eq(GovBiDataset::getWs, ws)
                    .eq(GovBiDataset::getMetricCode, param.getMetricCode())
                    .eq(GovBiDataset::getSyncState, "synced")
                    .orderByDesc(GovBiDataset::getUpdateTime)
                    .last("LIMIT 1"));
        }
        if (ds == null || !NOT_DELETE.equals(ds.getDeleteFlag())) {
            throw new CommonException("未找到已发布的指标数据集，请先发布到 BI");
        }
        GovMetric metricHead = findMetric(ds.getMetricCode(), ws);
        if (metricHead == null) {
            throw new CommonException("指标不存在: " + ds.getMetricCode());
        }
        secAuthGrantService.assertCanReadMetric(metricHead);
        Map<String, Object> params = resolveOpenParams(param);
        GovMetricCompileParam compileParam = new GovMetricCompileParam();
        compileParam.setMetricCode(ds.getMetricCode());
        compileParam.setWs(ws);
        compileParam.setDialect("trino");
        compileParam.setPersist(Boolean.FALSE);
        compileParam.setParams(params);
        Map<String, Object> compiled = govMetricService.compile(compileParam);
        String sqlText = str(compiled.get("sqlText"));
        if (StrUtil.isBlank(sqlText)) {
            sqlText = str(compiled.get("sql"));
        }
        if (StrUtil.isBlank(sqlText)) {
            throw new CommonException("编译结果无 SQL");
        }
        Long databaseId = ds.getSupersetDatabaseId();
        if (databaseId == null && StrUtil.isNotBlank(ds.getDsId())) {
            databaseId = projector.resolveSupersetDatabaseId(ds.getDsId());
        }
        if (databaseId == null) {
            throw new CommonException("缺少 Superset Database id，请先投影 Trino");
        }
        Map<String, Object> ss = supersetClient.upsertVirtualDataset(
                ds.getSupersetDatasetId(), databaseId, ds.getDatasetName(), sqlText);
        if (!Boolean.TRUE.equals(ss.get("ok"))) {
            throw new CommonException("刷新数据集 SQL 失败: " + ss.get("message"));
        }
        Long datasetId = ss.get("id") instanceof Number n ? n.longValue() : ds.getSupersetDatasetId();
        ds.setSqlText(sqlText);
        ds.setSupersetDatasetId(datasetId);
        Map<String, Object> hint = mergeFiltersHint(ds.getFiltersHint(), params, ds.getMetricCode(), ds.getVer(), compiled);
        ds.setFiltersHint(JSONUtil.toJsonStr(hint));
        ds.setSyncState("synced");
        ds.setLastSyncAt(new Date());
        ds.setLastError(null);
        ds.setRevision(ds.getRevision() == null ? 1 : ds.getRevision() + 1);
        biDatasetMapper.updateById(ds);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", ds.getId());
        out.put("metricCode", ds.getMetricCode());
        out.put("ver", ds.getVer());
        out.put("supersetDatasetId", datasetId);
        out.put("params", params);
        out.put("filters", Map.of("dims", params.get("dims") instanceof Map<?, ?> ? params.get("dims") : Map.of()));
        out.put("sqlRefreshed", true);
        out.put("viewHint", "SQL 已按维刷新；请在门户内嵌关联看板查看（embedSession）");
        // 若有钉了该指标的看板，提示前端直接嵌入
        List<GovBiDashboard> linked = biDashboardMapper.selectList(new QueryWrapper<GovBiDashboard>().lambda()
                .eq(GovBiDashboard::getDeleteFlag, NOT_DELETE)
                .eq(GovBiDashboard::getWs, ws)
                .like(GovBiDashboard::getMetricCodes, ds.getMetricCode())
                .ne(GovBiDashboard::getSyncState, "revoked")
                .orderByDesc(GovBiDashboard::getPinned)
                .last("LIMIT 5"));
        if (!linked.isEmpty()) {
            out.put("suggestedDashboardId", linked.get(0).getId());
            out.put("suggestedSupersetDashboardId", linked.get(0).getSupersetDashboardId());
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> embedSession(GovBiEmbedParam param) {
        if (!supersetClient.embedEnabled()) {
            throw new CommonException("未启用内嵌看数（检查 lh.superset.url / embed-enabled，及远端 EMBEDDED_SUPERSET）");
        }
        GovBiDashboard dash = resolveDashboard(param);
        if ("revoked".equalsIgnoreCase(dash.getSyncState())) {
            throw new CommonException("看板已在 Superset 侧吊销，请重新同步");
        }
        Long ssId = dash.getSupersetDashboardId();
        if (ssId == null) {
            throw new CommonException("缺少 Superset dashboard id");
        }
        List<String> domains = resolveAllowedDomains(param.getOrigin());
        Map<String, Object> emb = supersetClient.ensureDashboardEmbedded(ssId, domains);
        if (!Boolean.TRUE.equals(emb.get("ok"))) {
            throw new CommonException("启用 Embedded 失败: " + emb.get("message")
                    + "（请确认 Superset FEATURE_FLAGS.EMBEDDED_SUPERSET 与 GUEST_TOKEN_JWT_SECRET）");
        }
        String uuid = str(emb.get("uuid"));
        dash.setEmbeddedUuid(uuid);
        dash.setRevision(dash.getRevision() == null ? 1 : dash.getRevision() + 1);
        biDashboardMapper.updateById(dash);

        SaBaseLoginUser login = LhLoginUsers.requireUser();
        String uname = StrUtil.blankToDefault(login.getAccount(), "u_" + login.getId());
        String name = StrUtil.blankToDefault(login.getName(), uname);
        String first = name;
        String last = "";
        if (name.contains(" ")) {
            int i = name.indexOf(' ');
            first = name.substring(0, i);
            last = name.substring(i + 1);
        }
        Map<String, Object> dims = extractDims(param.getFilters(), param.getParams());
        Map<String, String> urlParams = dimsToUrlParams(dims);
        EmbedIsolation iso = resolveEmbedIsolation(dash, dims, ssId);
        Map<String, Object> gt = supersetClient.createGuestToken(
                uname, first, last, uuid, iso.rls, iso.datasetAllowlist);
        if (!Boolean.TRUE.equals(gt.get("ok"))) {
            throw new CommonException("签发 Guest Token 失败: " + gt.get("message"));
        }
        int expSec = 300;
        LhProperties.Superset sc = lhProperties.getSuperset();
        if (sc != null && sc.getGuestTokenExpSec() > 0) {
            expSec = sc.getGuestTokenExpSec();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("guestToken", gt.get("token"));
        out.put("embeddedId", uuid);
        out.put("supersetDomain", supersetClient.publicBaseUrl());
        out.put("dashboardId", dash.getId());
        out.put("supersetDashboardId", ssId);
        out.put("title", dash.getDashboardTitle());
        out.put("allowedDomains", domains);
        out.put("expiresInSec", expSec);
        out.put("filters", Map.of("dims", dims));
        out.put("urlParams", urlParams);
        out.put("rls", iso.rls);
        out.put("rlsApplied", !iso.rls.isEmpty());
        out.put("embedEnforceMetricAuth", iso.enforced);
        out.put("allowedMetricCodes", iso.allowedMetricCodes);
        out.put("deniedMetricCodes", iso.deniedMetricCodes);
        out.put("datasetAllowlist", iso.datasetAllowlist);
        out.put("datasetsAllowlistApplied", gt.get("datasetsAllowlistApplied"));
        if (gt.get("datasetsAllowlistSkipped") != null) {
            out.put("datasetsAllowlistSkipped", gt.get("datasetsAllowlistSkipped"));
        }
        out.put("username", uname);
        return out;
    }

    @Override
    public Map<String, Object> authoringEntry(String kind, String id) {
        return supersetClient.authoringEntry(kind, id);
    }

    private GovBiDashboard resolveDashboard(GovBiEmbedParam param) {
        if (param == null) {
            throw new CommonException("缺少看板参数");
        }
        if (StrUtil.isNotBlank(param.getId())) {
            GovBiDashboard d = biDashboardMapper.selectById(param.getId());
            if (d == null || !NOT_DELETE.equals(d.getDeleteFlag())) {
                throw new CommonException("看板不存在: " + param.getId());
            }
            return d;
        }
        if (param.getSupersetDashboardId() != null) {
            GovBiDashboard d = biDashboardMapper.selectOne(new QueryWrapper<GovBiDashboard>().lambda()
                    .eq(GovBiDashboard::getSupersetDashboardId, param.getSupersetDashboardId())
                    .last("LIMIT 1"));
            if (d != null) {
                return d;
            }
            // 尚未回写：临时行（仅内存返回前先落库骨架）
            d = new GovBiDashboard();
            d.setId(IdUtil.getSnowflakeNextIdStr());
            d.setRevision(1);
            d.setStatus("active");
            d.setWs(StrUtil.blankToDefault(param.getWs(), "default"));
            d.setSupersetDashboardId(param.getSupersetDashboardId());
            d.setDashboardTitle("Dashboard #" + param.getSupersetDashboardId());
            d.setPinned(0);
            d.setPublished(1);
            d.setSyncState("synced");
            d.setDeleteFlag(NOT_DELETE);
            d.setLastSyncAt(new Date());
            biDashboardMapper.insert(d);
            return d;
        }
        throw new CommonException("请提供 id 或 supersetDashboardId");
    }

    private List<String> resolveAllowedDomains(String origin) {
        List<String> domains = new ArrayList<>();
        LhProperties.Superset c = lhProperties.getSuperset();
        String cfg = c == null ? "" : StrUtil.blankToDefault(c.getEmbedAllowedDomains(), "");
        for (String part : cfg.split(",")) {
            String p = StrUtil.trim(part);
            if (StrUtil.isNotBlank(p)) {
                domains.add(StrUtil.removeSuffix(p, "/"));
            }
        }
        if (StrUtil.isNotBlank(origin)) {
            String o = StrUtil.removeSuffix(origin.trim(), "/");
            if (!domains.contains(o)) {
                domains.add(o);
            }
        }
        return domains;
    }

    private Map<String, Object> toDashboardRow(GovBiDashboard d) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", d.getId());
        row.put("ws", d.getWs());
        row.put("supersetDashboardId", d.getSupersetDashboardId());
        row.put("title", d.getDashboardTitle());
        row.put("slug", d.getDashboardSlug());
        row.put("urlPath", d.getUrlPath());
        row.put("embeddedUuid", d.getEmbeddedUuid());
        row.put("published", d.getPublished());
        row.put("pinned", d.getPinned() != null && d.getPinned() == 1);
        row.put("metricCodes", d.getMetricCodes());
        row.put("filtersHint", d.getFiltersHint());
        row.put("syncState", d.getSyncState());
        row.put("lastSyncAt", d.getLastSyncAt());
        row.put("lastError", d.getLastError());
        row.put("embedReady", StrUtil.isNotBlank(d.getEmbeddedUuid()));
        EmbedPreview preview = previewEmbedAccess(d);
        row.put("canEmbed", preview.canEmbed);
        row.put("embedBlockReason", preview.blockReason);
        row.put("allowedMetricCodes", preview.allowedMetricCodes);
        row.put("deniedMetricCodes", preview.deniedMetricCodes);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mergeFiltersHint(String existingJson, Map<String, Object> params,
                                                        String metricCode, String ver,
                                                        Map<String, Object> compiled) {
        Map<String, Object> hint = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(existingJson)) {
            try {
                Object parsed = JSONUtil.parse(existingJson);
                if (parsed instanceof Map<?, ?> m) {
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        if (e.getKey() != null) {
                            hint.put(String.valueOf(e.getKey()), e.getValue());
                        }
                    }
                }
            } catch (Exception ignored) {
                /* keep fresh */
            }
        }
        hint.put("filters.dims", "≡ params.dims");
        hint.put("lastParams", params);
        hint.put("metricCode", metricCode);
        hint.put("ver", ver);
        Object dimKeys = compiled != null ? compiled.get("grainKeys") : null;
        if (dimKeys == null && compiled != null) {
            dimKeys = compiled.get("dimKeys");
        }
        if (dimKeys instanceof List<?> list && !list.isEmpty()) {
            hint.put("dimKeys", list);
        } else if (!hint.containsKey("dimKeys")) {
            // 保留旧 dimKeys；无则不写
        }
        return hint;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractDims(Map<String, Object> filters, Map<String, Object> params) {
        if (params != null && params.get("dims") instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        if (filters != null && filters.get("dims") instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        if (filters != null && !filters.isEmpty() && !filters.containsKey("dims")) {
            return new LinkedHashMap<>(filters);
        }
        return Map.of();
    }

    private static Map<String, String> dimsToUrlParams(Map<String, Object> dims) {
        Map<String, String> out = new LinkedHashMap<>();
        if (dims == null || dims.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, Object> e : dims.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            List<String> vals = MetricParamBinder.normalizeDimValues(e.getValue());
            if (vals.isEmpty()) {
                continue;
            }
            String v = String.join(",", vals);
            // Embedded SDK urlParams：原生筛选若同名可消费；前缀 lh_ 供看板自定义
            out.put(e.getKey(), v);
            out.put("lh_dim_" + e.getKey(), v);
        }
        return out;
    }

    private boolean embedEnforceMetricAuth() {
        LhProperties.Superset sc = lhProperties.getSuperset();
        return sc == null || sc.isEmbedEnforceMetricAuth();
    }

    private EmbedPreview previewEmbedAccess(GovBiDashboard dash) {
        if (!embedEnforceMetricAuth()) {
            return EmbedPreview.ok(List.of(), List.of());
        }
        List<String> codes = parseMetricCodes(dash == null ? null : dash.getMetricCodes());
        if (codes.isEmpty()) {
            return EmbedPreview.blocked("看板未关联 metricCodes，无法按门户授权隔离看数");
        }
        String ws = StrUtil.blankToDefault(dash.getWs(), "default");
        List<String> allowed = new ArrayList<>();
        List<String> denied = new ArrayList<>();
        for (String code : codes) {
            GovMetric m = findMetric(code, ws);
            if (m == null || !secAuthGrantService.canReadMetric(m.getId())) {
                denied.add(code);
            } else {
                allowed.add(code);
            }
        }
        if (allowed.isEmpty()) {
            return EmbedPreview.blocked("关联指标均无查询权，请先申请 SELECT", allowed, denied);
        }
        return EmbedPreview.ok(allowed, denied);
    }

    /**
     * 门户授权隔离：可读闸门 + grant.row_filter + 请求维 → Guest RLS；
     * 未授权关联数据集 {@link #DENY_ALL_CLAUSE}；可选 datasets allowlist。
     */
    private EmbedIsolation resolveEmbedIsolation(GovBiDashboard dash, Map<String, Object> dims, Long ssDashboardId) {
        boolean enforce = embedEnforceMetricAuth();
        List<String> codes = parseMetricCodes(dash.getMetricCodes());
        if (enforce && codes.isEmpty()) {
            throw new CommonException("看板未关联 metricCodes，拒绝签发 Guest Token（须绑定可读指标以隔离看数）");
        }
        String ws = StrUtil.blankToDefault(dash.getWs(), "default");
        Map<String, Object> dimMap = dims == null ? Map.of() : dims;
        List<Map<String, Object>> rls = new ArrayList<>();
        List<String> allowedCodes = new ArrayList<>();
        List<String> deniedCodes = new ArrayList<>();
        Set<Long> allowDatasets = new LinkedHashSet<>();
        Set<Long> knownDatasets = new LinkedHashSet<>();

        for (String code : codes) {
            GovMetric metric = findMetric(code, ws);
            GovBiDataset ds = findBiDataset(code, ws);
            Long datasetId = ds == null ? null : ds.getSupersetDatasetId();
            if (datasetId != null) {
                knownDatasets.add(datasetId);
            }
            if (metric == null) {
                deniedCodes.add(code);
                if (datasetId != null) {
                    rls.add(rlsRow(datasetId, DENY_ALL_CLAUSE));
                }
                continue;
            }
            if (enforce && !secAuthGrantService.canReadMetric(metric.getId())) {
                deniedCodes.add(code);
                if (datasetId != null) {
                    rls.add(rlsRow(datasetId, DENY_ALL_CLAUSE));
                }
                continue;
            }
            if (datasetId == null) {
                deniedCodes.add(code + "(未投影)");
                continue;
            }
            allowedCodes.add(code);
            allowDatasets.add(datasetId);
            List<String> clauses = new ArrayList<>();
            if (enforce) {
                for (String pred : secAuthGrantService.resolveMetricRowFilterClauses(metric.getId())) {
                    if (StrUtil.isNotBlank(pred)) {
                        clauses.add("(" + pred.trim() + ")");
                    }
                }
            }
            clauses.addAll(dimClausesForDataset(ds, dimMap));
            if (!clauses.isEmpty()) {
                rls.add(rlsRow(datasetId, String.join(" AND ", clauses)));
            }
        }

        if (enforce && allowedCodes.isEmpty()) {
            throw new CommonException("无权查看该看板关联指标："
                    + (deniedCodes.isEmpty() ? "无可用指标" : String.join(",", deniedCodes))
                    + "（请申请 SELECT 或联系 Owner）");
        }

        // 远端看板图表上、但不在关联指标白名单内的数据集 → 封堵
        if (enforce && ssDashboardId != null) {
            for (Long remoteId : supersetClient.listDashboardDatasetIds(ssDashboardId)) {
                if (remoteId == null || remoteId <= 0 || knownDatasets.contains(remoteId)
                        || allowDatasets.contains(remoteId)) {
                    continue;
                }
                knownDatasets.add(remoteId);
                rls.add(rlsRow(remoteId, DENY_ALL_CLAUSE));
            }
        }

        List<Long> allowlist = enforce ? new ArrayList<>(allowDatasets) : null;
        return new EmbedIsolation(enforce, rls, allowlist, allowedCodes, deniedCodes);
    }

    private List<String> dimClausesForDataset(GovBiDataset ds, Map<String, Object> dims) {
        List<String> clauses = new ArrayList<>();
        if (ds == null || dims == null || dims.isEmpty()) {
            return clauses;
        }
        List<String> dimKeys = dimKeysFromHint(ds.getFiltersHint());
        for (Map.Entry<String, Object> e : dims.entrySet()) {
            String col = e.getKey();
            if (StrUtil.isBlank(col)) {
                continue;
            }
            if (!dimKeys.isEmpty() && !dimKeys.contains(col)) {
                continue;
            }
            try {
                String clause = MetricParamBinder.dimEqualsOrInClause(col, e.getValue());
                if (StrUtil.isNotBlank(clause) && CpQueryRowFilterInjector.isSafePredicate(clause)) {
                    clauses.add("(" + clause + ")");
                }
            } catch (IllegalArgumentException ignored) {
                /* skip bad dim */
            }
        }
        return clauses;
    }

    private static Map<String, Object> rlsRow(long datasetId, String clause) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dataset", datasetId);
        row.put("clause", clause);
        return row;
    }

    private static List<String> parseMetricCodes(String raw) {
        List<String> out = new ArrayList<>();
        if (StrUtil.isBlank(raw)) {
            return out;
        }
        for (String part : raw.split("[,;\\s]+")) {
            String code = StrUtil.trim(part);
            if (StrUtil.isNotBlank(code)) {
                out.add(code.toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }

    private GovMetric findMetric(String code, String ws) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        QueryWrapper<GovMetric> qw = new QueryWrapper<>();
        qw.lambda().eq(GovMetric::getDeleteFlag, NOT_DELETE)
                .eq(GovMetric::getMetricCode, code.trim().toUpperCase(Locale.ROOT));
        if (StrUtil.isNotBlank(ws)) {
            qw.lambda().eq(GovMetric::getWs, ws.trim());
        }
        return metricMapper.selectOne(qw.last("LIMIT 1"));
    }

    private GovBiDataset findBiDataset(String code, String ws) {
        return biDatasetMapper.selectOne(new QueryWrapper<GovBiDataset>().lambda()
                .eq(GovBiDataset::getDeleteFlag, NOT_DELETE)
                .eq(GovBiDataset::getWs, ws)
                .eq(GovBiDataset::getMetricCode, code)
                .ne(GovBiDataset::getSyncState, "revoked")
                .orderByDesc(GovBiDataset::getUpdateTime)
                .last("LIMIT 1"));
    }

    private static final class EmbedIsolation {
        final boolean enforced;
        final List<Map<String, Object>> rls;
        final List<Long> datasetAllowlist;
        final List<String> allowedMetricCodes;
        final List<String> deniedMetricCodes;

        EmbedIsolation(boolean enforced, List<Map<String, Object>> rls, List<Long> datasetAllowlist,
                       List<String> allowedMetricCodes, List<String> deniedMetricCodes) {
            this.enforced = enforced;
            this.rls = rls;
            this.datasetAllowlist = datasetAllowlist;
            this.allowedMetricCodes = allowedMetricCodes;
            this.deniedMetricCodes = deniedMetricCodes;
        }
    }

    private static final class EmbedPreview {
        final boolean canEmbed;
        final String blockReason;
        final List<String> allowedMetricCodes;
        final List<String> deniedMetricCodes;

        private EmbedPreview(boolean canEmbed, String blockReason,
                             List<String> allowedMetricCodes, List<String> deniedMetricCodes) {
            this.canEmbed = canEmbed;
            this.blockReason = blockReason;
            this.allowedMetricCodes = allowedMetricCodes;
            this.deniedMetricCodes = deniedMetricCodes;
        }

        static EmbedPreview ok(List<String> allowed, List<String> denied) {
            return new EmbedPreview(true, null, allowed, denied);
        }

        static EmbedPreview blocked(String reason) {
            return new EmbedPreview(false, reason, List.of(), List.of());
        }

        static EmbedPreview blocked(String reason, List<String> allowed, List<String> denied) {
            return new EmbedPreview(false, reason, allowed, denied);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> dimKeysFromHint(String filtersHint) {
        if (StrUtil.isBlank(filtersHint)) {
            return List.of();
        }
        try {
            Object parsed = JSONUtil.parse(filtersHint);
            if (parsed instanceof Map<?, ?> m && m.get("dimKeys") instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object o : list) {
                    if (o != null && StrUtil.isNotBlank(String.valueOf(o))) {
                        out.add(String.valueOf(o));
                    }
                }
                return out;
            }
        } catch (Exception ignored) {
            /* empty */
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolveOpenParams(GovBiOpenDatasetParam param) {
        if (param.getParams() != null && !param.getParams().isEmpty()) {
            return new LinkedHashMap<>(param.getParams());
        }
        Map<String, Object> filters = param.getFilters();
        if (filters == null) {
            return Map.of();
        }
        if (filters.containsKey("dims")) {
            Map<String, Object> p = new LinkedHashMap<>();
            Object dims = filters.get("dims");
            if (dims instanceof Map<?, ?> m) {
                p.put("dims", m);
            }
            return p;
        }
        // 允许直接传 { region: "xm" } 当作 dims
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("dims", filters);
        return p;
    }

    private LhDatasource resolveTrinoDs(String dsId, String ws) {
        if (StrUtil.isNotBlank(dsId)) {
            LhDatasource ds = datasourceMapper.selectById(dsId);
            if (ds == null) {
                throw new CommonException("数据源不存在: " + dsId);
            }
            if (!LhDatasourceSupersetProjector.isProjectable(ds)) {
                throw new CommonException("数据源不是 Trino query_gateway，禁止投影到 Superset");
            }
            return ds;
        }
        List<LhDatasource> list = datasourceMapper.selectList(new QueryWrapper<LhDatasource>().lambda()
                .eq(LhDatasource::getType, "trino")
                .like(LhDatasource::getPurposes, "query_gateway")
                .eq(LhDatasource::getWs, ws)
                .ne(LhDatasource::getStatus, LhDatasourceStatusEnum.REVOKED.getValue())
                .orderByAsc(LhDatasource::getCreateTime));
        if (list.isEmpty()) {
            list = datasourceMapper.selectList(new QueryWrapper<LhDatasource>().lambda()
                    .eq(LhDatasource::getType, "trino")
                    .like(LhDatasource::getPurposes, "query_gateway")
                    .ne(LhDatasource::getStatus, LhDatasourceStatusEnum.REVOKED.getValue())
                    .orderByAsc(LhDatasource::getCreateTime));
        }
        if (list.isEmpty()) {
            throw new CommonException("无可用 Trino query_gateway 数据源，请先在数据源中心登记");
        }
        // 优先已 synced
        for (LhDatasource ds : list) {
            if (projector.resolveSupersetDatabaseId(ds.getId()) != null) {
                return ds;
            }
        }
        return list.get(0);
    }

    private static String sanitizeDatasetName(String metricCode, String ver) {
        String raw = "metric_" + StrUtil.blankToDefault(metricCode, "x") + "_" + StrUtil.blankToDefault(ver, "v1");
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        if (s.length() > 100) {
            s = s.substring(0, 100);
        }
        return s;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
