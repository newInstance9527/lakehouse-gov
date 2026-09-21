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
package vip.xiaonuo.lh.modular.schemasync.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.engine.GravitinoClient;
import vip.xiaonuo.lh.core.engine.LhOmCatalogClassifier;
import vip.xiaonuo.lh.core.engine.OpenMetadataClient;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.schemasync.entity.CbGravAssetRef;
import vip.xiaonuo.lh.modular.schemasync.entity.CbOmAssetRef;
import vip.xiaonuo.lh.modular.schemasync.entity.CbSchemaSyncWatermark;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbGravAssetRefMapper;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbOmAssetRefMapper;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbSchemaSyncWatermarkMapper;
import vip.xiaonuo.lh.modular.schemasync.param.LhSchemaSyncRunParam;
import vip.xiaonuo.lh.modular.query.support.LakeQueryAssetBinder;
import vip.xiaonuo.lh.modular.schemasync.service.LhSchemaSyncService;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Grav→OM Schema Sync：按 grav_revision upsert 结构，保留 OM 业务注释
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Service
public class LhSchemaSyncServiceImpl implements LhSchemaSyncService {

    @Resource
    private LhProperties lhProperties;
    @Resource
    private GravitinoClient gravitinoClient;
    @Resource
    private OpenMetadataClient openMetadataClient;
    @Resource
    private CbGravAssetRefMapper gravMapper;
    @Resource
    private CbOmAssetRefMapper omMapper;
    @Resource
    private CbSchemaSyncWatermarkMapper watermarkMapper;
    @Resource
    private GovAssetMapper govAssetMapper;
    @Resource
    private LakeQueryAssetBinder lakeQueryAssetBinder;

    private static final String NOT_DELETE = "NOT_DELETE";

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Map<String, Object> run(LhSchemaSyncRunParam param) {
        if (param == null) {
            param = new LhSchemaSyncRunParam();
        }
        if (!lhProperties.getSchemaSync().isEnabled() && !Boolean.TRUE.equals(param.getForce())) {
            throw new CommonException("Schema Sync 未启用（lh.schema-sync.enabled=false）");
        }
        String metalake = StrUtil.blankToDefault(param.getMetalake(), lhProperties.getGravitino().getMetalake());
        String catalog = StrUtil.blankToDefault(param.getCatalog(), lhProperties.getGravitino().getCatalog());
        String lakeService = StrUtil.blankToDefault(param.getLakeService(),
                lhProperties.getOpenmetadata().getLakeService());
        String lakeDatabase = StrUtil.blankToDefault(param.getLakeDatabase(),
                lhProperties.getOpenmetadata().getLakeDatabase());
        boolean force = Boolean.TRUE.equals(param.getForce());
        LhOmCatalogClassifier.Spec classify = buildClassify(param, lakeService, lakeDatabase);

        // Catalog 不存在 / 非 relational：软跳过，避免对门户「不支持 Grav」类型抛 500
        try {
            gravitinoClient.listSchemas(metalake, catalog);
        } catch (Exception e) {
            String msg = StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName());
            Map<String, Object> soft = new LinkedHashMap<>();
            soft.put("skipped", true);
            soft.put("reason", "catalog_unavailable");
            soft.put("catalog", catalog);
            soft.put("metalake", metalake);
            soft.put("message", msg);
            soft.put("scanned", 0);
            soft.put("upserted", 0);
            soft.put("errors", 0);
            return soft;
        }

        List<String> schemas = resolveSchemas(param.getSchemas(), metalake, catalog);
        int scanned = 0;
        int upserted = 0;
        int skipped = 0;
        int errors = 0;
        List<String> errorMsgs = new ArrayList<>();

        for (String schema : schemas) {
            List<String> tables;
            try {
                tables = gravitinoClient.listTables(metalake, catalog, schema);
            } catch (Exception e) {
                errors++;
                errorMsgs.add(schema + ": listTables " + e.getMessage());
                continue;
            }
            for (String table : tables) {
                scanned++;
                try {
                    GravitinoClient.GravTable grav = gravitinoClient.loadTable(metalake, catalog, schema, table);
                    CbGravAssetRef gravRef = upsertGravRef(grav);
                    lakeQueryAssetBinder.ensure(gravRef);
                    CbOmAssetRef omRef = findOmRef(gravRef.getId());
                    long synced = omRef == null ? -1L : Optional.ofNullable(omRef.getSyncedGravRev()).orElse(-1L);
                    if (!force && omRef != null && synced >= grav.auditVersion
                            && "ok".equals(omRef.getLastSyncStatus())) {
                        skipped++;
                        continue;
                    }
                    // 多 schema 时按实际 schema 挂载；分类规格仅驱动 DatabaseService 类型
                    LhOmCatalogClassifier.Spec tableClassify = copyClassify(classify);
                    tableClassify.schemaName = schema;
                    Map<String, Object> omResult = openMetadataClient.upsertTableStructure(
                            lakeService, lakeDatabase, schema, grav, tableClassify);
                    saveOmRef(gravRef, omRef, omResult, grav.auditVersion, null, tableClassify);
                    upserted++;
                } catch (Exception e) {
                    errors++;
                    String msg = schema + "." + table + ": " + e.getMessage();
                    errorMsgs.add(msg);
                    try {
                        CbGravAssetRef g = gravMapper.selectOne(new QueryWrapper<CbGravAssetRef>().lambda()
                                .eq(CbGravAssetRef::getGravMetalake, metalake)
                                .eq(CbGravAssetRef::getGravCatalog, catalog)
                                .eq(CbGravAssetRef::getGravSchema, schema)
                                .eq(CbGravAssetRef::getGravTable, table));
                        if (g != null) {
                            saveOmRef(g, findOmRef(g.getId()), null, g.getGravRevision(),
                                    StrUtil.sub(msg, 0, 500), classify);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        touchWatermark(metalake + "/" + catalog, String.valueOf(System.currentTimeMillis()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("metalake", metalake);
        result.put("catalog", catalog);
        result.put("lakeService", lakeService);
        result.put("lakeDatabase", lakeDatabase);
        result.put("omServiceType", classify.serviceType);
        result.put("schemas", schemas);
        result.put("scanned", scanned);
        result.put("upserted", upserted);
        result.put("skipped", skipped);
        result.put("errors", errors);
        result.put("errorMsgs", errorMsgs.size() > 20 ? errorMsgs.subList(0, 20) : errorMsgs);
        return result;
    }

    private static LhOmCatalogClassifier.Spec buildClassify(LhSchemaSyncRunParam param,
                                                            String lakeService, String lakeDatabase) {
        LhOmCatalogClassifier.Spec s = new LhOmCatalogClassifier.Spec();
        s.omFamily = LhOmCatalogClassifier.FAMILY_DATABASE;
        s.serviceName = lakeService;
        s.serviceType = StrUtil.blankToDefault(param.getOmServiceType(), "CustomDatabase");
        s.serviceDisplayName = param.getOmServiceDisplayName();
        s.serviceDescription = param.getOmServiceDescription();
        s.databaseName = lakeDatabase;
        s.databaseDisplayName = param.getOmDatabaseDisplayName();
        s.databaseDescription = param.getOmDatabaseDescription();
        return s;
    }

    private static LhOmCatalogClassifier.Spec copyClassify(LhOmCatalogClassifier.Spec src) {
        LhOmCatalogClassifier.Spec s = new LhOmCatalogClassifier.Spec();
        s.omFamily = src.omFamily;
        s.serviceName = src.serviceName;
        s.serviceType = src.serviceType;
        s.serviceDisplayName = src.serviceDisplayName;
        s.serviceDescription = src.serviceDescription;
        s.databaseName = src.databaseName;
        s.databaseDisplayName = src.databaseDisplayName;
        s.databaseDescription = src.databaseDescription;
        s.schemaName = src.schemaName;
        s.category = src.category;
        s.typeCode = src.typeCode;
        return s;
    }

    private List<String> resolveSchemas(String override, String metalake, String catalog) {
        String cfg = StrUtil.blankToDefault(override, lhProperties.getSchemaSync().getSchemas());
        if (StrUtil.isNotBlank(cfg)) {
            return Arrays.stream(cfg.split("[,;\\s]+"))
                    .map(String::trim).filter(StrUtil::isNotBlank).distinct()
                    .collect(Collectors.toList());
        }
        return gravitinoClient.listSchemas(metalake, catalog);
    }

    private CbGravAssetRef upsertGravRef(GravitinoClient.GravTable grav) {
        CbGravAssetRef existing = gravMapper.selectOne(new QueryWrapper<CbGravAssetRef>().lambda()
                .eq(CbGravAssetRef::getGravMetalake, grav.metalake)
                .eq(CbGravAssetRef::getGravCatalog, grav.catalog)
                .eq(CbGravAssetRef::getGravSchema, grav.schema)
                .eq(CbGravAssetRef::getGravTable, grav.name));
        List<Map<String, Object>> cols = new ArrayList<>();
        for (GravitinoClient.GravColumn c : grav.columns) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name);
            m.put("type", c.type);
            m.put("nullable", c.nullable);
            cols.add(m);
        }
        Date now = new Date();
        if (existing == null) {
            existing = new CbGravAssetRef();
            existing.setId(IdUtil.getSnowflakeNextIdStr());
            existing.setRevision(1);
            existing.setStatus("active");
            existing.setWs("default");
            existing.setGravMetalake(grav.metalake);
            existing.setGravCatalog(grav.catalog);
            existing.setGravSchema(grav.schema);
            existing.setGravTable(grav.name);
            existing.setGravRevision(grav.auditVersion);
            existing.setLocationUri(grav.location);
            existing.setColumnsJson(JSONUtil.toJsonStr(cols));
            existing.setDriftFlag(0);
            existing.setLastSeenAt(now);
            gravMapper.insert(existing);
        } else {
            existing.setGravRevision(grav.auditVersion);
            existing.setLocationUri(grav.location);
            existing.setColumnsJson(JSONUtil.toJsonStr(cols));
            existing.setStatus("active");
            existing.setLastSeenAt(now);
            existing.setRevision(Optional.ofNullable(existing.getRevision()).orElse(1) + 1);
            gravMapper.updateById(existing);
        }
        return existing;
    }

    private CbOmAssetRef findOmRef(String gravAssetId) {
        return omMapper.selectOne(new QueryWrapper<CbOmAssetRef>().lambda()
                .eq(CbOmAssetRef::getGravAssetId, gravAssetId));
    }

    private void saveOmRef(CbGravAssetRef gravRef, CbOmAssetRef omRef, Map<String, Object> omResult,
                           long gravRev, String error, LhOmCatalogClassifier.Spec classify) {
        Date now = new Date();
        String fqn = omResult != null ? String.valueOf(omResult.getOrDefault("fqn",
                gravRef.getGravSchema() + "." + gravRef.getGravTable()))
                : gravRef.getGravSchema() + "." + gravRef.getGravTable();
        // 按 om_fqn 去重：目录 upsertOmPointer 可能已写过一行
        if (omRef == null && StrUtil.isNotBlank(fqn)) {
            omRef = omMapper.selectOne(new QueryWrapper<CbOmAssetRef>().lambda()
                    .eq(CbOmAssetRef::getOmFqn, fqn)
                    .last("LIMIT 1"));
        }
        if (omRef == null) {
            omRef = new CbOmAssetRef();
            omRef.setId(IdUtil.getSnowflakeNextIdStr());
            omRef.setRevision(1);
            omRef.setWs("default");
            omRef.setGravAssetId(gravRef.getId());
            omRef.setOmFqn(fqn);
            omRef.setSyncedGravRev(0L);
            omRef.setOmEntityType("table");
            if (classify != null) {
                omRef.setOmFamily(classify.omFamily);
                omRef.setOmServiceType(classify.serviceType);
            }
            omMapper.insert(omRef);
        }
        omRef.setGravAssetId(gravRef.getId());
        omRef.setOmEntityType(StrUtil.blankToDefault(omRef.getOmEntityType(), "table"));
        if (classify != null) {
            omRef.setOmFamily(classify.omFamily);
            omRef.setOmServiceType(classify.serviceType);
        }
        if (error != null) {
            omRef.setStatus("stale");
            omRef.setLastSyncStatus("error");
            omRef.setLastError(error);
            omRef.setDriftFlag(1);
        } else if (omResult != null) {
            omRef.setStatus("active");
            omRef.setOmFqn(String.valueOf(omResult.getOrDefault("fqn", omRef.getOmFqn())));
            omRef.setOmTableId(str(omResult.get("omTableId")));
            omRef.setOmRevision(str(omResult.get("omRevision")));
            omRef.setSyncedGravRev(gravRev);
            omRef.setLastSyncStatus("ok");
            omRef.setLastError(null);
            omRef.setDriftFlag(0);
        }
        linkGovAsset(omRef);
        omRef.setLastSyncAt(now);
        omRef.setRevision(Optional.ofNullable(omRef.getRevision()).orElse(1) + 1);
        omMapper.updateById(omRef);
    }

    /** 按 om_fqn 反查门户资产并回填双向指针 */
    private void linkGovAsset(CbOmAssetRef omRef) {
        if (omRef == null || StrUtil.isBlank(omRef.getOmFqn())) {
            return;
        }
        try {
            GovAsset asset = null;
            if (StrUtil.isNotBlank(omRef.getAssetId())) {
                asset = govAssetMapper.selectById(omRef.getAssetId());
            }
            if (asset == null) {
                asset = govAssetMapper.selectOne(new QueryWrapper<GovAsset>().lambda()
                        .eq(GovAsset::getOmFqn, omRef.getOmFqn())
                        .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                        .last("LIMIT 1"));
            }
            if (asset == null) {
                return;
            }
            omRef.setAssetId(asset.getId());
            boolean dirty = false;
            if (!Objects.equals(asset.getOmAssetId(), omRef.getId())) {
                asset.setOmAssetId(omRef.getId());
                dirty = true;
            }
            if (StrUtil.isBlank(asset.getOmFqn())) {
                asset.setOmFqn(omRef.getOmFqn());
                dirty = true;
            }
            if (StrUtil.isNotBlank(omRef.getGravAssetId())
                    && !Objects.equals(asset.getGravAssetId(), omRef.getGravAssetId())) {
                asset.setGravAssetId(omRef.getGravAssetId());
                dirty = true;
            }
            if (dirty) {
                asset.setRevision(asset.getRevision() == null ? 1 : asset.getRevision() + 1);
                govAssetMapper.updateById(asset);
            }
        } catch (Exception ignored) {
            // soft-fail：Schema Sync 不因门户回填失败中断
        }
    }

    private void touchWatermark(String markKey, String markValue) {
        CbSchemaSyncWatermark wm = watermarkMapper.selectOne(new QueryWrapper<CbSchemaSyncWatermark>().lambda()
                .eq(CbSchemaSyncWatermark::getSourceSystem, "gravitino")
                .eq(CbSchemaSyncWatermark::getMarkKey, markKey));
        Date now = new Date();
        if (wm == null) {
            wm = new CbSchemaSyncWatermark();
            wm.setId(IdUtil.getSnowflakeNextIdStr());
            wm.setSourceSystem("gravitino");
            wm.setMarkKey(markKey);
            wm.setMarkValue(markValue);
            wm.setUpdateTime(now);
            watermarkMapper.insert(wm);
        } else {
            wm.setMarkValue(markValue);
            wm.setUpdateTime(now);
            watermarkMapper.updateById(wm);
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
