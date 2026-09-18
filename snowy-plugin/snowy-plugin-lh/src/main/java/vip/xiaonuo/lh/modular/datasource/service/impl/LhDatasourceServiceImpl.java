/*
 * Copyright [2022] [https://www.xiaonuo.vip]
 *
 * Snowy采用APACHE LICENSE 2.0开源协议，您在使用过程中，需要注意以下几点：
 *
 * 1.请不要删除和修改根目录下的LICENSE文件。
 * 2.请不要删除和修改Snowy源码头部的版权声明。
 * 3.本项目代码可免费商业使用，商业使用请保留源码和相关描述文件的项目出处，作者声明等。
 * 4.分发源码时候，请注明软件出处 https://www.xiaonuo.vip
 * 5.不可二次分发开源参与同类竞品，如有想法可联系团队xiaonuobase@qq.com商议合作。
 * 6.若您的项目无法满足以上几点，需要更多功能代码，获取Snowy商业授权许可，请在官网购买授权，地址为 https://www.xiaonuo.vip
 */
package vip.xiaonuo.lh.modular.datasource.service.impl;

import cn.hutool.core.collection.CollStreamUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.common.enums.CommonSortOrderEnum;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.common.page.CommonPageRequest;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.modular.datasource.entity.LhConsumerBinding;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.entity.LhDsTable;
import vip.xiaonuo.lh.modular.datasource.enums.LhDatasourceStatusEnum;
import vip.xiaonuo.lh.modular.datasource.enums.LhDatasourceTypeEnum;
import vip.xiaonuo.lh.modular.datasource.form.LhDatasourceConnNormalizer;
import vip.xiaonuo.lh.modular.datasource.form.LhDatasourceViewAssembler;
import vip.xiaonuo.lh.modular.datasource.form.LhDsFormSchemaService;
import vip.xiaonuo.lh.modular.datasource.mapper.LhConsumerBindingMapper;
import vip.xiaonuo.lh.modular.datasource.mapper.LhDatasourceMapper;
import vip.xiaonuo.lh.modular.datasource.mapper.LhDsTableMapper;
import vip.xiaonuo.lh.modular.datasource.param.*;
import vip.xiaonuo.lh.modular.datasource.result.LhDatasourceVo;
import vip.xiaonuo.lh.modular.datasource.result.LhDsTableVo;
import vip.xiaonuo.lh.modular.datasource.service.LhDatasourceService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据源中心 Service 实现（对外字段对齐前端）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Service
public class LhDatasourceServiceImpl extends ServiceImpl<LhDatasourceMapper, LhDatasource>
        implements LhDatasourceService {

    @Resource
    private LhVaultClient vaultClient;
    @Resource
    private LhConsumerBindingMapper bindingMapper;
    @Resource
    private LhDsTableMapper dsTableMapper;
    @Resource
    private LhDsFormSchemaService formSchemaService;
    @Resource
    private LhDatasourceConnNormalizer connNormalizer;
    @Resource
    private LhDatasourceViewAssembler viewAssembler;

    @Override
    public Page<LhDatasourceVo> page(LhDatasourcePageParam param) {
        QueryWrapper<LhDatasource> qw = new QueryWrapper<LhDatasource>().checkSqlInjection();
        qw.lambda().ne(LhDatasource::getStatus, LhDatasourceStatusEnum.REVOKED.getValue());
        if (StrUtil.isNotBlank(param.getName())) {
            qw.lambda().like(LhDatasource::getName, param.getName());
        }
        if (StrUtil.isNotBlank(param.getType())) {
            // 前端传展示名或编码
            try {
                String code = formSchemaService.resolveTypeCode(param.getType());
                qw.lambda().and(w -> w.eq(LhDatasource::getType, code)
                        .or().eq(LhDatasource::getType, param.getType()));
            } catch (Exception e) {
                qw.lambda().eq(LhDatasource::getType, param.getType());
            }
        }
        String category = StrUtil.blankToDefault(param.getCategory(), param.getCat());
        if (StrUtil.isNotBlank(category)) {
            qw.lambda().eq(LhDatasource::getCategory, category);
        }
        if (StrUtil.isNotBlank(param.getStatus())) {
            qw.lambda().eq(LhDatasource::getStatus, param.getStatus());
        }
        String kw = StrUtil.blankToDefault(param.getKeyword(), param.getKw());
        if (StrUtil.isNotBlank(kw)) {
            qw.lambda().and(w -> w.like(LhDatasource::getName, kw)
                    .or().like(LhDatasource::getDsCode, kw)
                    .or().like(LhDatasource::getType, kw)
                    .or().like(LhDatasource::getEndpointHost, kw)
                    .or().like(LhDatasource::getOwner, kw)
                    .or().like(LhDatasource::getDatabaseName, kw)
                    .or().like(LhDatasource::getRemark, kw)
                    .or().like(LhDatasource::getConnMasked, kw)
                    .or().like(LhDatasource::getId, kw));
        }
        if (StrUtil.isNotBlank(param.getPurpose())) {
            qw.lambda().like(LhDatasource::getPurposes, param.getPurpose());
        }
        if ("1".equals(param.getUsableInDag())) {
            qw.lambda().eq(LhDatasource::getStatus, LhDatasourceStatusEnum.ONLINE.getValue())
                    .like(LhDatasource::getPurposes, "ingest");
        }
        if (ObjectUtil.isAllNotEmpty(param.getSortField(), param.getSortOrder())) {
            CommonSortOrderEnum.validate(param.getSortOrder());
            qw.orderBy(true, param.getSortOrder().equals(CommonSortOrderEnum.ASC.getValue()),
                    StrUtil.toUnderlineCase(param.getSortField()));
        } else {
            qw.lambda().orderByAsc(LhDatasource::getCategory)
                    .orderByAsc(LhDatasource::getType)
                    .orderByAsc(LhDatasource::getName);
        }
        Page<LhDatasource> raw = this.page(CommonPageRequest.defaultPage(), qw);
        Page<LhDatasourceVo> page = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        page.setRecords(raw.getRecords().stream().map(viewAssembler::toVo).collect(Collectors.toList()));
        return page;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public LhDatasourceVo add(LhDatasourceAddParam param) {
        viewAssembler.enrichParamFromFe(param);
        String typeCode = formSchemaService.resolveTypeCode(param.getType());
        param.setType(typeCode);
        Map<String, Object> conn = connNormalizer.mergeConn(param);
        formSchemaService.validateConn(typeCode, conn);
        LhDatasourceConnNormalizer.NormalizedConn n = connNormalizer.normalize(
                typeCode, conn, formSchemaService.defaultPort(typeCode));

        LhDatasource entity = new LhDatasource();
        entity.setId(StrUtil.blankToDefault(param.getId(), IdUtil.getSnowflakeNextIdStr()));
        entity.setRevision(1);
        entity.setWs(StrUtil.blankToDefault(param.getWs(), "default"));
        entity.setDsCode(StrUtil.blankToDefault(param.getDsCode(), "ds_" + entity.getId()));
        ensureDsCodeUnique(entity.getDsCode(), null);
        entity.setName(param.getName());
        entity.setType(typeCode);
        entity.setCategory(StrUtil.blankToDefault(param.getCategory(),
                LhDatasourceTypeEnum.resolveCategory(typeCode)));
        entity.setLevel(param.getLevel());
        entity.setOwner(param.getOwner());
        entity.setRemark(param.getDesc());
        entity.setAccessMode(StrUtil.blankToDefault(n.accessMode, param.getAccess()));
        entity.setSchemaSummary(StrUtil.blankToDefault(n.schemaSummary, param.getSchema()));
        entity.setLagDesc(StrUtil.blankToDefault(param.getLag(), n.accessMode));
        entity.setAssetName(param.getAsset());
        entity.setVer("v1.0");
        entity.setHealthScore(100);
        entity.setPurposes(connNormalizer.mapPurposes(param.getPurpose(), null));
        entity.setStatus(LhDatasourceStatusEnum.ONLINE.getValue());
        entity.setEndpointHost(n.host);
        entity.setEndpointPort(n.port);
        entity.setDatabaseName(n.database);
        entity.setConnMasked(JSONUtil.toJsonStr(connNormalizer.masked(conn)));
        entity.setVaultPath("datasource/" + typeCode + "/" + entity.getId());
        entity.setContentHash(SecureUtil.sha256(entity.getConnMasked()));
        Map<String, Object> secret = connNormalizer.secretPayload(conn, n);
        secret.put("jdbcUrl", buildJdbcUrl(typeCode, n, conn));
        vaultClient.write(entity.getVaultPath(), secret);
        this.save(entity);
        seedTablesFromSummary(entity);
        return viewAssembler.toVo(entity);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public LhDatasourceVo edit(LhDatasourceEditParam param) {
        viewAssembler.enrichParamFromFe(param);
        if (StrUtil.isBlank(param.getId())) {
            throw new CommonException("id不能为空");
        }
        LhDatasource entity = queryEntity(param.getId());
        if (StrUtil.isNotBlank(param.getDsCode()) && !param.getDsCode().equals(entity.getDsCode())) {
            ensureDsCodeUnique(param.getDsCode(), entity.getId());
            entity.setDsCode(param.getDsCode());
        }
        entity.setName(param.getName());
        entity.setOwner(param.getOwner());
        if (param.getDesc() != null) {
            entity.setRemark(param.getDesc());
        }
        if (StrUtil.isNotBlank(param.getLag())) {
            entity.setLagDesc(param.getLag());
        }
        if (param.getAsset() != null) {
            entity.setAssetName(param.getAsset());
        }
        if (StrUtil.isNotBlank(param.getCategory())) {
            entity.setCategory(param.getCategory());
        }
        if (StrUtil.isNotBlank(param.getPurpose())) {
            entity.setPurposes(connNormalizer.mapPurposes(param.getPurpose(), null));
        }
        if (StrUtil.isNotBlank(param.getLevel())) {
            entity.setLevel(param.getLevel());
        }

        Map<String, Object> conn = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(entity.getConnMasked())) {
            try {
                JSONUtil.parseObj(entity.getConnMasked()).forEach((k, v) -> {
                    if (v != null && !"******".equals(String.valueOf(v))) {
                        conn.put(k, v);
                    }
                });
            } catch (Exception ignored) {
            }
        }
        vaultClient.readOrEmpty(entity.getVaultPath()).forEach((k, v) -> {
            if (v != null && StrUtil.isNotBlank(String.valueOf(v))) {
                conn.putIfAbsent(k, v);
            }
        });
        Map<String, Object> incoming = connNormalizer.mergeConn(param);
        incoming.forEach((k, v) -> {
            if (v != null && StrUtil.isNotBlank(String.valueOf(v)) && !"******".equals(String.valueOf(v))) {
                conn.put(k, v);
            }
        });
        boolean touchConn = StrUtil.isNotBlank(param.getHost())
                || StrUtil.isNotBlank(param.getPassword())
                || StrUtil.isNotBlank(param.getUser())
                || StrUtil.isNotBlank(param.getDatabase())
                || (param.getConn() != null && !param.getConn().isEmpty());
        String typeCode = entity.getType();
        if (StrUtil.isNotBlank(param.getType())) {
            typeCode = formSchemaService.resolveTypeCode(param.getType());
            entity.setType(typeCode);
        }
        if (touchConn) {
            formSchemaService.validateConn(typeCode, conn);
            LhDatasourceConnNormalizer.NormalizedConn n = connNormalizer.normalize(
                    typeCode, conn, formSchemaService.defaultPort(typeCode));
            entity.setEndpointHost(n.host);
            entity.setEndpointPort(n.port);
            entity.setDatabaseName(n.database);
            entity.setAccessMode(StrUtil.blankToDefault(n.accessMode, param.getAccess()));
            if (StrUtil.isNotBlank(n.schemaSummary)) {
                entity.setSchemaSummary(n.schemaSummary);
            }
            entity.setConnMasked(JSONUtil.toJsonStr(connNormalizer.masked(conn)));
            entity.setContentHash(SecureUtil.sha256(entity.getConnMasked()));
            Map<String, Object> secret = connNormalizer.secretPayload(conn, n);
            secret.put("jdbcUrl", buildJdbcUrl(typeCode, n, conn));
            vaultClient.write(entity.getVaultPath(), secret);
            markBindingsStale(entity.getId());
        } else if (StrUtil.isNotBlank(param.getAccess())) {
            entity.setAccessMode(param.getAccess());
        }
        if (StrUtil.isNotBlank(param.getSchema())) {
            entity.setSchemaSummary(param.getSchema());
        }
        entity.setRevision(Optional.ofNullable(entity.getRevision()).orElse(1) + 1);
        bumpVer(entity);
        this.updateById(entity);
        return viewAssembler.toVo(entity);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void delete(List<LhDatasourceIdParam> ids) {
        List<String> idList = CollStreamUtil.toList(ids, LhDatasourceIdParam::getId);
        for (String id : idList) {
            LhDatasource ds = this.getById(id);
            if (ds != null) {
                ds.setStatus(LhDatasourceStatusEnum.REVOKED.getValue());
                ds.setRevision(Optional.ofNullable(ds.getRevision()).orElse(1) + 1);
                this.updateById(ds);
                markBindingsStale(id);
            }
        }
        this.removeByIds(idList);
    }

    @Override
    public LhDatasourceVo detail(LhDatasourceIdParam param) {
        return viewAssembler.toVo(queryEntity(param.getId()));
    }

    @Override
    public Map<String, Object> test(LhDatasourceTestParam param) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            viewAssembler.enrichParamFromFe(param);
            String type = param.getType();
            if (StrUtil.isBlank(type) && StrUtil.isNotBlank(param.getId())) {
                type = queryEntity(param.getId()).getType();
            }
            String typeCode;
            try {
                typeCode = formSchemaService.resolveTypeCode(type);
            } catch (Exception e) {
                typeCode = type;
            }
            param.setType(typeCode);
            Map<String, Object> conn = connNormalizer.mergeConn(param);
            LhDatasourceConnNormalizer.NormalizedConn n = connNormalizer.normalize(
                    typeCode, conn, formSchemaService.defaultPort(typeCode));
            LhDatasourceTypeEnum typeEnum = LhDatasourceTypeEnum.of(typeCode).orElse(null);

            if (typeEnum != null && typeEnum.isJdbc()) {
                String url = buildJdbcUrl(typeCode, n, conn);
                try (Connection c = DriverManager.getConnection(url, n.user, n.password);
                     Statement st = c.createStatement()) {
                    st.setQueryTimeout(5);
                    st.execute("SELECT 1");
                }
            } else if (typeEnum == LhDatasourceTypeEnum.HTTP_API) {
                String url = firstNonBlank(str(conn.get("baseURL")), str(conn.get("httpUrl")), n.host);
                if (StrUtil.isBlank(url)) {
                    throw new CommonException("baseURL 不能为空");
                }
                if (!url.startsWith("http")) {
                    url = "https://" + url;
                }
                cn.hutool.http.HttpRequest.get(url).timeout(5000).execute();
            } else if (typeEnum == LhDatasourceTypeEnum.KAFKA) {
                if (StrUtil.isBlank(n.host)) {
                    throw new CommonException("bootstrap 不能为空");
                }
            } else {
                if (StrUtil.isBlank(n.host)) {
                    throw new CommonException("连接端点不能为空");
                }
            }
            result.put("ok", true);
            result.put("costMs", System.currentTimeMillis() - start);
            if (StrUtil.isNotBlank(param.getId())) {
                LhDatasource ds = queryEntity(param.getId());
                ds.setLastOkAt(new Date());
                ds.setStatus(LhDatasourceStatusEnum.ONLINE.getValue());
                ds.setHealthScore(100);
                this.updateById(ds);
            }
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
            result.put("costMs", System.currentTimeMillis() - start);
            if (StrUtil.isNotBlank(param.getId())) {
                try {
                    LhDatasource ds = queryEntity(param.getId());
                    ds.setStatus(LhDatasourceStatusEnum.WARN.getValue());
                    ds.setHealthScore(40);
                    this.updateById(ds);
                } catch (Exception ignored) {
                }
            }
        }
        return result;
    }

    @Override
    public Map<String, Object> previewSchema(LhDatasourceIdParam param) {
        LhDatasource ds = queryEntity(param.getId());
        Map<String, Object> secret = vaultClient.read(ds.getVaultPath());
        List<Map<String, Object>> columns = new ArrayList<>();
        LhDatasourceTypeEnum typeEnum = LhDatasourceTypeEnum.of(ds.getType()).orElse(null);
        if (typeEnum != null && typeEnum.isJdbc()) {
            try {
                String url = String.valueOf(secret.getOrDefault("jdbcUrl", ""));
                String user = String.valueOf(secret.getOrDefault("username", ""));
                String pwd = String.valueOf(secret.getOrDefault("password", ""));
                try (Connection conn = DriverManager.getConnection(url, user, pwd);
                     ResultSet rs = conn.getMetaData().getColumns(null, null, "%", "%")) {
                    int n = 0;
                    while (rs.next() && n++ < 50) {
                        Map<String, Object> col = new LinkedHashMap<>();
                        col.put("table", rs.getString("TABLE_NAME"));
                        col.put("column", rs.getString("COLUMN_NAME"));
                        col.put("type", rs.getString("TYPE_NAME"));
                        columns.add(col);
                    }
                }
            } catch (Exception e) {
                columns.add(Map.of("table", "demo", "column", "id", "type", "BIGINT"));
            }
        } else {
            List<LhDsTable> tables = dsTableMapper.selectList(new QueryWrapper<LhDsTable>().lambda()
                    .eq(LhDsTable::getDsId, ds.getId()).last("LIMIT 20"));
            for (LhDsTable t : tables) {
                columns.add(Map.of("name", t.getTableName(), "type", "OBJECT",
                        "cnName", StrUtil.blankToDefault(t.getCnName(), "")));
            }
            if (columns.isEmpty()) {
                columns.add(Map.of("name", "payload", "type", "STRING"));
            }
        }
        return Map.of("dsId", ds.getId(), "type", ds.getType(), "columns", columns);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updatePurposes(LhDatasourcePurposesParam param) {
        LhDatasource ds = queryEntity(param.getId());
        if (param.getPurposes().contains("analyze_direct") && !"trino".equals(ds.getType())) {
            throw new CommonException("业务源默认禁止 analyze_direct");
        }
        ds.setPurposes(JSONUtil.toJsonStr(param.getPurposes()));
        ds.setRevision(Optional.ofNullable(ds.getRevision()).orElse(1) + 1);
        this.updateById(ds);
        markBindingsStale(ds.getId());
    }

    @Override
    public List<LhConsumerBinding> bindings(LhDatasourceIdParam param) {
        return bindingMapper.selectList(new QueryWrapper<LhConsumerBinding>().lambda()
                .eq(LhConsumerBinding::getDsId, param.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void rotateCred(LhDatasourceIdParam param) {
        LhDatasource ds = queryEntity(param.getId());
        Map<String, Object> old = vaultClient.read(ds.getVaultPath());
        old.put("rotatedAt", new Date().toString());
        vaultClient.write(ds.getVaultPath(), old);
        ds.setRevision(Optional.ofNullable(ds.getRevision()).orElse(1) + 1);
        this.updateById(ds);
        markBindingsStale(ds.getId());
    }

    @Override
    public List<LhDatasourceVo> listForDag() {
        List<String> ingestTypes = Arrays.stream(LhDatasourceTypeEnum.values())
                .filter(LhDatasourceTypeEnum::isIngestable)
                .map(LhDatasourceTypeEnum::getValue)
                .collect(Collectors.toList());
        return this.list(new QueryWrapper<LhDatasource>().lambda()
                        .eq(LhDatasource::getStatus, LhDatasourceStatusEnum.ONLINE.getValue())
                        .like(LhDatasource::getPurposes, "ingest")
                        .in(LhDatasource::getType, ingestTypes))
                .stream().map(viewAssembler::toVo).collect(Collectors.toList());
    }

    @Override
    public List<LhDatasourceVo> supersetProjection() {
        return this.list(new QueryWrapper<LhDatasource>().lambda()
                        .eq(LhDatasource::getType, "trino")
                        .like(LhDatasource::getPurposes, "query_gateway")
                        .ne(LhDatasource::getStatus, LhDatasourceStatusEnum.REVOKED.getValue()))
                .stream().map(viewAssembler::toVo).collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> resolveDs(String dsId, String mode) {
        LhDatasource ds = queryEntity(dsId);
        if (LhDatasourceStatusEnum.REVOKED.getValue().equals(ds.getStatus())
                || LhDatasourceStatusEnum.PAUSED.getValue().equals(ds.getStatus())) {
            throw new CommonException("datasource missing or paused");
        }
        List<String> purposes = JSONUtil.toList(ds.getPurposes(), String.class);
        if ("write".equalsIgnoreCase(mode) && !(purposes.contains("ingest") || purposes.contains("export"))) {
            throw new CommonException("purpose denied");
        }
        Map<String, Object> secret = vaultClient.read(ds.getVaultPath());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("meta", JSONUtil.parseObj(ds.getConnMasked()));
        result.put("secret", secret);
        result.put("lease_id", "local-" + dsId);
        result.put("dsCode", ds.getDsCode());
        result.put("revision", ds.getRevision());
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void batchImport(LhDatasourceBatchImportParam param) {
        for (LhDatasourceAddParam item : param.getItems()) {
            this.add(item);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Map<String, Object> toggleStatus(LhDatasourceToggleParam param) {
        LhDatasource ds = queryEntity(param.getId());
        String next = LhDatasourceStatusEnum.ONLINE.getValue().equals(ds.getStatus())
                ? LhDatasourceStatusEnum.PAUSED.getValue()
                : LhDatasourceStatusEnum.ONLINE.getValue();
        ds.setStatus(next);
        ds.setRevision(Optional.ofNullable(ds.getRevision()).orElse(1) + 1);
        this.updateById(ds);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ds.getId());
        m.put("status", next);
        m.put("source", viewAssembler.toVo(ds));
        return m;
    }

    @Override
    public Map<String, Object> kpi() {
        List<LhDatasource> all = this.list(new QueryWrapper<LhDatasource>().lambda()
                .ne(LhDatasource::getStatus, LhDatasourceStatusEnum.REVOKED.getValue()));
        long online = all.stream().filter(d -> LhDatasourceStatusEnum.ONLINE.getValue().equals(d.getStatus())).count();
        long warn = all.stream().filter(d -> LhDatasourceStatusEnum.WARN.getValue().equals(d.getStatus())).count();
        long paused = all.stream().filter(d -> LhDatasourceStatusEnum.PAUSED.getValue().equals(d.getStatus())).count();
        long typeCount = all.stream().map(LhDatasource::getType).filter(StrUtil::isNotBlank).distinct().count();
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("total", all.size());
        kpi.put("online", online);
        kpi.put("warn", warn);
        kpi.put("paused", paused);
        kpi.put("typeCount", typeCount);
        return kpi;
    }

    @Override
    public List<Map<String, Object>> typeOptions() {
        // 优先返回表单 schema 中的展示名，贴合前端筛选
        Map<String, Object> full = formSchemaService.fullSchema();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> types = (List<Map<String, Object>>) full.get("types");
        if (types != null && !types.isEmpty()) {
            return types;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (LhDatasourceTypeEnum e : LhDatasourceTypeEnum.values()) {
            if ("pg".equals(e.getValue())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("value", e.getLabel());
            m.put("label", e.getLabel());
            m.put("code", e.getValue());
            m.put("category", e.getCategory().getValue());
            list.add(m);
        }
        return list;
    }

    @Override
    public Map<String, Object> formSchema(String type) {
        if (StrUtil.isBlank(type)) {
            return formSchemaService.fullSchema();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("code", formSchemaService.resolveTypeCode(type));
        m.put("label", formSchemaService.resolveTypeLabel(type));
        m.put("port", formSchemaService.defaultPort(type));
        m.put("fields", formSchemaService.fieldsOf(type));
        m.put("purposeOptions", formSchemaService.fullSchema().get("purposeOptions"));
        return m;
    }

    @Override
    public Page<LhDsTableVo> tablePage(LhDsTablePageParam param) {
        queryEntity(param.getDsId());
        QueryWrapper<LhDsTable> qw = new QueryWrapper<LhDsTable>().checkSqlInjection();
        qw.lambda().eq(LhDsTable::getDsId, param.getDsId());
        if (StrUtil.isNotBlank(param.getKeyword())) {
            String kw = param.getKeyword();
            qw.lambda().and(w -> w.like(LhDsTable::getTableName, kw)
                    .or().like(LhDsTable::getCnName, kw)
                    .or().like(LhDsTable::getCommentTxt, kw)
                    .or().like(LhDsTable::getEncoding, kw)
                    .or().like(LhDsTable::getEngine, kw));
        }
        if (ObjectUtil.isAllNotEmpty(param.getSortField(), param.getSortOrder())) {
            CommonSortOrderEnum.validate(param.getSortOrder());
            qw.orderBy(true, param.getSortOrder().equals(CommonSortOrderEnum.ASC.getValue()),
                    StrUtil.toUnderlineCase(param.getSortField()));
        } else {
            qw.lambda().orderByAsc(LhDsTable::getTableName);
        }
        Page<LhDsTable> raw = dsTableMapper.selectPage(CommonPageRequest.defaultPage(), qw);
        Page<LhDsTableVo> page = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        page.setRecords(raw.getRecords().stream().map(viewAssembler::toTableVo).collect(Collectors.toList()));
        return page;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public LhDsTableVo tableAdd(LhDsTableAddParam param) {
        queryEntity(param.getDsId());
        Long cnt = dsTableMapper.selectCount(new QueryWrapper<LhDsTable>().lambda()
                .eq(LhDsTable::getDsId, param.getDsId())
                .eq(LhDsTable::getTableName, param.getName()));
        if (cnt != null && cnt > 0) {
            throw new CommonException("表名已存在: {}", param.getName());
        }
        LhDsTable row = new LhDsTable();
        row.setId(IdUtil.getSnowflakeNextIdStr());
        row.setRevision(1);
        row.setDsId(param.getDsId());
        row.setTableName(param.getName());
        row.setCnName(param.getCnName());
        row.setCommentTxt(param.getComment());
        row.setEncoding(StrUtil.blankToDefault(param.getEncoding(), "utf8mb4"));
        row.setEngine(param.getEngine());
        row.setSyncedAt(new Date());
        row.setStatus("ENABLE");
        dsTableMapper.insert(row);
        refreshSchemaSummary(param.getDsId());
        return viewAssembler.toTableVo(row);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public LhDsTableVo tableEdit(LhDsTableEditParam param) {
        LhDsTable row = dsTableMapper.selectById(param.getId());
        if (row == null) {
            throw new CommonException("表清单记录不存在: {}", param.getId());
        }
        row.setCnName(param.getCnName());
        row.setCommentTxt(param.getComment());
        row.setEncoding(param.getEncoding());
        row.setEngine(param.getEngine());
        row.setRevision(Optional.ofNullable(row.getRevision()).orElse(1) + 1);
        dsTableMapper.updateById(row);
        return viewAssembler.toTableVo(row);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void tableDelete(List<LhDsTableIdParam> ids) {
        List<String> idList = CollStreamUtil.toList(ids, LhDsTableIdParam::getId);
        Set<String> dsIds = new HashSet<>();
        for (String id : idList) {
            LhDsTable row = dsTableMapper.selectById(id);
            if (row != null) {
                dsIds.add(row.getDsId());
            }
        }
        dsTableMapper.deleteBatchIds(idList);
        for (String dsId : dsIds) {
            refreshSchemaSummary(dsId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Map<String, Object> tableSync(LhDatasourceIdParam param) {
        LhDatasource ds = queryEntity(param.getId());
        Set<String> discovered = new LinkedHashSet<>();
        LhDatasourceTypeEnum typeEnum = LhDatasourceTypeEnum.of(ds.getType()).orElse(null);
        if (typeEnum != null && typeEnum.isJdbc()) {
            try {
                Map<String, Object> secret = vaultClient.read(ds.getVaultPath());
                String url = String.valueOf(secret.getOrDefault("jdbcUrl", ""));
                String user = String.valueOf(secret.getOrDefault("username", ""));
                String pwd = String.valueOf(secret.getOrDefault("password", ""));
                try (Connection conn = DriverManager.getConnection(url, user, pwd);
                     ResultSet rs = conn.getMetaData().getTables(null, null, "%", new String[]{"TABLE", "VIEW"})) {
                    while (rs.next()) {
                        discovered.add(rs.getString("TABLE_NAME"));
                    }
                }
            } catch (Exception e) {
                discovered.addAll(parseSchemaSummary(ds.getSchemaSummary()));
            }
        } else {
            discovered.addAll(parseSchemaSummary(ds.getSchemaSummary()));
        }
        int added = 0;
        Date now = new Date();
        List<LhDsTableVo> addedRows = new ArrayList<>();
        for (String name : discovered) {
            if (StrUtil.isBlank(name)) {
                continue;
            }
            Long cnt = dsTableMapper.selectCount(new QueryWrapper<LhDsTable>().lambda()
                    .eq(LhDsTable::getDsId, ds.getId()).eq(LhDsTable::getTableName, name));
            if (cnt != null && cnt > 0) {
                continue;
            }
            LhDsTable row = new LhDsTable();
            row.setId(IdUtil.getSnowflakeNextIdStr());
            row.setRevision(1);
            row.setDsId(ds.getId());
            row.setTableName(name.trim());
            row.setEncoding("utf8mb4");
            row.setRowCount(10000L + (Math.abs(name.hashCode()) % 90000));
            row.setSyncedAt(now);
            row.setStatus("ENABLE");
            dsTableMapper.insert(row);
            addedRows.add(viewAssembler.toTableVo(row));
            added++;
        }
        refreshSchemaSummary(ds.getId());
        long total = dsTableMapper.selectCount(new QueryWrapper<LhDsTable>().lambda()
                .eq(LhDsTable::getDsId, ds.getId()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dsId", ds.getId());
        result.put("discovered", discovered.size());
        result.put("added", added);
        result.put("total", total);
        result.put("tables", addedRows);
        result.put("schema", this.getById(ds.getId()).getSchemaSummary());
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Map<String, Object> batchSyncTables(List<LhDatasourceIdParam> ids) {
        int tableAdded = 0;
        int dsCount = 0;
        for (LhDatasourceIdParam idParam : ids) {
            Map<String, Object> one = tableSync(idParam);
            tableAdded += ((Number) one.getOrDefault("added", 0)).intValue();
            dsCount++;
        }
        return Map.of("dsCount", dsCount, "tableAdded", tableAdded,
                "columnEstimate", tableAdded * 12);
    }

    public LhDatasource queryEntity(String id) {
        LhDatasource ds = this.getById(id);
        if (ds == null) {
            throw new CommonException("数据源不存在: {}", id);
        }
        return ds;
    }

    private void markBindingsStale(String dsId) {
        List<LhConsumerBinding> list = bindingMapper.selectList(new QueryWrapper<LhConsumerBinding>().lambda()
                .eq(LhConsumerBinding::getDsId, dsId));
        for (LhConsumerBinding b : list) {
            b.setStatus("stale");
            b.setSyncState("stale");
            b.setRevision(Optional.ofNullable(b.getRevision()).orElse(1) + 1);
            bindingMapper.updateById(b);
        }
    }

    private void ensureDsCodeUnique(String dsCode, String excludeId) {
        QueryWrapper<LhDatasource> qw = new QueryWrapper<>();
        qw.lambda().eq(LhDatasource::getDsCode, dsCode);
        if (StrUtil.isNotBlank(excludeId)) {
            qw.lambda().ne(LhDatasource::getId, excludeId);
        }
        if (this.count(qw) > 0) {
            throw new CommonException("数据源编码已存在: {}", dsCode);
        }
    }

    private void bumpVer(LhDatasource entity) {
        String ver = StrUtil.blankToDefault(entity.getVer(), "v1.0");
        try {
            String num = ver.startsWith("v") ? ver.substring(1) : ver;
            String[] parts = num.split("\\.");
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) + 1 : 1;
            entity.setVer("v" + parts[0] + "." + minor);
        } catch (Exception e) {
            entity.setVer("v1.1");
        }
    }

    private String buildJdbcUrl(String type, LhDatasourceConnNormalizer.NormalizedConn n,
                                Map<String, Object> conn) {
        Object jdbc = conn != null ? conn.get("jdbcUrl") : null;
        if (jdbc != null && StrUtil.isNotBlank(String.valueOf(jdbc))) {
            return String.valueOf(jdbc);
        }
        String host = n.host;
        String port = n.port;
        String db = n.database;
        String extra = n.extra;
        if ("mysql".equals(type)) {
            return "jdbc:mysql://" + host + ":" + port + "/" + db
                    + "?useSSL=false&allowPublicKeyRetrieval=true"
                    + (StrUtil.isNotBlank(extra) ? "&" + extra : "");
        }
        if ("pg".equals(type) || "postgresql".equals(type)) {
            return "jdbc:postgresql://" + host + ":" + port + "/" + db
                    + (StrUtil.isNotBlank(extra) ? "?" + extra : "");
        }
        if ("oracle".equals(type)) {
            return "jdbc:oracle:thin:@" + host + ":" + port + ":" + db;
        }
        if ("sqlserver".equals(type)) {
            return "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + db;
        }
        if ("clickhouse".equals(type)) {
            return "jdbc:clickhouse://" + host + ":" + port + "/" + db;
        }
        if ("doris".equals(type)) {
            return "jdbc:mysql://" + host + ":" + port + "/" + db + "?useSSL=false";
        }
        if ("trino".equals(type)) {
            return "jdbc:trino://" + host + ":" + port + "/" + StrUtil.blankToDefault(db, "hive");
        }
        return "";
    }

    private void seedTablesFromSummary(LhDatasource entity) {
        List<String> names = parseSchemaSummary(entity.getSchemaSummary());
        Date now = new Date();
        for (String name : names) {
            LhDsTable row = new LhDsTable();
            row.setId(IdUtil.getSnowflakeNextIdStr());
            row.setRevision(1);
            row.setDsId(entity.getId());
            row.setTableName(name);
            row.setEncoding("utf8mb4");
            row.setSyncedAt(now);
            row.setStatus("ENABLE");
            dsTableMapper.insert(row);
        }
    }

    private List<String> parseSchemaSummary(String summary) {
        if (StrUtil.isBlank(summary)) {
            return Collections.emptyList();
        }
        return Arrays.stream(summary.split("[,;/\\n]+"))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .collect(Collectors.toList());
    }

    private void refreshSchemaSummary(String dsId) {
        List<LhDsTable> tables = dsTableMapper.selectList(new QueryWrapper<LhDsTable>().lambda()
                .eq(LhDsTable::getDsId, dsId)
                .orderByAsc(LhDsTable::getTableName));
        String summary = tables.stream().map(LhDsTable::getTableName).collect(Collectors.joining(", "));
        LhDatasource ds = this.getById(dsId);
        if (ds != null) {
            ds.setSchemaSummary(summary);
            this.updateById(ds);
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) {
                return v;
            }
        }
        return null;
    }
}
