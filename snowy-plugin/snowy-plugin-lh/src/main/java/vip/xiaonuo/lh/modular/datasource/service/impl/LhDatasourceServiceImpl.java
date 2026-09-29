/*
 * Copyright [2022] [https://www.xiaonuo.vip]
 *
 * Snowy??APACHE LICENSE 2.0??????????????????????
 *
 * 1.?????????????LICENSE???
 * 2.????????Snowy??????????
 * 3.?????????????????????????????????????????
 * 4.?????????????? https://www.xiaonuo.vip
 * 5.????????????????????????xiaonuobase@qq.com?????
 * 6.?????????????????????????Snowy??????????????????? https://www.xiaonuo.vip
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.common.enums.CommonSortOrderEnum;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.common.page.CommonPageRequest;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.core.vault.LhVaultClient;
import vip.xiaonuo.lh.core.vault.LhVaultDynamicRotate;
import vip.xiaonuo.lh.modular.datasource.discover.LhInventoryDiscoverer;
import vip.xiaonuo.lh.modular.datasource.discover.LhInventoryDiscoverResult;
import vip.xiaonuo.lh.modular.datasource.discover.LhInventoryObjectKinds;
import vip.xiaonuo.lh.modular.datasource.discover.LhManualSummaryInventoryDiscoverer;
import vip.xiaonuo.lh.modular.datasource.discover.LhRemoteInventoryItem;
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
import vip.xiaonuo.lh.modular.datasource.result.LhMetaColumnVo;
import vip.xiaonuo.lh.modular.datasource.result.LhMetaObjectVo;
import vip.xiaonuo.lh.modular.datasource.support.LhJdbcMetaBrowser;
import vip.xiaonuo.lh.modular.datasource.service.LhDatasourceGravitinoProjector;
import vip.xiaonuo.lh.modular.datasource.service.LhDatasourceSqlrestProjector;
import vip.xiaonuo.lh.modular.datasource.service.LhDatasourcePostRegisterBridge;
import vip.xiaonuo.lh.modular.datasource.service.LhDatasourceService;
import vip.xiaonuo.lh.modular.datasource.service.LhPortalInventoryOmBridge;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ????? Service ????????????
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Slf4j
@Service
public class LhDatasourceServiceImpl extends ServiceImpl<LhDatasourceMapper, LhDatasource>
        implements LhDatasourceService {

    @Resource
    private LhVaultClient vaultClient;
    @Resource
    private LhVaultDynamicRotate vaultDynamicRotate;
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
    @Resource
    private LhDatasourcePostRegisterBridge postRegisterBridge;
    @Resource
    private LhDatasourceGravitinoProjector gravitinoProjector;
    @Resource
    private LhDatasourceSqlrestProjector sqlrestProjector;
    @Resource
    private LhPortalInventoryOmBridge portalInventoryOmBridge;
    @Resource
    private vip.xiaonuo.lh.modular.catalog.support.GovAssetSourceReconcile govAssetSourceReconcile;
    @Resource
    private vip.xiaonuo.lh.modular.datasource.support.LhDatasourceLinkedAssetFiller linkedAssetFiller;
    @Resource
    private LhJdbcMetaBrowser jdbcMetaBrowser;
    /** ? @Order?Hive/Kafka/ES/Redis/RMQ/MinIO ????????? */
    @Resource
    private List<LhInventoryDiscoverer> inventoryDiscoverers;
    @Resource
    private vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService secAuthGrantService;
    @Resource
    private vip.xiaonuo.lh.core.user.LhUserNameResolver userNameResolver;
    @Resource
    private vip.xiaonuo.lh.modular.datasource.support.LhSqlrestBindingEnricher sqlrestBindingEnricher;
    @Resource
    private vip.xiaonuo.lh.modular.datasource.support.ApiBuildTableAccess apiBuildTableAccess;

    @Override
    public Page<LhDatasourceVo> page(LhDatasourcePageParam param) {
        QueryWrapper<LhDatasource> qw = new QueryWrapper<LhDatasource>().checkSqlInjection();
        qw.lambda().ne(LhDatasource::getStatus, LhDatasourceStatusEnum.REVOKED.getValue());
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(param.getWs());
        if (StrUtil.isNotBlank(workspace)) {
            qw.lambda().eq(LhDatasource::getWs, workspace);
        }
        if (StrUtil.isNotBlank(param.getName())) {
            qw.lambda().like(LhDatasource::getName, param.getName());
        }
        if (StrUtil.isNotBlank(param.getType())) {
            // ?????????
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
        if ("1".equals(param.getUsableInDag()) || "true".equalsIgnoreCase(param.getUsableInDag())) {
            // ?????? DAG?ingest????? export????
            qw.lambda().eq(LhDatasource::getStatus, LhDatasourceStatusEnum.ONLINE.getValue())
                    .and(w -> w.like(LhDatasource::getPurposes, "ingest")
                            .or().like(LhDatasource::getPurposes, "export"));
        }
        if (ObjectUtil.isAllNotEmpty(param.getSortField(), param.getSortOrder())) {
            CommonSortOrderEnum.validate(param.getSortOrder());
            qw.orderBy(true, param.getSortOrder().equals(CommonSortOrderEnum.ASC.getValue()),
                    StrUtil.toUnderlineCase(param.getSortField()));
        } else {
            qw.lambda().orderByDesc(LhDatasource::getCreateTime)
                    .orderByDesc(LhDatasource::getId);
        }
        Page<LhDatasource> raw = this.page(CommonPageRequest.defaultPage(), qw);
        Page<LhDatasourceVo> page = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<LhDatasourceVo> vos = raw.getRecords().stream().map(viewAssembler::toVo).collect(Collectors.toList());
        // 非超管：只返回 canUse（Owner / EDIT / MANAGE）的源
        if (!LhLoginUsers.isSuperAdmin()) {
            vos = vos.stream()
                    .filter(v -> v != null && StrUtil.isNotBlank(v.getId())
                            && secAuthGrantService.canUseDatasource(v.getId()))
                    .collect(Collectors.toList());
            page.setTotal(vos.size());
        }
        linkedAssetFiller.fill(vos);
        userNameResolver.fillDatasources(vos);
        sqlrestBindingEnricher.fill(vos);
        page.setRecords(vos);
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
        String userId = LhLoginUsers.requireUserId();
        entity.setOwner(StrUtil.blankToDefault(param.getOwner(), userId));
        entity.setCreateUser(userId);
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
        // Grav Catalog ? ????? ? OM?soft-fail???????
        postRegisterBridge.afterPersist(entity);
        return enrichVo(viewAssembler.toVo(this.getById(entity.getId())));
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public LhDatasourceVo edit(LhDatasourceEditParam param) {
        viewAssembler.enrichParamFromFe(param);
        if (StrUtil.isBlank(param.getId())) {
            throw new CommonException("id????");
        }
        LhDatasource entity = queryEntity(param.getId());
        assertCanEditDs(entity);
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
                || StrUtil.isNotBlank(param.getBootstrap())
                || StrUtil.isNotBlank(param.getBootstrapServers())
                || (StrUtil.isNotBlank(param.getPassword()) && !"******".equals(param.getPassword()))
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
        // ??????? Grav / ??? / OM?soft-fail?
        postRegisterBridge.afterPersist(entity);
        return enrichVo(viewAssembler.toVo(this.getById(entity.getId())));
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void delete(List<LhDatasourceIdParam> ids) {
        List<String> idList = CollStreamUtil.toList(ids, LhDatasourceIdParam::getId);
        for (String id : idList) {
            LhDatasource ds = this.getById(id);
            if (ds != null) {
                assertCanDeleteDs(ds);
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
        LhDatasourceVo vo = viewAssembler.toVo(queryEntity(param.getId()));
        linkedAssetFiller.fill(vo);
        sqlrestBindingEnricher.fill(vo);
        return enrichVo(vo);
    }

    @Override
    public Map<String, Object> test(LhDatasourceTestParam param) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            viewAssembler.enrichParamFromFe(param);
            LhDatasource existing = null;
            if (StrUtil.isNotBlank(param.getId())) {
                existing = queryEntity(param.getId());
            }
            String type = param.getType();
            if (StrUtil.isBlank(type) && existing != null) {
                type = existing.getType();
            }
            String typeCode;
            try {
                typeCode = formSchemaService.resolveTypeCode(type);
            } catch (Exception e) {
                typeCode = type;
            }
            param.setType(typeCode);
            Map<String, Object> conn = connNormalizer.mergeConn(param);
            // ??????? ******?????????????
            stripSecretPlaceholders(conn);
            // ??????? Vault ????????UI ?? id ???????
            if (existing != null) {
                if (StrUtil.isNotBlank(existing.getConnMasked())) {
                    try {
                        JSONUtil.parseObj(existing.getConnMasked()).forEach((k, v) -> {
                            if (v != null && !isSecretPlaceholder(v)) {
                                conn.putIfAbsent(k, v);
                            }
                        });
                    } catch (Exception ignored) {
                    }
                }
                vaultClient.readOrEmpty(existing.getVaultPath()).forEach((k, v) -> {
                    if (v == null || StrUtil.isBlank(String.valueOf(v)) || isSecretPlaceholder(v)) {
                        return;
                    }
                    // ???????????? Vault????????
                    if (isSecretKey(k) || !conn.containsKey(k) || isSecretPlaceholder(conn.get(k))) {
                        conn.put(k, v);
                    } else {
                        conn.putIfAbsent(k, v);
                    }
                });
                // Vault ?? username???? user
                if (StrUtil.isBlank(str(conn.get("user"))) && StrUtil.isNotBlank(str(conn.get("username")))) {
                    conn.put("user", conn.get("username"));
                }
                if (StrUtil.isBlank(str(conn.get("host"))) && StrUtil.isNotBlank(existing.getEndpointHost())) {
                    conn.put("host", existing.getEndpointHost());
                }
                if (StrUtil.isBlank(str(conn.get("port"))) && StrUtil.isNotBlank(existing.getEndpointPort())) {
                    conn.put("port", existing.getEndpointPort());
                }
                if (StrUtil.isBlank(str(conn.get("database"))) && StrUtil.isNotBlank(existing.getDatabaseName())) {
                    conn.put("database", existing.getDatabaseName());
                }
            }
            if (isSecretPlaceholder(conn.get("password"))) {
                throw new CommonException("??????????????????????????? Vault ???");
            }
            LhDatasourceConnNormalizer.NormalizedConn n = connNormalizer.normalize(
                    typeCode, conn, formSchemaService.defaultPort(typeCode));
            LhDatasourceTypeEnum typeEnum = LhDatasourceTypeEnum.of(typeCode).orElse(null);

            if (typeEnum != null && typeEnum.isJdbc()) {
                String url = buildJdbcUrl(typeCode, n, conn);
                if (StrUtil.isBlank(url)) {
                    throw new CommonException("???? JDBC URL???? host/port/database");
                }
                if (StrUtil.isBlank(n.user)) {
                    throw new CommonException("???????");
                }
                if (StrUtil.isBlank(n.password)) {
                    throw new CommonException("??????");
                }
                try (Connection c = DriverManager.getConnection(url, n.user, n.password);
                     Statement st = c.createStatement()) {
                    st.setQueryTimeout(5);
                    st.execute("SELECT 1");
                } catch (java.sql.SQLException jdbcEx) {
                    // ClickHouse/Trino ????? classpath ???? TCP???????
                    String msg = StrUtil.blankToDefault(jdbcEx.getMessage(), "");
                    if (msg.contains("No suitable driver") || msg.contains("????????")) {
                        probeTcp(n.host, n.port, typeCode + " ??");
                        result.put("warning", "??? " + typeCode + " JDBC ???? TCP ????");
                    } else {
                        throw jdbcEx;
                    }
                }
            } else if (typeEnum == LhDatasourceTypeEnum.HTTP_API
                    || typeEnum == LhDatasourceTypeEnum.TABLEAU
                    || typeEnum == LhDatasourceTypeEnum.SUPERSET
                    || typeEnum == LhDatasourceTypeEnum.AIRFLOW
                    || StrUtil.isNotBlank(str(conn.get("baseURL")))) {
                String url = firstNonBlank(str(conn.get("baseURL")), str(conn.get("httpUrl")), n.host);
                if (StrUtil.isBlank(url)) {
                    throw new CommonException("baseURL ????");
                }
                if (!url.startsWith("http")) {
                    url = "https://" + url;
                }
                cn.hutool.http.HttpRequest.get(url).timeout(5000).execute();
            } else if (typeEnum == LhDatasourceTypeEnum.KAFKA) {
                String bootstrap = firstNonBlank(str(conn.get("bootstrap")), str(conn.get("bootstrapServers")), n.host);
                String port = firstNonBlank(str(conn.get("port")), n.port);
                // ??????????????
                if (StrUtil.isNotBlank(port) && StrUtil.isNotBlank(bootstrap) && !bootstrap.contains(":")) {
                    bootstrap = bootstrap + ":" + port;
                }
                probeTcp(bootstrap, null, "Kafka bootstrap");
            } else {
                String host = firstNonBlank(n.host, str(conn.get("endpoint")), str(conn.get("zkQuorum")),
                        str(conn.get("nameNode")), str(conn.get("serviceUrl")));
                probeTcp(host, n.port, "??");
            }
            result.putIfAbsent("ok", true);
            result.put("costMs", System.currentTimeMillis() - start);
            if (existing != null) {
                existing.setLastOkAt(new Date());
                existing.setStatus(LhDatasourceStatusEnum.ONLINE.getValue());
                existing.setHealthScore(100);
                this.updateById(existing);
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

    private void probeTcp(String host, String port, String label) throws Exception {
        if (StrUtil.isBlank(host)) {
            throw new CommonException(label + " host ????");
        }
        String h = host.trim();
        int p = 9092;
        try {
            if (StrUtil.isNotBlank(port)) {
                p = Integer.parseInt(port.trim());
            } else if (h.contains(":")) {
                int idx = h.lastIndexOf(':');
                p = Integer.parseInt(h.substring(idx + 1).trim());
                h = h.substring(0, idx);
            }
        } catch (NumberFormatException e) {
            throw new CommonException(label + " port ??");
        }
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(h, p), 5000);
        }
    }

    @Override
    public Map<String, Object> previewSchema(LhDatasourceIdParam param) {
        LhDatasource ds = queryEntity(param.getId());
        Map<String, Object> secret = vaultClient.read(ds.getVaultPath());
        List<Map<String, Object>> columns = new ArrayList<>();
        LhDatasourceTypeEnum typeEnum = LhDatasourceTypeEnum.of(ds.getType()).orElse(null);
        List<LhDsTable> registered = dsTableMapper.selectList(new QueryWrapper<LhDsTable>().lambda()
                .eq(LhDsTable::getDsId, ds.getId()).last("LIMIT 100"));
        if (typeEnum != null && typeEnum.isJdbc()) {
            try {
                String url = String.valueOf(secret.getOrDefault("jdbcUrl", ""));
                String user = String.valueOf(secret.getOrDefault("username", ""));
                String pwd = String.valueOf(secret.getOrDefault("password", ""));
                try (Connection conn = DriverManager.getConnection(url, user, pwd)) {
                    DatabaseMetaData meta = conn.getMetaData();
                    List<String> tableNames = new ArrayList<>();
                    for (LhDsTable t : registered) {
                        if (StrUtil.isNotBlank(t.getTableName())) {
                            tableNames.add(t.getTableName());
                        }
                    }
                    if (tableNames.isEmpty()) {
                        tableNames.add("%");
                    }
                    String dsDb = StrUtil.blankToDefault(ds.getDatabaseName(), null);
                    boolean mysqlFamily = typeEnum == LhDatasourceTypeEnum.MYSQL
                            || typeEnum == LhDatasourceTypeEnum.DORIS
                            || typeEnum == LhDatasourceTypeEnum.CLICKHOUSE;
                    boolean pgFamily = typeEnum == LhDatasourceTypeEnum.PG
                            || typeEnum == LhDatasourceTypeEnum.POSTGRESQL;
                    int n = 0;
                    int maxCols = 2000;
                    for (String tablePat : tableNames) {
                        String simple = tablePat;
                        String schemaPat = null;
                        String catalog = null;
                        if (tablePat.contains(".")) {
                            String[] parts = tablePat.split("\\.");
                            simple = parts[parts.length - 1];
                            if (parts.length >= 2) {
                                schemaPat = parts[parts.length - 2];
                            }
                        }
                        // MySQL?catalog=???schema=null?PG?schema=public?????
                        if (mysqlFamily) {
                            catalog = dsDb;
                            schemaPat = null;
                        } else if (pgFamily) {
                            catalog = dsDb;
                            if (StrUtil.isBlank(schemaPat)) {
                                schemaPat = vip.xiaonuo.lh.modular.datasource.form.LhDatasourceConnNormalizer
                                        .resolveJdbcSchemaName(secret.get("schema"), "public");
                            }
                        }
                        try (ResultSet rs = meta.getColumns(catalog, schemaPat, simple, "%")) {
                            while (rs.next() && n++ < maxCols) {
                                Map<String, Object> col = new LinkedHashMap<>();
                                String tbl = rs.getString("TABLE_NAME");
                                String sch = rs.getString("TABLE_SCHEM");
                                if (StrUtil.isNotBlank(sch) && !"null".equalsIgnoreCase(sch)
                                        && pgFamily) {
                                    col.put("table", sch + "." + tbl);
                                } else {
                                    col.put("table", tbl);
                                }
                                col.put("column", rs.getString("COLUMN_NAME"));
                                col.put("type", rs.getString("TYPE_NAME"));
                                columns.add(col);
                            }
                        }
                        if (n >= maxCols) {
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("previewSchema JDBC fail dsId={}: {}", ds.getId(), e.getMessage());
                columns.add(Map.of("table", "_error", "column", "_preview_failed", "type", "STRING",
                        "message", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName())));
            }
        } else {
            for (LhDsTable t : registered) {
                columns.add(Map.of("name", t.getTableName(), "type", "OBJECT",
                        "cnName", StrUtil.blankToDefault(t.getCnName(), "")));
            }
            if (columns.isEmpty()) {
                columns.add(Map.of("name", "payload", "type", "STRING"));
            }
        }
        return Map.of("dsId", ds.getId(), "type", ds.getType(), "columns", columns);
    }

    @Override
    public List<String> listMetaSchemas(LhDatasourceMetaParam param) {
        LhDatasource ds = queryEntity(param.getId());
        assertCanUseDs(ds);
        if (!jdbcMetaBrowser.supportsJdbc(ds)) {
            log.info("meta schemas skipped (non-jdbc) dsId={} type={}: {}",
                    ds.getId(), ds.getType(), jdbcMetaBrowser.unsupportedMessage(ds));
            return Collections.emptyList();
        }
        return jdbcMetaBrowser.listSchemas(ds);
    }

    @Override
    public List<LhMetaObjectVo> listMetaTables(LhDatasourceMetaParam param) {
        LhDatasource ds = queryEntity(param.getId());
        assertCanUseDs(ds);
        if (!jdbcMetaBrowser.supportsJdbc(ds)) {
            return Collections.emptyList();
        }
        List<LhMetaObjectVo> raw = jdbcMetaBrowser.listTables(ds, param.getSchema());
        return apiBuildTableAccess.filterReadableObjects(ds.getId(), param.getSchema(), raw);
    }

    @Override
    public List<LhMetaObjectVo> listMetaViews(LhDatasourceMetaParam param) {
        LhDatasource ds = queryEntity(param.getId());
        assertCanUseDs(ds);
        if (!jdbcMetaBrowser.supportsJdbc(ds)) {
            return Collections.emptyList();
        }
        List<LhMetaObjectVo> raw = jdbcMetaBrowser.listViews(ds, param.getSchema());
        return apiBuildTableAccess.filterReadableObjects(ds.getId(), param.getSchema(), raw);
    }

    @Override
    public List<LhMetaColumnVo> listMetaColumns(LhDatasourceMetaParam param) {
        LhDatasource ds = queryEntity(param.getId());
        assertCanUseDs(ds);
        apiBuildTableAccess.assertCanReadTableForMeta(ds.getId(), param.getSchema(), param.getTable());
        if (!jdbcMetaBrowser.supportsJdbc(ds)) {
            return Collections.emptyList();
        }
        List<LhMetaColumnVo> cols = jdbcMetaBrowser.listColumns(ds, param.getSchema(), param.getTable());
        apiBuildTableAccess.markSensitiveColumns(cols);
        return cols;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updatePurposes(LhDatasourcePurposesParam param) {
        LhDatasource ds = queryEntity(param.getId());
        assertCanEditDs(ds);
        if (param.getPurposes().contains("analyze_direct") && !"trino".equals(ds.getType())) {
            throw new CommonException("??????? analyze_direct");
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
        assertCanEditDs(ds);
        vaultDynamicRotate.rotateExisting(ds.getVaultPath());
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
        List<LhDatasourceVo> vos = this.list(new QueryWrapper<LhDatasource>().lambda()
                        .eq(LhDatasource::getStatus, LhDatasourceStatusEnum.ONLINE.getValue())
                        .like(LhDatasource::getPurposes, "ingest")
                        .in(LhDatasource::getType, ingestTypes))
                .stream()
                .filter(ds -> LhLoginUsers.isSuperAdmin() || secAuthGrantService.canUseDatasource(ds.getId()))
                .map(viewAssembler::toVo)
                .collect(Collectors.toList());
        userNameResolver.fillDatasources(vos);
        return vos;
    }

    @Override
    public List<LhDatasourceVo> supersetProjection() {
        List<LhDatasourceVo> vos = this.list(new QueryWrapper<LhDatasource>().lambda()
                        .eq(LhDatasource::getType, "trino")
                        .like(LhDatasource::getPurposes, "query_gateway")
                        .ne(LhDatasource::getStatus, LhDatasourceStatusEnum.REVOKED.getValue()))
                .stream().map(viewAssembler::toVo).collect(Collectors.toList());
        userNameResolver.fillDatasources(vos);
        return vos;
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
        assertCanEditDs(ds);
        String next = LhDatasourceStatusEnum.ONLINE.getValue().equals(ds.getStatus())
                ? LhDatasourceStatusEnum.PAUSED.getValue()
                : LhDatasourceStatusEnum.ONLINE.getValue();
        ds.setStatus(next);
        ds.setRevision(Optional.ofNullable(ds.getRevision()).orElse(1) + 1);
        this.updateById(ds);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ds.getId());
        m.put("status", next);
        m.put("source", enrichVo(viewAssembler.toVo(ds)));
        return m;
    }

    @Override
    public Map<String, Object> kpi(String ws) {
        QueryWrapper<LhDatasource> qw = new QueryWrapper<LhDatasource>().checkSqlInjection();
        qw.lambda().ne(LhDatasource::getStatus, LhDatasourceStatusEnum.REVOKED.getValue());
        String workspace = vip.xiaonuo.lh.core.ws.LhWsFilters.listWs(ws);
        if (StrUtil.isNotBlank(workspace)) {
            qw.lambda().eq(LhDatasource::getWs, workspace);
        }
        List<LhDatasource> all = this.list(qw);
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
        // ?????? schema ????????????
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
        // 表清单常 >100；CommonPageRequest 全局上限 100，本接口单独放宽
        Page<LhDsTable> pageReq = inventoryTablePageRequest();
        Page<LhDsTable> raw = dsTableMapper.selectPage(pageReq, qw);
        Page<LhDsTableVo> page = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        page.setRecords(raw.getRecords().stream().map(viewAssembler::toTableVo).collect(Collectors.toList()));
        return page;
    }

    /** 表清单分页：默认 500，最大 5000（覆盖同步后全量展示） */
    private static Page<LhDsTable> inventoryTablePageRequest() {
        long current = 1;
        long size = 500;
        if (vip.xiaonuo.common.util.CommonServletUtil.isWeb()) {
            String pageStr = vip.xiaonuo.common.util.CommonServletUtil.getParamFromRequest("current");
            String sizeStr = vip.xiaonuo.common.util.CommonServletUtil.getParamFromRequest("size");
            if (StrUtil.isNotBlank(pageStr)) {
                try {
                    current = Math.max(1, Long.parseLong(pageStr.trim()));
                } catch (NumberFormatException ignored) {
                    current = 1;
                }
            }
            if (StrUtil.isNotBlank(sizeStr)) {
                try {
                    size = Long.parseLong(sizeStr.trim());
                } catch (NumberFormatException ignored) {
                    size = 500;
                }
            }
        }
        size = Math.max(1, Math.min(size, 5000));
        return new Page<>(current, size);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public LhDsTableVo tableAdd(LhDsTableAddParam param) {
        LhDatasource ds = queryEntity(param.getDsId());
        assertCanEditDs(ds);
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
            throw new CommonException("表不存在: {}", param.getId());
        }
        LhDatasource ds = queryEntity(row.getDsId());
        assertCanEditDs(ds);
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
        for (String dsId : dsIds) {
            assertCanDeleteDs(queryEntity(dsId));
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
        assertCanEditDs(ds);
        Date now = new Date();
        DiscoverBundle bundle = discoverRemoteTables(ds);
        List<RemoteTableMeta> metas = bundle.metas;
        int added = 0;
        int updated = 0;
        int removed = 0;
        List<LhDsTableVo> touched = new ArrayList<>();
        Set<String> remoteNames = new HashSet<>();
        for (RemoteTableMeta meta : metas) {
            if (StrUtil.isBlank(meta.name)) {
                continue;
            }
            remoteNames.add(meta.name);
            LhDsTable row = dsTableMapper.selectOne(new QueryWrapper<LhDsTable>().lambda()
                    .eq(LhDsTable::getDsId, ds.getId())
                    .eq(LhDsTable::getTableName, meta.name));
            // PG 历史清单只有裸表名：同步到 schema.table 时原地升级，避免删旧建新导致资产 objectName 悬空
            if (row == null && meta.name.contains(".")) {
                row = upgradeBareInventoryName(ds.getId(), meta.name);
            }
            if (row == null) {
                row = new LhDsTable();
                row.setId(IdUtil.getSnowflakeNextIdStr());
                row.setRevision(1);
                row.setDsId(ds.getId());
                row.setTableName(meta.name);
                row.setStatus("ENABLE");
                applyRemoteMeta(row, meta, now);
                dsTableMapper.insert(row);
                added++;
            } else {
                applyRemoteMeta(row, meta, now);
                row.setRevision(Optional.ofNullable(row.getRevision()).orElse(1) + 1);
                dsTableMapper.updateById(row);
                updated++;
            }
            touched.add(viewAssembler.toTableVo(row));
        }
        // ????????????????????????? mock ???
        Map<String, Object> catalogStale = null;
        String catalogStaleError = null;
        if (bundle.fromRemote) {
            List<LhDsTable> existing = dsTableMapper.selectList(new QueryWrapper<LhDsTable>().lambda()
                    .eq(LhDsTable::getDsId, ds.getId()));
            List<LhDsTable> staleRows = existing.stream()
                    .filter(t -> !remoteNames.contains(t.getTableName()))
                    .collect(Collectors.toList());
            if (!staleRows.isEmpty()) {
                try {
                    List<String> missingNames = staleRows.stream()
                            .map(LhDsTable::getTableName)
                            .filter(StrUtil::isNotBlank)
                            .toList();
                    catalogStale = govAssetSourceReconcile.markMissingObjects(ds.getId(), missingNames);
                } catch (Exception e) {
                    catalogStaleError = e.getMessage();
                }
                List<String> staleIds = staleRows.stream().map(LhDsTable::getId).collect(Collectors.toList());
                dsTableMapper.deleteBatchIds(staleIds);
                removed = staleIds.size();
            }
        }
        refreshSchemaSummary(ds.getId());
        long total = dsTableMapper.selectCount(new QueryWrapper<LhDsTable>().lambda()
                .eq(LhDsTable::getDsId, ds.getId()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dsId", ds.getId());
        result.put("source", bundle.fromRemote ? "remote" : ("manual".equals(bundle.path) ? "manual" : "fallback"));
        result.put("path", bundle.path);
        result.put("objectKind", bundle.objectKind);
        result.put("objectKindLabel", LhInventoryObjectKinds.labelOf(bundle.objectKind));
        if (StrUtil.isNotBlank(bundle.hint)) {
            result.put("hint", bundle.hint);
        }
        result.put("discovered", metas.size());
        result.put("added", added);
        result.put("updated", updated);
        result.put("removed", removed);
        result.put("total", total);
        result.put("tables", touched);
        result.put("schema", this.getById(ds.getId()).getSchemaSummary());
        if (catalogStale != null) {
            result.put("catalogStale", catalogStale);
        }
        if (catalogStaleError != null) {
            result.put("catalogStaleError", catalogStaleError);
        }
        // P2???????Grav ?? OM ?????? OM?soft-fail?
        if (portalInventoryOmBridge.shouldPush(ds)) {
            try {
                Map<String, Object> portalOm = portalInventoryOmBridge.pushInventory(
                        ds, gravitinoProjector.catalogNameOf(ds));
                result.put("portalOm", portalOm);
            } catch (Exception e) {
                result.put("portalOmError", e.getMessage());
            }
        }
        return result;
    }

    @Override
    public Map<String, Object> tableDiscover(LhDatasourceTestParam param) {
        viewAssembler.enrichParamFromFe(param);
        LhDatasource existing = null;
        if (StrUtil.isNotBlank(param.getId())) {
            existing = queryEntity(param.getId());
        }
        String type = param.getType();
        if (StrUtil.isBlank(type) && existing != null) {
            type = existing.getType();
        }
        String typeCode;
        try {
            typeCode = formSchemaService.resolveTypeCode(type);
        } catch (Exception e) {
            typeCode = type;
        }
        param.setType(typeCode);
        Map<String, Object> conn = connNormalizer.mergeConn(param);
        stripSecretPlaceholders(conn);
        if (existing != null) {
            if (StrUtil.isNotBlank(existing.getConnMasked())) {
                try {
                    JSONUtil.parseObj(existing.getConnMasked()).forEach((k, v) -> {
                        if (v != null && !isSecretPlaceholder(v)) {
                            conn.putIfAbsent(k, v);
                        }
                    });
                } catch (Exception ignored) {
                }
            }
            vaultClient.readOrEmpty(existing.getVaultPath()).forEach((k, v) -> {
                if (v == null || StrUtil.isBlank(String.valueOf(v)) || isSecretPlaceholder(v)) {
                    return;
                }
                if (isSecretKey(k) || !conn.containsKey(k) || isSecretPlaceholder(conn.get(k))) {
                    conn.put(k, v);
                } else {
                    conn.putIfAbsent(k, v);
                }
            });
            if (StrUtil.isBlank(str(conn.get("user"))) && StrUtil.isNotBlank(str(conn.get("username")))) {
                conn.put("user", conn.get("username"));
            }
            if (StrUtil.isBlank(str(conn.get("host"))) && StrUtil.isNotBlank(existing.getEndpointHost())) {
                conn.put("host", existing.getEndpointHost());
            }
            if (StrUtil.isBlank(str(conn.get("port"))) && StrUtil.isNotBlank(existing.getEndpointPort())) {
                conn.put("port", existing.getEndpointPort());
            }
            if (StrUtil.isBlank(str(conn.get("database"))) && StrUtil.isNotBlank(existing.getDatabaseName())) {
                conn.put("database", existing.getDatabaseName());
            }
        }
        if (isSecretPlaceholder(conn.get("password"))) {
            throw new CommonException("??????????????????????????? Vault ???");
        }
        LhDatasourceConnNormalizer.NormalizedConn n = connNormalizer.normalize(
                typeCode, conn, formSchemaService.defaultPort(typeCode));
        LhDatasourceTypeEnum typeEnum = LhDatasourceTypeEnum.of(typeCode).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", typeCode);
        // ???????? Discoverer ???Hive/Kafka/ES/Redis/RMQ/MinIO ????????????
        if (existing != null) {
            LhInventoryDiscoverer discoverer = findInventoryDiscoverer(typeCode);
            if (discoverer != null) {
                DiscoverBundle bundle = discoverRemoteTables(existing);
                List<Map<String, Object>> tables = new ArrayList<>();
                for (RemoteTableMeta m : bundle.metas) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", m.name);
                    row.put("comment", m.comment);
                    row.put("engine", m.engine);
                    row.put("encoding", m.encoding);
                    row.put("rowCount", m.rowCount);
                    tables.add(row);
                }
                result.put("ok", true);
                result.put("source", bundle.fromRemote ? "remote"
                        : ("manual".equals(bundle.path) ? "manual" : "fallback"));
                result.put("path", bundle.path);
                result.put("objectKind", bundle.objectKind);
                result.put("objectKindLabel", LhInventoryObjectKinds.labelOf(bundle.objectKind));
                if (StrUtil.isNotBlank(bundle.hint)) {
                    result.put("hint", bundle.hint);
                }
                result.put("discovered", tables.size());
                result.put("tables", tables);
                result.put("schema", tables.stream().map(t -> String.valueOf(t.get("name")))
                        .collect(Collectors.joining(",")));
                return result;
            }
        }
        if (typeEnum == null || !typeEnum.isJdbc()) {
            String kind = LhInventoryObjectKinds.ofType(typeCode);
            String kindLabel = LhInventoryObjectKinds.labelOf(kind);
            String hint = LhManualSummaryInventoryDiscoverer.hintOf(
                    StrUtil.blankToDefault(typeCode, "").toLowerCase(Locale.ROOT), kindLabel);
            if (StrUtil.isBlank(hint) || hint.startsWith("????")) {
                hint = "??????? JDBC ??????????????????????"
                        + kindLabel + "?";
            }
            result.put("ok", false);
            result.put("error", hint);
            result.put("objectKind", kind);
            result.put("objectKindLabel", kindLabel);
            result.put("path", "unsupported");
            result.put("tables", Collections.emptyList());
            return result;
        }
        String url = buildJdbcUrl(typeCode, n, conn);
        if (StrUtil.isBlank(url)) {
            throw new CommonException("???? JDBC URL???? host/port/database");
        }
        if (StrUtil.isBlank(n.user) || StrUtil.isBlank(n.password)) {
            throw new CommonException("???/??????");
        }
        LhDatasource probe = existing != null ? existing : new LhDatasource();
        if (existing == null) {
            probe.setType(typeCode);
            probe.setDatabaseName(n.database);
        }
        try (Connection c = DriverManager.getConnection(url, n.user, n.password)) {
            Map<String, Object> secret = new LinkedHashMap<>(conn);
            secret.put("database", n.database);
            List<RemoteTableMeta> metas;
            if ("mysql".equals(typeCode) || "doris".equals(typeCode)) {
                metas = discoverMysqlTables(c, secret, probe);
            } else if ("postgresql".equals(typeCode) || "pg".equals(typeCode)) {
                metas = discoverPgTables(c);
            } else {
                metas = discoverJdbcTables(c);
            }
            List<Map<String, Object>> tables = new ArrayList<>();
            for (RemoteTableMeta m : metas) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", m.name);
                row.put("comment", m.comment);
                row.put("engine", m.engine);
                row.put("encoding", m.encoding);
                row.put("rowCount", m.rowCount);
                tables.add(row);
            }
            result.put("ok", true);
            result.put("source", "remote");
            result.put("discovered", tables.size());
            result.put("tables", tables);
            result.put("schema", tables.stream().map(t -> String.valueOf(t.get("name")))
                    .collect(Collectors.joining(",")));
            return result;
        } catch (CommonException e) {
            throw e;
        } catch (Exception e) {
            throw new CommonException("???????: {}", e.getMessage());
        }
    }

    /**
     * ????????Discoverer ???Hive/Kafka/ES/Redis/RMQ/MinIO / ?????? JDBC ? fallback
     */
    private DiscoverBundle discoverRemoteTables(LhDatasource ds) {
        String type = StrUtil.blankToDefault(ds.getType(), "").toLowerCase(Locale.ROOT);
        LhInventoryDiscoverer discoverer = findInventoryDiscoverer(type);
        if (discoverer != null) {
            return fromInventory(discoverer.discover(ds));
        }
        LhDatasourceTypeEnum typeEnum = LhDatasourceTypeEnum.of(ds.getType()).orElse(null);
        if (typeEnum == null || !typeEnum.isJdbc()) {
            DiscoverBundle b = new DiscoverBundle();
            b.fromRemote = false;
            b.path = "fallback";
            b.objectKind = LhInventoryObjectKinds.ofType(type);
            b.hint = "????????????? schemaSummary ??????";
            b.metas = parseSchemaSummary(ds.getSchemaSummary()).stream().map(n -> {
                RemoteTableMeta m = new RemoteTableMeta();
                m.name = n;
                return m;
            }).collect(Collectors.toList());
            return b;
        }
        try {
            Map<String, Object> secret = vaultClient.read(ds.getVaultPath());
            String url = String.valueOf(secret.getOrDefault("jdbcUrl", ""));
            String user = String.valueOf(secret.getOrDefault("username", ""));
            String pwd = String.valueOf(secret.getOrDefault("password", ""));
            if (StrUtil.isBlank(url)) {
                throw new CommonException("Vault ??? jdbcUrl");
            }
            try (Connection conn = DriverManager.getConnection(url, user, pwd)) {
                List<RemoteTableMeta> metas;
                if ("mysql".equals(type) || "doris".equals(type)) {
                    metas = discoverMysqlTables(conn, secret, ds);
                } else if ("postgresql".equals(type) || "pg".equals(type)) {
                    metas = discoverPgTables(conn);
                } else {
                    metas = discoverJdbcTables(conn);
                }
                DiscoverBundle b = new DiscoverBundle();
                b.fromRemote = true;
                b.path = "jdbc";
                b.objectKind = LhInventoryObjectKinds.TABLE;
                b.metas = metas;
                return b;
            }
        } catch (Exception e) {
            DiscoverBundle b = new DiscoverBundle();
            b.fromRemote = false;
            b.path = "fallback";
            b.objectKind = LhInventoryObjectKinds.TABLE;
            b.hint = "JDBC ???????? schemaSummary?" + e.getMessage();
            b.metas = parseSchemaSummary(ds.getSchemaSummary()).stream().map(n -> {
                RemoteTableMeta m = new RemoteTableMeta();
                m.name = n;
                m.comment = "fallback";
                return m;
            }).collect(Collectors.toList());
            if (b.metas.isEmpty()) {
                throw new CommonException("???????: {}", e.getMessage());
            }
            return b;
        }
    }

    private LhInventoryDiscoverer findInventoryDiscoverer(String typeCode) {
        if (inventoryDiscoverers == null || inventoryDiscoverers.isEmpty()) {
            return null;
        }
        for (LhInventoryDiscoverer d : inventoryDiscoverers) {
            if (d.supports(typeCode)) {
                return d;
            }
        }
        return null;
    }

    private static DiscoverBundle fromInventory(LhInventoryDiscoverResult inv) {
        DiscoverBundle b = new DiscoverBundle();
        b.fromRemote = inv.fromRemote;
        b.path = inv.path;
        b.objectKind = StrUtil.blankToDefault(inv.objectKind, LhInventoryObjectKinds.TABLE);
        b.hint = inv.hint;
        List<RemoteTableMeta> metas = new ArrayList<>();
        for (LhRemoteInventoryItem item : inv.items) {
            RemoteTableMeta m = new RemoteTableMeta();
            m.name = item.name;
            m.comment = item.comment;
            m.engine = item.engine;
            m.encoding = item.encoding;
            m.rowCount = item.rowCount;
            metas.add(m);
        }
        b.metas = metas;
        return b;
    }

    private static final class DiscoverBundle {
        boolean fromRemote;
        String path = "unknown";
        String objectKind = LhInventoryObjectKinds.TABLE;
        String hint;
        List<RemoteTableMeta> metas = Collections.emptyList();
    }

    private List<RemoteTableMeta> discoverMysqlTables(Connection conn, Map<String, Object> secret,
                                                      LhDatasource ds) throws Exception {
        String schema = firstNonBlank(
                str(secret.get("database")),
                ds.getDatabaseName(),
                conn.getCatalog());
        String sql = "SELECT TABLE_NAME, ENGINE, TABLE_ROWS, TABLE_COMMENT, TABLE_COLLATION "
                + "FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE IN ('BASE TABLE','VIEW') "
                + "ORDER BY TABLE_NAME";
        List<RemoteTableMeta> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    RemoteTableMeta m = new RemoteTableMeta();
                    m.name = rs.getString("TABLE_NAME");
                    m.engine = rs.getString("ENGINE");
                    long rows = rs.getLong("TABLE_ROWS");
                    if (!rs.wasNull()) {
                        m.rowCount = rows;
                    }
                    m.comment = rs.getString("TABLE_COMMENT");
                    m.encoding = collationToEncoding(rs.getString("TABLE_COLLATION"));
                    list.add(m);
                }
            }
        }
        return list;
    }

    private List<RemoteTableMeta> discoverPgTables(Connection conn) throws Exception {
        // MUST 写入 schema.table：PG 库名 ≠ schema，裸表名无法唯一定位（public/cp/...）
        String sql = "SELECT n.nspname AS schema_name, c.relname AS table_name, "
                + "COALESCE(obj_description(c.oid), '') AS table_comment, "
                + "COALESCE(s.n_live_tup, 0) AS table_rows "
                + "FROM pg_class c "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid "
                + "WHERE c.relkind IN ('r','p','v','m') "
                + "AND n.nspname NOT IN ('pg_catalog','information_schema','pg_toast') "
                + "AND n.nspname NOT LIKE 'pg_temp_%' AND n.nspname NOT LIKE 'pg_toast_temp_%' "
                + "ORDER BY n.nspname, c.relname";
        List<RemoteTableMeta> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String schema = rs.getString("schema_name");
                String table = rs.getString("table_name");
                if (StrUtil.isBlank(table)) {
                    continue;
                }
                RemoteTableMeta m = new RemoteTableMeta();
                m.name = StrUtil.isNotBlank(schema) ? schema + "." + table : table;
                m.comment = rs.getString("table_comment");
                m.rowCount = rs.getLong("table_rows");
                m.engine = "heap";
                m.encoding = "UTF8";
                list.add(m);
            }
        }
        return list;
    }

    private List<RemoteTableMeta> discoverJdbcTables(Connection conn) throws Exception {
        List<RemoteTableMeta> list = new ArrayList<>();
        try (ResultSet rs = conn.getMetaData().getTables(conn.getCatalog(), null, "%",
                new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                String table = rs.getString("TABLE_NAME");
                if (StrUtil.isBlank(table)) {
                    continue;
                }
                String schem = rs.getString("TABLE_SCHEM");
                RemoteTableMeta m = new RemoteTableMeta();
                if (StrUtil.isNotBlank(schem) && !"null".equalsIgnoreCase(schem)) {
                    m.name = schem + "." + table;
                } else {
                    m.name = table;
                }
                m.comment = rs.getString("REMARKS");
                list.add(m);
            }
        }
        return list;
    }

    /**
     * 将历史裸表名升级为 schema.table（仅当该 ds 下裸名唯一命中时）。
     * 同时回写 gov_asset_source_link.object_name，避免资产仍指向旧名。
     */
    private LhDsTable upgradeBareInventoryName(String dsId, String schemaTable) {
        String bare = schemaTable.substring(schemaTable.lastIndexOf('.') + 1);
        if (StrUtil.isBlank(bare) || bare.equals(schemaTable)) {
            return null;
        }
        List<LhDsTable> hits = dsTableMapper.selectList(new QueryWrapper<LhDsTable>().lambda()
                .eq(LhDsTable::getDsId, dsId)
                .eq(LhDsTable::getTableName, bare));
        if (hits == null || hits.size() != 1) {
            return null;
        }
        LhDsTable row = hits.get(0);
        String oldName = row.getTableName();
        row.setTableName(schemaTable);
        try {
            // soft：资产源绑定同名对象一并升级
            govAssetSourceReconcile.renameObjectName(dsId, oldName, schemaTable);
        } catch (Exception e) {
            log.warn("rename asset objectName {} -> {} soft-fail: {}", oldName, schemaTable, e.getMessage());
        }
        return row;
    }

    private void applyRemoteMeta(LhDsTable row, RemoteTableMeta meta, Date now) {
        if (StrUtil.isNotBlank(meta.comment)) {
            row.setCommentTxt(StrUtil.sub(meta.comment, 0, 512));
            // ?????????????
            if (StrUtil.isBlank(row.getCnName())) {
                row.setCnName(StrUtil.sub(meta.comment, 0, 64));
            }
        }
        if (StrUtil.isNotBlank(meta.engine)) {
            row.setEngine(meta.engine);
        }
        if (StrUtil.isNotBlank(meta.encoding)) {
            row.setEncoding(meta.encoding);
        } else if (StrUtil.isBlank(row.getEncoding())) {
            row.setEncoding("utf8mb4");
        }
        if (meta.rowCount != null) {
            row.setRowCount(meta.rowCount);
        }
        row.setSyncedAt(now);
    }

    private static String collationToEncoding(String collation) {
        if (StrUtil.isBlank(collation)) {
            return "utf8mb4";
        }
        // utf8mb4_general_ci ? utf8mb4
        int idx = collation.indexOf('_');
        return idx > 0 ? collation.substring(0, idx) : collation;
    }

    private static class RemoteTableMeta {
        String name;
        String comment;
        String engine;
        String encoding;
        Long rowCount;
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

    @Override
    public Map<String, Object> projectToGravitino(List<LhDatasourceIdParam> ids) {
        List<LhDatasource> list;
        if (ids == null || ids.isEmpty()) {
            list = this.list(new QueryWrapper<LhDatasource>().lambda()
                    .ne(LhDatasource::getStatus, LhDatasourceStatusEnum.REVOKED.getValue()));
        } else {
            list = new ArrayList<>();
            for (LhDatasourceIdParam p : ids) {
                list.add(queryEntity(p.getId()));
            }
        }
        int projected = 0;
        int skipped = 0;
        int errors = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (LhDatasource ds : list) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", ds.getId());
            one.put("name", ds.getName());
            one.put("type", ds.getType());
            try {
                Map<String, Object> r = gravitinoProjector.project(ds);
                one.putAll(r);
                if (Boolean.TRUE.equals(r.get("skipped"))) {
                    skipped++;
                } else if (r.get("error") != null) {
                    errors++;
                } else {
                    projected++;
                }
            } catch (Exception e) {
                errors++;
                one.put("error", e.getMessage());
            }
            details.add(one);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", list.size());
        out.put("projected", projected);
        out.put("skipped", skipped);
        out.put("errors", errors);
        out.put("details", details);
        return out;
    }

    @Override
    public List<Map<String, Object>> listForSqlrest() {
        List<LhDatasource> all = this.list(new QueryWrapper<LhDatasource>().lambda()
                .eq(LhDatasource::getStatus, LhDatasourceStatusEnum.ONLINE.getValue()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (LhDatasource ds : all) {
            boolean projectable = LhDatasourceSqlrestProjector.isProjectable(ds.getType());
            if (!projectable && !"trino".equalsIgnoreCase(ds.getType())) {
                continue;
            }
            LhConsumerBinding bind = bindingMapper.selectOne(new QueryWrapper<LhConsumerBinding>().lambda()
                    .eq(LhConsumerBinding::getDsId, ds.getId())
                    .eq(LhConsumerBinding::getConsumerType, LhDatasourceSqlrestProjector.CONSUMER_TYPE)
                    .last("LIMIT 1"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", ds.getId());
            row.put("dsCode", ds.getDsCode());
            row.put("name", ds.getName());
            row.put("type", ds.getType());
            row.put("endpoint", StrUtil.blankToDefault(ds.getEndpointHost(), "")
                    + (StrUtil.isNotBlank(ds.getEndpointPort()) ? ":" + ds.getEndpointPort() : ""));
            row.put("database", ds.getDatabaseName());
            row.put("projectable", projectable);
            row.put("recommended", "trino".equalsIgnoreCase(ds.getType())
                    || (ds.getPurposes() != null && ds.getPurposes().contains("query_gateway")));
            if (bind != null) {
                row.put("syncState", bind.getSyncState());
                row.put("lastError", bind.getLastError());
                row.put("lastSyncAt", bind.getLastSyncAt());
                Long srId = null;
                if (StrUtil.isNotBlank(bind.getProjection())) {
                    cn.hutool.json.JSONObject p = JSONUtil.parseObj(bind.getProjection());
                    srId = p.getLong("sqlrestDatasourceId");
                    row.put("sqlrestDatasourceId", srId);
                    row.put("sqlrestName", p.get("sqlrestName"));
                    row.put("sqlrestType", p.get("sqlrestType"));
                }
                boolean synced = "synced".equalsIgnoreCase(bind.getSyncState()) && srId != null;
                row.put("projected", synced);
            } else {
                row.put("syncState", projectable ? "never" : "unsupported");
                row.put("projected", false);
                row.put("lastSyncAt", null);
            }
            // 平台权限：仅返回当前用户可用源（拥有者或 EDIT/MANAGE）
            try {
                if (!secAuthGrantService.canUseDatasource(ds.getId())) {
                    continue;
                }
            } catch (Exception e) {
                // 未登录等：不返回
                continue;
            }
            out.add(row);
        }
        return out;
    }

    @Override
    public Map<String, Object> projectToSqlrest(List<LhDatasourceIdParam> ids) {
        List<LhDatasource> list;
        if (ids == null || ids.isEmpty()) {
            list = this.list(new QueryWrapper<LhDatasource>().lambda()
                    .eq(LhDatasource::getStatus, LhDatasourceStatusEnum.ONLINE.getValue()));
        } else {
            list = new ArrayList<>();
            for (LhDatasourceIdParam p : ids) {
                list.add(queryEntity(p.getId()));
            }
        }
        int projected = 0;
        int skipped = 0;
        int errors = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (LhDatasource ds : list) {
            if (!secAuthGrantService.canUseDatasource(ds.getId())) {
                Map<String, Object> denied = new LinkedHashMap<>();
                denied.put("id", ds.getId());
                denied.put("name", ds.getName());
                denied.put("ok", false);
                denied.put("skipped", true);
                denied.put("message", "无权投影该数据源（须为拥有者或持有 EDIT/MANAGE）");
                details.add(denied);
                skipped++;
                continue;
            }
            if (!LhDatasourceSqlrestProjector.isProjectable(ds.getType())) {
                if (ids != null && !ids.isEmpty()) {
                    Map<String, Object> one = sqlrestProjector.project(ds);
                    details.add(one);
                    skipped++;
                }
                continue;
            }
            Map<String, Object> one = sqlrestProjector.project(ds);
            details.add(one);
            if (Boolean.TRUE.equals(one.get("skipped"))) {
                skipped++;
            } else if (Boolean.TRUE.equals(one.get("ok"))) {
                projected++;
            } else {
                errors++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", details.size());
        out.put("projected", projected);
        out.put("skipped", skipped);
        out.put("errors", errors);
        out.put("details", details);
        out.put("ok", errors == 0);
        return out;
    }

    public LhDatasource queryEntity(String id) {
        LhDatasource ds = this.getById(id);
        if (ds == null) {
            throw new CommonException("??????: {}", id);
        }
        return ds;
    }

    /** 拥有者或 MANAGE grant 方可改删/启停（超管不短路） */
    private LhDatasourceVo enrichVo(LhDatasourceVo vo) {
        userNameResolver.fillDatasource(vo);
        return vo;
    }

    private void assertCanEditDs(LhDatasource ds) {
        secAuthGrantService.assertCanEditDatasource(ds);
    }

    private void assertCanDeleteDs(LhDatasource ds) {
        secAuthGrantService.assertCanDeleteDatasource(ds);
    }

    /** 构建页左树 / 元数据浏览：与 listForSqlrest、build 同口径（拥有者或 EDIT/MANAGE） */
    private void assertCanUseDs(LhDatasource ds) {
        if (ds == null || !secAuthGrantService.canUseDatasource(ds.getId())) {
            throw new CommonException("无权使用该数据源（须为拥有者或持有 EDIT/MANAGE）");
        }
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
            throw new CommonException("????????: {}", dsCode);
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
            // SID ? :??????????? SID?? /
            if (StrUtil.isNotBlank(db) && (db.contains(".") || db.contains("/") || db.toLowerCase().contains("service"))) {
                String svc = db.replace("service:", "").replace("SERVICE:", "");
                return "jdbc:oracle:thin:@//" + host + ":" + port + "/" + svc;
            }
            return "jdbc:oracle:thin:@" + host + ":" + port + ":" + db;
        }
        if ("sqlserver".equals(type)) {
            return "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + db
                    + (StrUtil.isNotBlank(extra) ? ";" + extra.replace("&", ";") : "");
        }
        if ("clickhouse".equals(type)) {
            return "jdbc:clickhouse://" + host + ":" + port + "/" + StrUtil.blankToDefault(db, "default")
                    + (StrUtil.isNotBlank(extra) ? "?" + extra : "");
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
        // JDBC ?? afterPersist ? tableSync ??????????? mock/????????
        LhDatasourceTypeEnum typeEnum = LhDatasourceTypeEnum.of(entity.getType()).orElse(null);
        if (typeEnum != null && typeEnum.isJdbc()) {
            return;
        }
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

    private static boolean isSecretPlaceholder(Object v) {
        if (v == null) {
            return false;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || "******".equals(s)) {
            return true;
        }
        // ?? * ????
        return s.length() >= 4 && s.chars().allMatch(c -> c == '*');
    }

    private static boolean isSecretKey(String k) {
        if (k == null) {
            return false;
        }
        return "password".equalsIgnoreCase(k)
                || "secretKey".equalsIgnoreCase(k)
                || "token".equalsIgnoreCase(k)
                || "privateKey".equalsIgnoreCase(k)
                || "authHeader".equalsIgnoreCase(k);
    }

    private static void stripSecretPlaceholders(Map<String, Object> conn) {
        if (conn == null || conn.isEmpty()) {
            return;
        }
        List<String> drop = new ArrayList<>();
        conn.forEach((k, v) -> {
            if (isSecretKey(k) && isSecretPlaceholder(v)) {
                drop.add(k);
            }
        });
        drop.forEach(conn::remove);
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
