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
package vip.xiaonuo.lh.modular.catalog.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
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
import vip.xiaonuo.lh.config.LhProperties;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;
import vip.xiaonuo.lh.core.engine.GravitinoClient;
import vip.xiaonuo.lh.core.engine.LhOmCatalogClassifier;
import vip.xiaonuo.lh.core.engine.OpenMetadataClient;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.entity.GovAssetSourceLink;
import vip.xiaonuo.lh.modular.catalog.enums.GovAssetLayerEnum;
import vip.xiaonuo.lh.modular.catalog.enums.GovAssetStatusEnum;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetSourceLinkMapper;
import vip.xiaonuo.lh.modular.catalog.param.GovAssetAddParam;
import vip.xiaonuo.lh.modular.catalog.param.GovAssetEditParam;
import vip.xiaonuo.lh.modular.catalog.param.GovAssetIdParam;
import vip.xiaonuo.lh.modular.catalog.param.GovAssetMetaParam;
import vip.xiaonuo.lh.modular.catalog.param.GovAssetPageParam;
import vip.xiaonuo.lh.modular.catalog.param.GovAssetPreviewParam;
import vip.xiaonuo.lh.modular.catalog.preview.GovAssetPreviewContext;
import vip.xiaonuo.lh.modular.catalog.preview.GovAssetPreviewRouter;
import vip.xiaonuo.lh.modular.catalog.preview.PreviewAdapterSupport;
import vip.xiaonuo.lh.modular.catalog.result.GovAssetSourceVo;
import vip.xiaonuo.lh.modular.catalog.result.GovAssetVo;
import vip.xiaonuo.lh.modular.catalog.service.GovAssetService;
import vip.xiaonuo.lh.modular.catalog.support.GovAssetCrossModuleExtras;
import vip.xiaonuo.lh.modular.catalog.support.GovAssetGoldTagSupport;
import vip.xiaonuo.lh.modular.catalog.support.GovAssetMetaDriftReconcile;
import vip.xiaonuo.lh.modular.catalog.support.GovAssetSourceReconcile;
import vip.xiaonuo.lh.modular.datasource.discover.LhInventoryObjectKinds;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;
import vip.xiaonuo.lh.modular.datasource.entity.LhDsTable;
import vip.xiaonuo.lh.modular.datasource.enums.LhDatasourceTypeEnum;
import vip.xiaonuo.lh.modular.datasource.mapper.LhDatasourceMapper;
import vip.xiaonuo.lh.modular.datasource.mapper.LhDsTableMapper;
import vip.xiaonuo.lh.modular.datasource.service.LhDatasourceGravitinoProjector;
import vip.xiaonuo.lh.modular.datasource.service.LhPortalInventoryOmBridge;
import vip.xiaonuo.lh.modular.datasource.support.LhIcebergNamespaceNames;
import vip.xiaonuo.lh.modular.plat.service.PlatOutboxService;
import vip.xiaonuo.lh.modular.schemasync.entity.CbGravAssetRef;
import vip.xiaonuo.lh.modular.schemasync.entity.CbOmAssetRef;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbGravAssetRefMapper;
import vip.xiaonuo.lh.modular.schemasync.mapper.CbOmAssetRefMapper;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 资产目录 Service 实现（P0：门户登记 + 源绑定；OM 对齐 soft-fail）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Slf4j
@Service
public class GovAssetServiceImpl extends ServiceImpl<GovAssetMapper, GovAsset> implements GovAssetService {

    private static final String WS_DEFAULT = "default";
    private static final String LINK_PRIMARY = "primary";
    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private GovAssetSourceLinkMapper linkMapper;
    @Resource
    private LhDatasourceMapper datasourceMapper;
    @Resource
    private LhDsTableMapper dsTableMapper;
    @Resource
    private LhPortalInventoryOmBridge portalInventoryOmBridge;
    @Resource
    private LhDatasourceGravitinoProjector gravitinoProjector;
    @Resource
    private CbOmAssetRefMapper omAssetRefMapper;
    @Resource
    private CbGravAssetRefMapper gravAssetRefMapper;
    @Resource
    private GravitinoClient gravitinoClient;
    @Resource
    private OpenMetadataClient openMetadataClient;
    @Resource
    private vip.xiaonuo.lh.modular.sec.service.SecAuthGrantService secAuthGrantService;
    @Resource
    private vip.xiaonuo.lh.modular.sec.service.LhTrinoPrincipalService trinoPrincipalService;
    @Resource
    private LhProperties lhProperties;
    @Resource
    private GovAssetCrossModuleExtras crossModuleExtras;
    @Resource
    private PlatOutboxService platOutboxService;
    @Resource
    private GovAssetSourceReconcile govAssetSourceReconcile;
    @Resource
    private GovAssetMetaDriftReconcile govAssetMetaDriftReconcile;
    @Resource
    private GovAssetPreviewRouter previewRouter;
    @Resource
    private vip.xiaonuo.lh.core.user.LhUserNameResolver userNameResolver;

    @Override
    public Page<GovAssetVo> page(GovAssetPageParam param) {
        QueryWrapper<GovAsset> qw = new QueryWrapper<GovAsset>().checkSqlInjection();
        qw.lambda().eq(GovAsset::getDeleteFlag, NOT_DELETE);
        // 空间优先：缺省 scope=workspace；enterprise=已发布共享层；all=特权巡检
        applyListScope(qw, param);
        String ws = StrUtil.trim(param.getWs());

        String layer = param.getLayer();
        if (StrUtil.isNotBlank(layer)) {
            qw.lambda().eq(GovAsset::getLayer, layer.trim().toLowerCase(Locale.ROOT));
        }
        String domain = firstNonBlank(param.getDomain(), param.getDomainCode());
        if (StrUtil.isNotBlank(domain)) {
            qw.lambda().eq(GovAsset::getDomainCode, domain.trim().toLowerCase(Locale.ROOT));
        }
        String kind = firstNonBlank(param.getKind(), param.getAssetKind());
        if (StrUtil.isNotBlank(kind)) {
            qw.lambda().eq(GovAsset::getAssetKind, kind.trim().toLowerCase(Locale.ROOT));
        }
        if (StrUtil.isNotBlank(param.getStatus())) {
            qw.lambda().eq(GovAsset::getStatus, param.getStatus().trim().toLowerCase(Locale.ROOT));
        }

        Set<String> filterAssetIds = resolveAssetIdsBySource(param.getDsId(), param.getSource());
        if (filterAssetIds != null) {
            if (filterAssetIds.isEmpty()) {
                Page<GovAssetVo> empty = new Page<>(1, 10, 0);
                empty.setRecords(List.of());
                return empty;
            }
            qw.lambda().in(GovAsset::getId, filterAssetIds);
        }

        String q = firstNonBlank(param.getQ(), param.getKeyword());
        if (StrUtil.isNotBlank(q)) {
            String kw = q.trim();
            qw.lambda().and(w -> w.like(GovAsset::getAssetCode, kw)
                    .or().like(GovAsset::getName, kw)
                    .or().like(GovAsset::getCnName, kw)
                    .or().like(GovAsset::getDescription, kw)
                    .or().like(GovAsset::getTechOwner, kw)
                    .or().like(GovAsset::getOmFqn, kw));
        }

        if (StrUtil.isNotBlank(param.getSortField())) {
            CommonSortOrderEnum.validate(param.getSortOrder());
            boolean asc = CommonSortOrderEnum.ASC.getValue().equalsIgnoreCase(param.getSortOrder());
            qw.orderBy(true, asc, StrUtil.toUnderlineCase(param.getSortField()));
        } else {
            qw.lambda().orderByDesc(GovAsset::getCreateTime).orderByDesc(GovAsset::getId);
        }

        Page<GovAsset> raw = this.page(CommonPageRequest.defaultPage(), qw);
        Page<GovAssetVo> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<GovAsset> records = raw.getRecords() == null ? List.of() : raw.getRecords();
        Map<String, List<GovAssetSourceLink>> linksByAsset = loadPrimaryLinks(
                records.stream().map(GovAsset::getId).toList());
        Map<String, LhDatasource> dsMap = loadDatasources(
                linksByAsset.values().stream().flatMap(List::stream).map(GovAssetSourceLink::getDsId).toList());
        List<GovAssetVo> vos = records.stream()
                .map(a -> toVo(a, linksByAsset.get(a.getId()), dsMap, false))
                .toList();
        Map<String, GovAssetCrossModuleExtras.AssetScoreKey> scoreKeys = new LinkedHashMap<>();
        for (GovAssetVo vo : vos) {
            scoreKeys.put(vo.getId(), new GovAssetCrossModuleExtras.AssetScoreKey(vo.getObjectName(), vo.getOmFqn()));
        }
        Map<String, java.math.BigDecimal> scores = crossModuleExtras.batchScores(ws, scoreKeys);
        for (GovAssetVo vo : vos) {
            vo.setQualityScore(scores.get(vo.getId()));
        }
        userNameResolver.fillAssets(vos);
        out.setRecords(vos);
        return out;
    }

    @Override
    public GovAssetVo detail(GovAssetIdParam param) {
        GovAsset asset = requireAsset(param.getId());
        List<GovAssetSourceLink> links = linkMapper.selectList(new QueryWrapper<GovAssetSourceLink>().lambda()
                .eq(GovAssetSourceLink::getAssetId, asset.getId())
                .eq(GovAssetSourceLink::getDeleteFlag, NOT_DELETE)
                .orderByAsc(GovAssetSourceLink::getLinkRole));
        Map<String, LhDatasource> dsMap = loadDatasources(
                links.stream().map(GovAssetSourceLink::getDsId).toList());
        GovAssetVo vo = toVo(asset, links, dsMap, true);
        String objectName = vo.getObjectName();
        Map<String, Object> extras = new LinkedHashMap<>();
        Map<String, Object> schema = loadSchemaInternal(asset, links, dsMap);
        extras.put("schema", schema);
        extras.put("omMeta", loadOmMetaInternal(asset));
        extras.put("quality", crossModuleExtras.buildQuality(asset, objectName));
        extras.put("lineage", crossModuleExtras.buildLineage(asset, objectName));
        extras.put("standard", crossModuleExtras.buildStandard(asset, objectName, schema));
        extras.put("lifecycle", crossModuleExtras.buildLifecycle(asset, objectName));
        extras.put("gold", buildGoldExtra(asset, extras.get("omMeta")));
        extras.put("drift", buildDriftExtra(asset));
        extras.put("omMetaWrite", Map.of(
                "available", true,
                "api", "POST /lh/catalog/assets/meta"));
        vo.setExtras(extras);
        userNameResolver.fillAsset(vo);
        return vo;
    }

    @Override
    public Map<String, Object> schema(GovAssetIdParam param) {
        GovAsset asset = requireAsset(param.getId());
        List<GovAssetSourceLink> links = linkMapper.selectList(new QueryWrapper<GovAssetSourceLink>().lambda()
                .eq(GovAssetSourceLink::getAssetId, asset.getId())
                .eq(GovAssetSourceLink::getDeleteFlag, NOT_DELETE));
        Map<String, LhDatasource> dsMap = loadDatasources(
                links.stream().map(GovAssetSourceLink::getDsId).toList());
        return loadSchemaInternal(asset, links, dsMap);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateMeta(GovAssetMetaParam param) {
        GovAsset asset = requireAsset(param.getId());
        secAuthGrantService.assertCanEditAsset(asset);
        if (StrUtil.isBlank(asset.getOmFqn())) {
            throw new CommonException("资产尚未对齐 OM（无 omFqn），请先 refresh");
        }
        String entityType = resolveOmEntityType(asset);
        List<String> goldTagFqns = configuredGoldTagFqns();
        List<String> tagsToWrite = param.getTags();
        if (param.getGold() != null) {
            List<String> base = tagsToWrite;
            if (base == null) {
                // 未传 tags：先读现有 OM tags 再增删金标
                try {
                    JSONObject ent = openMetadataClient.getCatalogEntity(entityType, asset.getOmFqn());
                    base = GovAssetGoldTagSupport.extractTagFqns(ent == null ? null : ent.get("tags"));
                } catch (Exception e) {
                    base = new ArrayList<>();
                }
            }
            tagsToWrite = GovAssetGoldTagSupport.applyGoldFlag(base, Boolean.TRUE.equals(param.getGold()), goldTagFqns);
        }
        boolean touching = param.getDescription() != null
                || param.getDisplayName() != null
                || tagsToWrite != null;
        if (!touching) {
            throw new CommonException("请至少提供 description / displayName / tags / gold 之一");
        }

        Map<String, Object> before = snapshotMetaAudit(asset);
        Map<String, Object> om = openMetadataClient.patchEntityMeta(
                entityType, asset.getOmFqn(),
                param.getDescription(), param.getDisplayName(), tagsToWrite);
        if (Boolean.TRUE.equals(param.getSyncPortalDraft()) && param.getDescription() != null) {
            asset.setDescription(param.getDescription());
        }
        boolean syncGold = param.getSyncGold() == null || Boolean.TRUE.equals(param.getSyncGold());
        Map<String, Object> gold = null;
        if (syncGold) {
            List<String> afterFqns = tagsToWrite != null
                    ? tagsToWrite
                    : GovAssetGoldTagSupport.extractTagFqns(om.get("tags"));
            gold = applyGoldFromOm(asset, afterFqns, goldTagFqns);
        }
        if ((Boolean.TRUE.equals(param.getSyncPortalDraft()) && param.getDescription() != null)
                || (gold != null && Boolean.TRUE.equals(gold.get("portalUpdated")))) {
            asset.setRevision(asset.getRevision() == null ? 1 : asset.getRevision() + 1);
            this.updateById(asset);
            if (Boolean.TRUE.equals(param.getSyncPortalDraft()) && param.getDescription() != null) {
                om.put("portalDraftSynced", true);
            }
        }
        om.put("assetId", asset.getId());
        om.put("assetCode", asset.getAssetCode());
        if (gold != null) {
            om.put("gold", gold);
        }
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("before", before);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("description", om.get("description"));
        after.put("displayName", om.get("displayName"));
        after.put("tags", om.get("tags") != null ? om.get("tags") : tagsToWrite);
        after.put("isGold", asset.getIsGold());
        auditPayload.put("after", after);
        auditPayload.put("omFqn", asset.getOmFqn());
        auditPayload.put("entityType", entityType);
        String eventId = platOutboxService.appendSoft(
                "catalog.asset.om_meta.updated",
                "gov_asset",
                asset.getId(),
                auditPayload,
                Map.of("source", "POST /lh/catalog/assets/meta"));
        if (eventId != null) {
            om.put("auditEventId", eventId);
        }
        return om;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovAssetVo add(GovAssetAddParam param) {
        LhDatasource ds = datasourceMapper.selectById(param.getDsId());
        if (ds == null) {
            throw new CommonException("数据源不存在: {}", param.getDsId());
        }
        GovAssetLayerEnum.validate(param.getLayer());
        String domain = param.getDomain().trim().toLowerCase(Locale.ROOT);
        String objectName = param.getObjectName().trim();
        String ws = StrUtil.blankToDefault(param.getWs(), WS_DEFAULT);

        // 同一源对象 primary 不可重复
        Long dupLink = linkMapper.selectCount(new QueryWrapper<GovAssetSourceLink>().lambda()
                .eq(GovAssetSourceLink::getDsId, ds.getId())
                .eq(GovAssetSourceLink::getObjectName, objectName)
                .eq(GovAssetSourceLink::getLinkRole, LINK_PRIMARY)
                .eq(GovAssetSourceLink::getDeleteFlag, NOT_DELETE));
        if (dupLink != null && dupLink > 0) {
            throw new CommonException("该源对象已注册为资产（primary）: {}.{}", ds.getName(), objectName);
        }

        LhDsTable tableRow = dsTableMapper.selectOne(new QueryWrapper<LhDsTable>().lambda()
                .eq(LhDsTable::getDsId, ds.getId())
                .eq(LhDsTable::getTableName, objectName)
                .last("LIMIT 1"));
        boolean allowManual = lhProperties.getCatalog() != null
                && lhProperties.getCatalog().isAllowManualObjectName();
        if (tableRow == null && !allowManual) {
            throw new CommonException("源对象不在表清单，请先同步 ig_ds_table: {}.{}", ds.getName(), objectName);
        }
        String objectKind = LhInventoryObjectKinds.ofType(ds.getType());
        if (tableRow != null && StrUtil.isNotBlank(tableRow.getEngine())
                && "kafka".equalsIgnoreCase(tableRow.getEngine())) {
            objectKind = LhInventoryObjectKinds.TOPIC;
        }

        String assetKind = StrUtil.blankToDefault(param.getAssetKind(), objectKind).toLowerCase(Locale.ROOT);
        boolean autoCode = StrUtil.isBlank(param.getAssetCode());
        String assetCode = autoCode
                ? buildAssetCode(param.getLayer(), domain, objectName, ds)
                : sanitizeCode(param.getAssetCode());
        if (autoCode) {
            assetCode = allocateUniqueAssetCode(ws, assetCode);
        } else if (assetCodeExists(ws, assetCode)) {
            throw new CommonException("资产编码已存在: {}", assetCode);
        }

        String name = StrUtil.blankToDefault(param.getName(), shortName(objectName));
        String cnName = firstNonBlank(param.getCnName(),
                tableRow == null ? null : tableRow.getCnName());
        String desc = firstNonBlank(param.getDescription(),
                tableRow == null ? null : tableRow.getCommentTxt());
        String engine = firstNonBlank(param.getEngine(),
                tableRow == null ? null : tableRow.getEngine(),
                inferEngine(ds.getType(), assetKind));

        GovAsset asset = new GovAsset();
        asset.setId(IdUtil.getSnowflakeNextIdStr());
        asset.setRevision(1);
        asset.setStatus(GovAssetStatusEnum.ACTIVE.getValue());
        asset.setWs(ws);
        asset.setVisibility("private_ws");
        asset.setShareStatus("none");
        asset.setRemark(param.getRemark());
        asset.setAssetCode(assetCode);
        asset.setName(name);
        asset.setCnName(cnName);
        asset.setDescription(desc);
        asset.setAssetKind(assetKind);
        asset.setLayer(param.getLayer().trim().toLowerCase(Locale.ROOT));
        asset.setDomainCode(domain);
        asset.setSensitivity(StrUtil.blankToDefault(param.getSensitivity(), "internal"));
        String userId = LhLoginUsers.requireUserId();
        asset.setTechOwner(StrUtil.blankToDefault(param.getTechOwner(), userId));
        asset.setBizOwner(param.getBizOwner());
        asset.setCreateUser(userId);
        asset.setEngine(engine);
        asset.setIsGold(0);
        asset.setLastSyncStatus("never");
        asset.setDeleteFlag(NOT_DELETE);
        this.save(asset);

        GovAssetSourceLink link = new GovAssetSourceLink();
        link.setId(IdUtil.getSnowflakeNextIdStr());
        link.setRevision(1);
        link.setStatus("active");
        link.setWs(ws);
        link.setAssetId(asset.getId());
        link.setDsId(ds.getId());
        link.setDsCode(ds.getDsCode());
        link.setObjectName(objectName);
        link.setObjectKind(objectKind);
        link.setDsTableId(tableRow == null ? null : tableRow.getId());
        link.setLinkRole(LINK_PRIMARY);
        link.setDeleteFlag(NOT_DELETE);
        if (tableRow == null && allowManual) {
            link.setRemark("manual_object");
        }
        linkMapper.insert(link);

        // 可选：回写源上展示用关联名（非 SoT）
        if (StrUtil.isBlank(ds.getAssetName())) {
            ds.setAssetName(asset.getAssetCode());
            datasourceMapper.updateById(ds);
        }

        // soft-fail：登记后尝试门户清单→OM
        try {
            softRefreshOm(asset, ds);
        } catch (Exception e) {
            log.warn("Asset register OM soft-fail id={}: {}", asset.getId(), e.getMessage());
        }
        // soft-fail：可投影类型挂接 Grav 指针，否则即席 schema-tree 永久为空（仅查面 iceberg，ds_* 不进树）
        try {
            if (gravitinoProjector.supportsGravitino(ds.getType())) {
                Map<String, Object> grav = upsertGravBridge(asset, List.of(link));
                if (!Boolean.TRUE.equals(grav.get("ok"))) {
                    log.warn("Asset register Grav soft-skip id={} reason={}",
                            asset.getId(), grav.getOrDefault("reason", grav.get("message")));
                }
            }
        } catch (Exception e) {
            log.warn("Asset register Grav soft-fail id={}: {}", asset.getId(), e.getMessage());
        }

        GovAssetIdParam idParam = new GovAssetIdParam();
        idParam.setId(asset.getId());
        return detail(idParam);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovAssetVo edit(GovAssetEditParam param) {
        GovAsset asset = requireAsset(param.getId());
        secAuthGrantService.assertCanEditAsset(asset);
        if (param.getRevision() != null && !param.getRevision().equals(asset.getRevision())) {
            throw new CommonException("资产已被他人修改，请刷新后重试");
        }
        if (StrUtil.isNotBlank(param.getLayer())) {
            GovAssetLayerEnum.validate(param.getLayer());
            asset.setLayer(param.getLayer().trim().toLowerCase(Locale.ROOT));
        }
        if (StrUtil.isNotBlank(param.getDomain())) {
            asset.setDomainCode(param.getDomain().trim().toLowerCase(Locale.ROOT));
        }
        if (param.getName() != null) {
            asset.setName(param.getName());
        }
        if (param.getCnName() != null) {
            asset.setCnName(param.getCnName());
        }
        if (param.getDescription() != null) {
            asset.setDescription(param.getDescription());
        }
        if (param.getTechOwner() != null) {
            asset.setTechOwner(param.getTechOwner());
        }
        if (param.getBizOwner() != null) {
            asset.setBizOwner(param.getBizOwner());
        }
        if (param.getSensitivity() != null) {
            asset.setSensitivity(param.getSensitivity());
        }
        if (param.getEngine() != null) {
            asset.setEngine(param.getEngine());
        }
        if (param.getIsGold() != null) {
            asset.setIsGold(param.getIsGold());
        }
        if (StrUtil.isNotBlank(param.getStatus())) {
            GovAssetStatusEnum.of(param.getStatus())
                    .orElseThrow(() -> new CommonException("不支持的状态: {}", param.getStatus()));
            asset.setStatus(param.getStatus().trim().toLowerCase(Locale.ROOT));
        }
        if (param.getRemark() != null) {
            asset.setRemark(param.getRemark());
        }
        asset.setRevision(asset.getRevision() == null ? 1 : asset.getRevision() + 1);
        this.updateById(asset);

        GovAssetIdParam idParam = new GovAssetIdParam();
        idParam.setId(asset.getId());
        return detail(idParam);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> delete(GovAssetIdParam param) {
        GovAsset asset = requireAsset(param.getId());
        secAuthGrantService.assertCanDeleteAsset(asset);
        List<GovAssetSourceLink> links = linkMapper.selectList(new QueryWrapper<GovAssetSourceLink>().lambda()
                .eq(GovAssetSourceLink::getAssetId, asset.getId())
                .eq(GovAssetSourceLink::getDeleteFlag, NOT_DELETE));
        for (GovAssetSourceLink link : links) {
            linkMapper.deleteById(link.getId());
        }
        // 释放唯一键，便于同编码重建
        String code = StrUtil.blankToDefault(asset.getAssetCode(), asset.getId());
        String freed = code + "__del_" + asset.getId();
        if (freed.length() > 128) {
            freed = freed.substring(0, 128);
        }
        asset.setAssetCode(freed);
        asset.setStatus(GovAssetStatusEnum.ARCHIVED.getValue());
        asset.setRevision(asset.getRevision() == null ? 1 : asset.getRevision() + 1);
        this.updateById(asset);
        this.removeById(asset.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", asset.getId());
        out.put("deleted", true);
        out.put("linkCount", links.size());
        return out;
    }

    @Override
    public List<GovAssetSourceVo> sources(GovAssetIdParam param) {
        GovAsset asset = requireAsset(param.getId());
        List<GovAssetSourceLink> links = linkMapper.selectList(new QueryWrapper<GovAssetSourceLink>().lambda()
                .eq(GovAssetSourceLink::getAssetId, asset.getId())
                .eq(GovAssetSourceLink::getDeleteFlag, NOT_DELETE));
        Map<String, LhDatasource> dsMap = loadDatasources(
                links.stream().map(GovAssetSourceLink::getDsId).toList());
        return links.stream().map(l -> toSourceVo(l, dsMap.get(l.getDsId()))).toList();
    }

    @Override
    public Map<String, Object> refresh(GovAssetIdParam param) {
        GovAsset asset = requireAsset(param.getId());
        secAuthGrantService.assertCanEditAsset(asset);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("assetId", asset.getId());
        r.put("assetCode", asset.getAssetCode());
        asset.setStatus(GovAssetStatusEnum.SYNCING.getValue());
        this.updateById(asset);

        List<GovAssetSourceLink> links = linkMapper.selectList(new QueryWrapper<GovAssetSourceLink>().lambda()
                .eq(GovAssetSourceLink::getAssetId, asset.getId())
                .eq(GovAssetSourceLink::getDeleteFlag, NOT_DELETE));
        List<Map<String, Object>> omResults = new ArrayList<>();
        int ok = 0;
        for (GovAssetSourceLink link : links) {
            LhDatasource ds = datasourceMapper.selectById(link.getDsId());
            if (ds == null) {
                continue;
            }
            try {
                Map<String, Object> one = softRefreshOm(asset, ds);
                omResults.add(one);
                if (Boolean.TRUE.equals(one.get("ok"))) {
                    ok++;
                }
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("dsId", ds.getId());
                err.put("ok", false);
                err.put("error", e.getMessage());
                omResults.add(err);
            }
        }
        asset = this.getById(asset.getId());
        Map<String, Object> sourceReconcile = Map.of();
        try {
            sourceReconcile = govAssetSourceReconcile.reconcileAsset(asset);
            asset = this.getById(asset.getId());
        } catch (Exception e) {
            sourceReconcile = Map.of("ok", false, "error", StrUtil.blankToDefault(e.getMessage(), "error"));
        }
        boolean sourceStale = Boolean.TRUE.equals(sourceReconcile.get("linkUpdated"))
                || Boolean.TRUE.equals(sourceReconcile.get("assetDegraded"))
                || "stale".equalsIgnoreCase(asset.getLastSyncStatus());
        asset.setLastSyncAt(new Date());
        if (sourceStale) {
            asset.setLastSyncStatus("stale");
            if (!GovAssetStatusEnum.ARCHIVED.getValue().equalsIgnoreCase(asset.getStatus())) {
                asset.setStatus(GovAssetStatusEnum.DEGRADED.getValue());
            }
        } else {
            asset.setLastSyncStatus(ok > 0 ? "ok" : "error");
            asset.setStatus(ok > 0 ? GovAssetStatusEnum.ACTIVE.getValue() : GovAssetStatusEnum.DEGRADED.getValue());
        }
        Map<String, Object> gold = reconcileGoldOnRefresh(asset);
        Map<String, Object> bridge = softBridgeOnRefresh(asset, links);
        Map<String, Object> metaDrift = Map.of();
        try {
            asset = this.getById(asset.getId());
            metaDrift = govAssetMetaDriftReconcile.reconcileAsset(asset);
            asset = this.getById(asset.getId());
        } catch (Exception e) {
            metaDrift = Map.of("ok", false, "error", StrUtil.blankToDefault(e.getMessage(), "error"));
        }
        asset = this.getById(asset.getId());
        asset.setRevision(asset.getRevision() == null ? 1 : asset.getRevision() + 1);
        this.updateById(asset);

        r.put("ok", ok > 0 && !sourceStale);
        r.put("portalOm", omResults);
        r.put("gold", gold);
        r.put("bridge", bridge);
        r.put("sourceReconcile", sourceReconcile);
        r.put("metaDrift", metaDrift);
        r.put("lineage", Map.of("available", true, "api", "/lh/lineage/graph"));
        r.put("quality", Map.of("available", true, "api", "/lh/quality/overview"));
        r.put("status", asset.getStatus());
        r.put("omFqn", asset.getOmFqn());
        r.put("gravAssetId", asset.getGravAssetId());
        r.put("omAssetId", asset.getOmAssetId());
        return r;
    }

    @Override
    public Map<String, Object> preview(GovAssetPreviewParam param) {
        Map<String, Object> base = new LinkedHashMap<>();
        GovAsset asset = requireAsset(param.getId());
        base.put("assetId", asset.getId());
        base.put("assetCode", asset.getAssetCode());
        boolean authorized = secAuthGrantService.hasTableReadGrant(asset.getId());
        int limit = param.getLimit() == null ? 20 : Math.max(1, Math.min(100, param.getLimit()));
        List<GovAssetSourceLink> links = linkMapper.selectList(new QueryWrapper<GovAssetSourceLink>().lambda()
                .eq(GovAssetSourceLink::getAssetId, asset.getId())
                .eq(GovAssetSourceLink::getDeleteFlag, NOT_DELETE));
        Map<String, LhDatasource> dsMap = loadDatasources(
                links.stream().map(GovAssetSourceLink::getDsId).toList());
        GovAssetSourceLink primary = links == null || links.isEmpty() ? null : links.stream()
                .filter(l -> LINK_PRIMARY.equals(l.getLinkRole()))
                .findFirst().orElse(links.get(0));
        LhDatasource primaryDs = primary == null || dsMap == null ? null : dsMap.get(primary.getDsId());
        String objectName = resolvePreviewObjectName(primary, links, asset);
        GovAssetPreviewContext ctx = GovAssetPreviewContext.builder()
                .asset(asset)
                .links(links)
                .primaryLink(primary)
                .primaryDs(primaryDs)
                .objectName(objectName)
                .limit(limit)
                .base(base)
                .build();
        boolean lake = PreviewAdapterSupport.isLakeSource(ctx);
        if (lake) {
            if (trinoPrincipalService.findActive(LhLoginUsers.requireUserId()) == null) {
                base.put("ok", false);
                base.put("source", "denied");
                base.put("message", "门户账号未映射 Gravitino 主体，不能预览湖表。表权限由 Gravitino 裁决，不使用服务账号");
                base.put("needPrincipal", true);
                base.put("columns", List.of());
                base.put("rows", List.of());
                base.put("rowCount", 0);
                return base;
            }
        } else if (!authorized) {
            base.put("ok", false);
            base.put("source", "denied");
            base.put("message", "看见≠能查：非资产拥有者且无表级读授权，请走申请中心；目录接口不代查生产数据");
            base.put("needApply", true);
            base.put("columns", List.of());
            base.put("rows", List.of());
            base.put("rowCount", 0);
            return base;
        }
        return previewRouter.preview(ctx);
    }

    /** 预览用表名：主链接 → 任意链接 → OM FQN 末段 → assetCode */
    private static String resolvePreviewObjectName(GovAssetSourceLink primary, List<GovAssetSourceLink> links,
                                                   GovAsset asset) {
        if (primary != null && StrUtil.isNotBlank(primary.getObjectName())) {
            return primary.getObjectName().trim();
        }
        if (links != null) {
            for (GovAssetSourceLink l : links) {
                if (l != null && StrUtil.isNotBlank(l.getObjectName())) {
                    return l.getObjectName().trim();
                }
            }
        }
        if (asset != null && StrUtil.isNotBlank(asset.getOmFqn())) {
            return shortName(asset.getOmFqn());
        }
        return asset == null ? null : asset.getAssetCode();
    }

    @Override
    public Map<String, Object> metaOptions() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("layers", Arrays.stream(GovAssetLayerEnum.values())
                .map(e -> Map.of("value", e.getValue(), "label", e.getLabel()))
                .toList());
        m.put("domains", List.of(
                Map.of("value", "trade", "label", "交易域"),
                Map.of("value", "user", "label", "用户域"),
                Map.of("value", "product", "label", "商品域"),
                Map.of("value", "marketing", "label", "营销域"),
                Map.of("value", "finance", "label", "财务域")));
        m.put("sensitivities", List.of(
                Map.of("value", "public", "label", "公开"),
                Map.of("value", "internal", "label", "内部"),
                Map.of("value", "secret", "label", "敏感"),
                Map.of("value", "confidential", "label", "机密")));
        m.put("statuses", Arrays.stream(GovAssetStatusEnum.values())
                .map(e -> Map.of("value", e.getValue()))
                .toList());
        m.put("kinds", List.of(
                LhInventoryObjectKinds.TABLE, LhInventoryObjectKinds.TOPIC,
                LhInventoryObjectKinds.INDEX, LhInventoryObjectKinds.BUCKET,
                LhInventoryObjectKinds.QUEUE, LhInventoryObjectKinds.PATH,
                LhInventoryObjectKinds.COLLECTION, LhInventoryObjectKinds.ENDPOINT));
        return m;
    }

    @Override
    public Map<String, Object> reconcileMetaDrift(String ws, String assetId) {
        if (StrUtil.isNotBlank(assetId)) {
            GovAsset asset = requireAsset(assetId.trim());
            Map<String, Object> one = govAssetMetaDriftReconcile.reconcileAsset(asset);
            Map<String, Object> out = new LinkedHashMap<>(one);
            out.put("mode", "asset");
            return out;
        }
        Map<String, Object> batch = govAssetMetaDriftReconcile.reconcileWorkspace(ws);
        batch.put("mode", "workspace");
        return batch;
    }

    @Override
    public List<Map<String, Object>> listMetaDrifts(String ws, Integer limit) {
        return govAssetMetaDriftReconcile.listOpen(ws, limit);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovAssetVo publishShare(GovAssetIdParam param) {
        GovAsset asset = requireAsset(param.getId());
        asset.setVisibility("shared_enterprise");
        asset.setShareStatus("published");
        asset.setRevision(asset.getRevision() == null ? 1 : asset.getRevision() + 1);
        asset.setUpdateTime(new Date());
        this.updateById(asset);
        return detail(param);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GovAssetVo unpublishShare(GovAssetIdParam param) {
        GovAsset asset = requireAsset(param.getId());
        asset.setVisibility("private_ws");
        asset.setShareStatus("none");
        asset.setRevision(asset.getRevision() == null ? 1 : asset.getRevision() + 1);
        asset.setUpdateTime(new Date());
        this.updateById(asset);
        return detail(param);
    }

    /**
     * workspace（默认）：按 home_ws 筛；enterprise：已发布共享；all：不过滤归属。
     * 兼容：未传 scope 但传了 ws → 按 workspace；二者皆空 → 回落 default 空间（禁止隐式全局）。
     */
    private void applyListScope(QueryWrapper<GovAsset> qw, GovAssetPageParam param) {
        String scope = StrUtil.trim(param.getScope());
        String ws = StrUtil.trim(param.getWs());
        if (StrUtil.isBlank(scope)) {
            scope = StrUtil.isNotBlank(ws) ? "workspace" : "workspace";
        }
        String s = scope.toLowerCase(Locale.ROOT);
        if ("enterprise".equals(s)) {
            qw.lambda().and(w -> w.in(GovAsset::getVisibility, "shared_enterprise", "listed_public")
                    .or().eq(GovAsset::getShareStatus, "published"));
            return;
        }
        if ("all".equals(s)) {
            return;
        }
        String home = StrUtil.blankToDefault(ws, "default");
        qw.lambda().eq(GovAsset::getWs, home);
    }

    private Map<String, Object> buildDriftExtra(GovAsset asset) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", "/catalog");
        out.put("api", "GET /lh/catalog/assets/drift");
        if (asset == null) {
            out.put("available", false);
            return out;
        }
        try {
            List<Map<String, Object>> opens = govAssetMetaDriftReconcile.listOpen(asset.getWs(), 200).stream()
                    .filter(m -> Objects.equals(String.valueOf(m.get("assetId")), asset.getId()))
                    .limit(8)
                    .toList();
            out.put("available", true);
            out.put("openCount", opens.size());
            out.put("items", opens);
            return out;
        } catch (Exception e) {
            out.put("available", false);
            out.put("degraded", true);
            out.put("message", e.getMessage());
            return out;
        }
    }

    // ---------- helpers ----------

    /**
     * refresh 时 soft-fail 挂接 cb_grav / 回填 om 指针
     */
    private Map<String, Object> softBridgeOnRefresh(GovAsset asset, List<GovAssetSourceLink> links) {
        Map<String, Object> bridge = new LinkedHashMap<>();
        try {
            bridge.put("grav", upsertGravBridge(asset, links));
        } catch (Exception e) {
            bridge.put("grav", Map.of("ok", false, "error",
                    StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName())));
        }
        try {
            Map<String, Object> om = new LinkedHashMap<>();
            if (StrUtil.isNotBlank(asset.getOmAssetId()) || StrUtil.isNotBlank(asset.getOmFqn())) {
                om.put("ok", true);
                om.put("omAssetId", asset.getOmAssetId());
                om.put("omFqn", asset.getOmFqn());
                // 若有 om_fqn 无 om_asset_id，补一行指针
                if (StrUtil.isBlank(asset.getOmAssetId()) && StrUtil.isNotBlank(asset.getOmFqn())) {
                    LhOmCatalogClassifier.Spec classify = new LhOmCatalogClassifier.Spec();
                    classify.omFamily = LhOmCatalogClassifier.FAMILY_DATABASE;
                    classify.serviceType = "Iceberg";
                    upsertOmPointer(asset, classify, asset.getOmFqn());
                    this.updateById(asset);
                    om.put("omAssetId", asset.getOmAssetId());
                    om.put("upserted", true);
                }
            } else {
                om.put("ok", false);
                om.put("skipped", true);
                om.put("reason", "no_om_fqn");
            }
            bridge.put("om", om);
        } catch (Exception e) {
            bridge.put("om", Map.of("ok", false, "error",
                    StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName())));
        }
        return bridge;
    }

    private Map<String, Object> upsertGravBridge(GovAsset asset, List<GovAssetSourceLink> links) {
        Map<String, Object> r = new LinkedHashMap<>();
        String kind = StrUtil.blankToDefault(asset.getAssetKind(), "table").toLowerCase(Locale.ROOT);
        if ("topic".equals(kind) || "index".equals(kind) || "bucket".equals(kind)
                || "queue".equals(kind) || "path".equals(kind)) {
            r.put("ok", false);
            r.put("skipped", true);
            r.put("reason", "non_relational_kind:" + kind);
            return r;
        }
        GovAssetSourceLink primary = links == null ? null : links.stream()
                .filter(l -> LINK_PRIMARY.equals(l.getLinkRole()))
                .findFirst().orElse(links == null || links.isEmpty() ? null : links.get(0));
        if (primary == null) {
            r.put("ok", false);
            r.put("skipped", true);
            r.put("reason", "no_primary_link");
            return r;
        }
        LhDatasource ds = datasourceMapper.selectById(primary.getDsId());
        if (ds == null || !gravitinoProjector.supportsGravitino(ds.getType())) {
            r.put("ok", false);
            r.put("skipped", true);
            r.put("reason", "type_not_grav_projectable");
            return r;
        }
        String metalake = lhProperties.getGravitino().getMetalake();
        String catalog = gravitinoProjector.catalogNameOf(ds);
        LhIcebergNamespaceNames.SchemaTable st = LhIcebergNamespaceNames.resolveSchemaTable(
                ds, primary.getObjectName());
        String schema = st.schema();
        String table = st.table();
        if (StrUtil.isBlank(table)) {
            r.put("ok", false);
            r.put("skipped", true);
            r.put("reason", "blank_table_name");
            return r;
        }
        if (StrUtil.isNotBlank(asset.getGravAssetId())) {
            CbGravAssetRef existing = gravAssetRefMapper.selectById(asset.getGravAssetId());
            if (existing != null) {
                metalake = existing.getGravMetalake();
                catalog = existing.getGravCatalog();
                schema = existing.getGravSchema();
                table = existing.getGravTable();
            }
        }
        GravitinoClient.GravTable gt = gravitinoClient.loadTable(metalake, catalog, schema, table);
        CbGravAssetRef ref = upsertGravRefFromTable(gt, asset);
        asset.setGravAssetId(ref.getId());
        this.updateById(asset);
        // 同步挂 OM 指针（若 schemasync 已有同 fqn 则合并）
        String omFqn = StrUtil.blankToDefault(asset.getOmFqn(),
                catalog + "." + schema + "." + table);
        LhOmCatalogClassifier.Spec classify = LhOmCatalogClassifier.resolve(ds, catalog);
        upsertOmPointer(asset, classify, omFqn);
        if (StrUtil.isBlank(asset.getOmFqn())) {
            asset.setOmFqn(omFqn);
        }
        // 回填 om ref 的 grav_asset_id
        if (StrUtil.isNotBlank(asset.getOmAssetId())) {
            CbOmAssetRef omRef = omAssetRefMapper.selectById(asset.getOmAssetId());
            if (omRef != null) {
                omRef.setGravAssetId(ref.getId());
                omRef.setOmEntityType(mapOmEntityType(asset.getAssetKind()));
                omRef.setOmFamily(classify.omFamily);
                omRef.setOmServiceType(classify.serviceType);
                omAssetRefMapper.updateById(omRef);
            }
        }
        this.updateById(asset);
        r.put("ok", true);
        r.put("gravAssetId", ref.getId());
        r.put("metalake", metalake);
        r.put("catalog", catalog);
        r.put("schema", schema);
        r.put("table", table);
        r.put("omAssetId", asset.getOmAssetId());
        r.put("omFqn", asset.getOmFqn());
        return r;
    }

    private CbGravAssetRef upsertGravRefFromTable(GravitinoClient.GravTable grav, GovAsset asset) {
        CbGravAssetRef existing = gravAssetRefMapper.selectOne(new QueryWrapper<CbGravAssetRef>().lambda()
                .eq(CbGravAssetRef::getGravMetalake, grav.metalake)
                .eq(CbGravAssetRef::getGravCatalog, grav.catalog)
                .eq(CbGravAssetRef::getGravSchema, grav.schema)
                .eq(CbGravAssetRef::getGravTable, grav.name)
                .last("LIMIT 1"));
        List<Map<String, Object>> cols = new ArrayList<>();
        if (grav.columns != null) {
            for (GravitinoClient.GravColumn c : grav.columns) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", c.name);
                m.put("type", c.type);
                m.put("nullable", c.nullable);
                cols.add(m);
            }
        }
        Date now = new Date();
        String colsJson = cn.hutool.json.JSONUtil.toJsonStr(cols);
        if (existing == null) {
            existing = new CbGravAssetRef();
            existing.setId(IdUtil.getSnowflakeNextIdStr());
            existing.setRevision(1);
            existing.setStatus("active");
            existing.setWs(StrUtil.blankToDefault(asset.getWs(), WS_DEFAULT));
            existing.setGravMetalake(grav.metalake);
            existing.setGravCatalog(grav.catalog);
            existing.setGravSchema(grav.schema);
            existing.setGravTable(grav.name);
            existing.setGravRevision(grav.auditVersion);
            existing.setLocationUri(grav.location);
            existing.setColumnsJson(colsJson);
            existing.setLayer(asset.getLayer());
            existing.setDomainCode(asset.getDomainCode());
            existing.setDriftFlag(0);
            existing.setLastSeenAt(now);
            existing.setDeleteFlag(NOT_DELETE);
            gravAssetRefMapper.insert(existing);
        } else {
            existing.setGravRevision(grav.auditVersion);
            existing.setLocationUri(grav.location);
            existing.setColumnsJson(colsJson);
            existing.setStatus("active");
            existing.setLayer(asset.getLayer());
            existing.setDomainCode(asset.getDomainCode());
            existing.setLastSeenAt(now);
            existing.setRevision(existing.getRevision() == null ? 1 : existing.getRevision() + 1);
            gravAssetRefMapper.updateById(existing);
        }
        return existing;
    }

    private Map<String, Object> loadSchemaInternal(GovAsset asset, List<GovAssetSourceLink> links,
                                                   Map<String, LhDatasource> dsMap) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("assetId", asset.getId());
        r.put("assetKind", asset.getAssetKind());

        // 非表形态：禁止走 Grav「假表」；返回对象/消息语义结构（预览走原生适配器，非 Trino）
        String resolvedKind = resolveObjectKind(asset, links, dsMap);
        r.put("resolvedKind", resolvedKind);
        if (isNonTableKind(resolvedKind)) {
            Map<String, Object> meta = buildNonTableSchema(resolvedKind, asset, links, dsMap);
            r.putAll(meta);
            return r;
        }

        // 1) Grav：cb_grav_asset_ref 或 数据源 catalog + 对象名
        try {
            Map<String, Object> grav = tryLoadGravSchema(asset, links, dsMap);
            if (grav != null && Boolean.TRUE.equals(grav.get("available"))) {
                r.putAll(grav);
                return r;
            }
            if (grav != null) {
                r.put("gravAttempt", grav);
            }
        } catch (Exception e) {
            r.put("gravError", e.getMessage());
            log.warn("Grav schema load fail asset={}: {}", asset.getId(), e.getMessage());
        }

        // 2) OM 回退
        try {
            Map<String, Object> om = tryLoadOmSchema(asset);
            if (om != null && Boolean.TRUE.equals(om.get("available"))) {
                r.putAll(om);
                return r;
            }
            if (om != null) {
                r.put("omAttempt", om);
            }
        } catch (Exception e) {
            r.put("omError", e.getMessage());
            log.warn("OM schema load fail asset={}: {}", asset.getId(), e.getMessage());
        }

        r.put("available", false);
        r.put("source", "none");
        r.put("hint", "无 Grav 表指针且 OM 实体不可用；可先 refresh。"
                + "PostgreSQL 裸表名默认 schema=public（不是库名）；Iceberg 用 objectName 的 namespace.table");
        return r;
    }

    /** 综合 assetKind / link.objectKind / 数据源类型，纠正误标为 table 的对象存储等 */
    private String resolveObjectKind(GovAsset asset, List<GovAssetSourceLink> links,
                                     Map<String, LhDatasource> dsMap) {
        String kind = StrUtil.blankToDefault(asset == null ? null : asset.getAssetKind(), "").toLowerCase(Locale.ROOT);
        GovAssetSourceLink primary = links == null ? null : links.stream()
                .filter(l -> LINK_PRIMARY.equals(l.getLinkRole()))
                .findFirst().orElse(links == null || links.isEmpty() ? null : links.get(0));
        if (primary != null) {
            if (StrUtil.isNotBlank(primary.getObjectKind())) {
                kind = primary.getObjectKind().toLowerCase(Locale.ROOT);
            }
            LhDatasource ds = dsMap == null ? null : dsMap.get(primary.getDsId());
            if (ds != null) {
                String fromDs = LhInventoryObjectKinds.ofType(ds.getType());
                if (!LhInventoryObjectKinds.TABLE.equals(fromDs)
                        && !LhInventoryObjectKinds.UNKNOWN.equals(fromDs)) {
                    kind = fromDs;
                }
            }
        }
        if (StrUtil.isBlank(kind)) {
            kind = LhInventoryObjectKinds.TABLE;
        }
        return kind;
    }

    private static boolean isNonTableKind(String kind) {
        String k = StrUtil.blankToDefault(kind, "").toLowerCase(Locale.ROOT);
        return LhInventoryObjectKinds.BUCKET.equals(k)
                || LhInventoryObjectKinds.INDEX.equals(k)
                || LhInventoryObjectKinds.TOPIC.equals(k)
                || LhInventoryObjectKinds.QUEUE.equals(k)
                || LhInventoryObjectKinds.PATH.equals(k)
                || LhInventoryObjectKinds.KEY.equals(k)
                || LhInventoryObjectKinds.KEY_PREFIX.equals(k)
                || LhInventoryObjectKinds.COLLECTION.equals(k)
                || LhInventoryObjectKinds.FILESET.equals(k)
                || LhInventoryObjectKinds.ENDPOINT.equals(k);
    }

    /**
     * 非表资产的「结构」：对象元数据 / 消息样例列，不是伪造的业务表字段。
     */
    private Map<String, Object> buildNonTableSchema(String kind, GovAsset asset,
                                                     List<GovAssetSourceLink> links,
                                                     Map<String, LhDatasource> dsMap) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("available", true);
        r.put("relational", false);
        List<Map<String, Object>> cols = new ArrayList<>();
        String source;
        String hint;
        switch (StrUtil.blankToDefault(kind, "")) {
            case LhInventoryObjectKinds.BUCKET, LhInventoryObjectKinds.FILESET -> {
                source = "s3_object_meta";
                hint = "对象存储无关系表字段；下列为对象元数据列。数据预览走 MinIO/S3 ListObjects（非 Trino/Grav）。";
                cols.add(metaCol("key", "STRING", "对象键"));
                cols.add(metaCol("size", "BIGINT", "字节大小"));
                cols.add(metaCol("lastModified", "TIMESTAMP", "最后修改时间"));
                cols.add(metaCol("etag", "STRING", "ETag"));
            }
            case LhInventoryObjectKinds.INDEX -> {
                source = "es_doc_meta";
                hint = "Elasticsearch 索引无固定 JDBC 字段；预览走 _search 样例文档（非 Trino）。映射属性可后续增强。";
                cols.add(metaCol("_id", "STRING", "文档 ID"));
                cols.add(metaCol("_source", "JSON", "文档内容（预览时展开）"));
            }
            case LhInventoryObjectKinds.TOPIC, LhInventoryObjectKinds.QUEUE -> {
                source = "mq_message_meta";
                hint = "消息队列无表字段；预览走短时消费样例（非 Trino）。";
                cols.add(metaCol("partition", "INT", "分区"));
                cols.add(metaCol("offset", "BIGINT", "偏移"));
                cols.add(metaCol("timestamp", "BIGINT", "时间戳"));
                cols.add(metaCol("key", "STRING", "消息 Key"));
                cols.add(metaCol("value", "STRING", "消息 Value"));
            }
            case LhInventoryObjectKinds.KEY, LhInventoryObjectKinds.KEY_PREFIX -> {
                source = "redis_kv_meta";
                hint = "Redis 无表字段；预览走 SCAN 样例键值（非 Trino）。";
                cols.add(metaCol("key", "STRING", "键"));
                cols.add(metaCol("type", "STRING", "类型"));
                cols.add(metaCol("value", "STRING", "样例值"));
            }
            case LhInventoryObjectKinds.COLLECTION -> {
                source = "mongo_doc_meta";
                hint = "MongoDB 集合为文档模型；预览走 find 样例（非 Trino）。列随文档动态出现。";
                cols.add(metaCol("_id", "STRING", "文档 ID"));
                cols.add(metaCol("document", "JSON", "文档内容"));
            }
            case LhInventoryObjectKinds.PATH -> {
                source = "fs_path_meta";
                hint = "文件/HDFS/FTP 路径无表字段；当前仅元数据，List 预览可后续增强。";
                cols.add(metaCol("path", "STRING", "路径"));
                cols.add(metaCol("name", "STRING", "名称"));
                cols.add(metaCol("size", "BIGINT", "大小"));
            }
            case LhInventoryObjectKinds.ENDPOINT -> {
                source = "http_api_meta";
                hint = "HTTP API 无表字段；预览走 GET 响应样例（非 Trino）。";
                cols.add(metaCol("status", "INT", "HTTP 状态"));
                cols.add(metaCol("body", "STRING", "响应片段"));
            }
            default -> {
                source = "object_meta";
                hint = "非关系型对象；无 JDBC/Grav 表结构。";
                cols.add(metaCol("name", "STRING", "对象名"));
            }
        }
        r.put("source", source);
        r.put("hint", hint);
        r.put("columns", cols);
        r.put("columnCount", cols.size());
        if (links != null && !links.isEmpty()) {
            GovAssetSourceLink p = links.stream()
                    .filter(l -> LINK_PRIMARY.equals(l.getLinkRole()))
                    .findFirst().orElse(links.get(0));
            r.put("objectName", p.getObjectName());
            r.put("objectKind", p.getObjectKind());
            LhDatasource ds = dsMap == null ? null : dsMap.get(p.getDsId());
            if (ds != null) {
                r.put("dsType", ds.getType());
                r.put("dsName", ds.getName());
            }
        }
        return r;
    }

    private static Map<String, Object> metaCol(String name, String type, String comment) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("type", type);
        c.put("nullable", true);
        c.put("comment", comment);
        return c;
    }

    private Map<String, Object> tryLoadGravSchema(GovAsset asset, List<GovAssetSourceLink> links,
                                                  Map<String, LhDatasource> dsMap) {
        String kind = resolveObjectKind(asset, links, dsMap);
        if (isNonTableKind(kind)) {
            Map<String, Object> skip = new LinkedHashMap<>();
            skip.put("available", false);
            skip.put("source", "gravitino");
            skip.put("skipped", true);
            skip.put("reason", "non_relational_kind:" + kind);
            return skip;
        }

        String metalake = null;
        String catalog = null;
        String schema = null;
        String table = null;

        if (StrUtil.isNotBlank(asset.getGravAssetId())) {
            CbGravAssetRef ref = gravAssetRefMapper.selectById(asset.getGravAssetId());
            if (ref != null) {
                metalake = ref.getGravMetalake();
                catalog = ref.getGravCatalog();
                schema = ref.getGravSchema();
                table = ref.getGravTable();
            }
        }
        if (StrUtil.isBlank(catalog) || StrUtil.isBlank(table)) {
            GovAssetSourceLink primary = links == null ? null : links.stream()
                    .filter(l -> LINK_PRIMARY.equals(l.getLinkRole()))
                    .findFirst().orElse(links == null || links.isEmpty() ? null : links.get(0));
            if (primary != null) {
                LhDatasource ds = dsMap.get(primary.getDsId());
                if (ds != null && gravitinoProjector.supportsGravitino(ds.getType())) {
                    metalake = StrUtil.blankToDefault(metalake, lhProperties.getGravitino().getMetalake());
                    catalog = StrUtil.blankToDefault(catalog, gravitinoProjector.catalogNameOf(ds));
                    LhIcebergNamespaceNames.SchemaTable st = LhIcebergNamespaceNames.resolveSchemaTable(
                            ds, primary.getObjectName());
                    schema = StrUtil.blankToDefault(schema, st.schema());
                    table = StrUtil.blankToDefault(table, st.table());
                }
            }
        }
        if (StrUtil.isBlank(metalake) || StrUtil.isBlank(catalog)
                || StrUtil.isBlank(schema) || StrUtil.isBlank(table)) {
            Map<String, Object> miss = new LinkedHashMap<>();
            miss.put("available", false);
            miss.put("source", "gravitino");
            miss.put("reason", "no_grav_coordinates");
            return miss;
        }

        GravitinoClient.GravTable gt = gravitinoClient.loadTable(metalake, catalog, schema, table);
        List<Map<String, Object>> cols = new ArrayList<>();
        for (GravitinoClient.GravColumn c : gt.columns) {
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", c.name);
            col.put("type", c.type);
            col.put("nullable", c.nullable);
            col.put("comment", c.comment);
            cols.add(col);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("available", true);
        r.put("source", "gravitino");
        r.put("metalake", metalake);
        r.put("catalog", catalog);
        r.put("schema", schema);
        r.put("table", gt.name);
        r.put("comment", gt.comment);
        r.put("location", gt.location);
        r.put("gravRevision", gt.auditVersion);
        r.put("partitionKeys", gt.partitionKeys);
        r.put("columns", cols);
        r.put("columnCount", cols.size());
        return r;
    }

    private Map<String, Object> tryLoadOmSchema(GovAsset asset) {
        if (StrUtil.isBlank(asset.getOmFqn())) {
            Map<String, Object> miss = new LinkedHashMap<>();
            miss.put("available", false);
            miss.put("source", "openmetadata");
            miss.put("reason", "no_om_fqn");
            return miss;
        }
        String entityType = resolveOmEntityType(asset);
        JSONObject ent = openMetadataClient.getCatalogEntity(entityType, asset.getOmFqn());
        if (ent == null) {
            Map<String, Object> miss = new LinkedHashMap<>();
            miss.put("available", false);
            miss.put("source", "openmetadata");
            miss.put("reason", "entity_not_found");
            miss.put("omFqn", asset.getOmFqn());
            return miss;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("available", true);
        r.put("source", "openmetadata");
        r.put("omFqn", ent.getStr("fullyQualifiedName", asset.getOmFqn()));
        r.put("omEntityType", entityType);
        r.put("description", ent.getStr("description"));
        r.put("displayName", ent.getStr("displayName"));

        List<Map<String, Object>> cols = new ArrayList<>();
        JSONArray columns = ent.getJSONArray("columns");
        if (columns != null) {
            for (int i = 0; i < columns.size(); i++) {
                JSONObject c = columns.getJSONObject(i);
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("name", c.getStr("name"));
                col.put("type", StrUtil.blankToDefault(c.getStr("dataTypeDisplay"), c.getStr("dataType")));
                col.put("comment", c.getStr("description"));
                cols.add(col);
            }
        }
        JSONArray fields = ent.getJSONArray("fields");
        if (fields != null && cols.isEmpty()) {
            for (int i = 0; i < fields.size(); i++) {
                JSONObject c = fields.getJSONObject(i);
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("name", c.getStr("name"));
                col.put("type", StrUtil.blankToDefault(c.getStr("dataTypeDisplay"), c.getStr("dataType")));
                col.put("comment", c.getStr("description"));
                cols.add(col);
            }
        }
        if ("topic".equalsIgnoreCase(entityType)) {
            r.put("partitions", ent.get("partitions"));
            r.put("messageSchema", ent.get("messageSchema"));
        }
        r.put("columns", cols);
        r.put("columnCount", cols.size());
        return r;
    }

    private Map<String, Object> loadOmMetaInternal(GovAsset asset) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (StrUtil.isBlank(asset.getOmFqn())) {
            r.put("available", false);
            r.put("reason", "no_om_fqn");
            return r;
        }
        try {
            String entityType = resolveOmEntityType(asset);
            JSONObject ent = openMetadataClient.getCatalogEntity(entityType, asset.getOmFqn());
            if (ent == null) {
                r.put("available", false);
                r.put("reason", "entity_not_found");
                r.put("omFqn", asset.getOmFqn());
                return r;
            }
            List<String> tagFqns = GovAssetGoldTagSupport.extractTagFqns(ent.get("tags"));
            r.put("available", true);
            r.put("omFqn", ent.getStr("fullyQualifiedName", asset.getOmFqn()));
            r.put("description", ent.getStr("description"));
            r.put("displayName", ent.getStr("displayName"));
            r.put("owners", ent.get("owners"));
            r.put("tags", ent.get("tags"));
            r.put("tagFqns", tagFqns);
            r.put("omId", ent.getStr("id"));
            r.put("omVersion", ent.get("version"));
            return r;
        } catch (Exception e) {
            r.put("available", false);
            r.put("error", e.getMessage());
            return r;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildGoldExtra(GovAsset asset, Object omMetaObj) {
        boolean portalIsGold = asset.getIsGold() != null && asset.getIsGold() == 1;
        List<String> goldFqns = configuredGoldTagFqns();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("policy", "om_wins");
        r.put("goldTagFqns", goldFqns);
        r.put("portalIsGold", portalIsGold);
        if (!(omMetaObj instanceof Map<?, ?> omMeta) || !Boolean.TRUE.equals(omMeta.get("available"))) {
            r.put("omHasGold", null);
            r.put("aligned", null);
            r.put("hint", "OM 元数据不可用，暂不对账");
            return r;
        }
        List<String> tagFqns = omMeta.get("tagFqns") instanceof List<?> list
                ? (List<String>) list
                : GovAssetGoldTagSupport.extractTagFqns(omMeta.get("tags"));
        Map<String, Object> rec = GovAssetGoldTagSupport.reconcile(portalIsGold, tagFqns, goldFqns);
        r.putAll(rec);
        return r;
    }

    private Map<String, Object> reconcileGoldOnRefresh(GovAsset asset) {
        if (StrUtil.isBlank(asset.getOmFqn())) {
            return Map.of("skipped", true, "reason", "no_om_fqn");
        }
        try {
            String entityType = resolveOmEntityType(asset);
            JSONObject ent = openMetadataClient.getCatalogEntity(entityType, asset.getOmFqn());
            List<String> tagFqns = GovAssetGoldTagSupport.extractTagFqns(ent == null ? null : ent.get("tags"));
            return applyGoldFromOm(asset, tagFqns, configuredGoldTagFqns());
        } catch (Exception e) {
            log.warn("gold reconcile soft-fail asset={}: {}", asset.getId(), e.getMessage());
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    /** 按 OM 金标回写门户 is_gold（不立刻 updateById，由调用方决定） */
    private Map<String, Object> applyGoldFromOm(GovAsset asset, List<String> tagFqns, List<String> goldTagFqns) {
        boolean portalIsGold = asset.getIsGold() != null && asset.getIsGold() == 1;
        Map<String, Object> rec = GovAssetGoldTagSupport.reconcile(portalIsGold, tagFqns, goldTagFqns);
        int desired = (Integer) rec.get("desiredIsGold");
        boolean updated = false;
        if (!Boolean.TRUE.equals(rec.get("aligned"))) {
            asset.setIsGold(desired);
            updated = true;
        }
        rec.put("portalUpdated", updated);
        rec.put("ok", true);
        return rec;
    }

    private List<String> configuredGoldTagFqns() {
        String raw = lhProperties.getOpenmetadata() == null
                ? null
                : lhProperties.getOpenmetadata().getGoldTagFqns();
        if (StrUtil.isBlank(raw)) {
            return GovAssetGoldTagSupport.normalizeGoldTagFqns(null);
        }
        return GovAssetGoldTagSupport.normalizeGoldTagFqns(
                Arrays.stream(raw.split(",")).map(String::trim).filter(StrUtil::isNotBlank).toList());
    }

    private Map<String, Object> snapshotMetaAudit(GovAsset asset) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assetId", asset.getId());
        m.put("assetCode", asset.getAssetCode());
        m.put("description", asset.getDescription());
        m.put("isGold", asset.getIsGold());
        m.put("omFqn", asset.getOmFqn());
        try {
            Map<String, Object> om = loadOmMetaInternal(asset);
            m.put("omDescription", om.get("description"));
            m.put("omDisplayName", om.get("displayName"));
            m.put("omTagFqns", om.get("tagFqns"));
        } catch (Exception ignored) {
            // soft
        }
        return m;
    }

    private String resolveOmEntityType(GovAsset asset) {
        if (StrUtil.isNotBlank(asset.getOmAssetId())) {
            CbOmAssetRef ref = omAssetRefMapper.selectById(asset.getOmAssetId());
            if (ref != null && StrUtil.isNotBlank(ref.getOmEntityType())) {
                return ref.getOmEntityType();
            }
        }
        return mapOmEntityType(asset.getAssetKind());
    }

    private Map<String, Object> softRefreshOm(GovAsset asset, LhDatasource ds) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("dsId", ds.getId());
        String catalog = gravitinoProjector.catalogNameOf(ds);
        if (portalInventoryOmBridge.shouldPush(ds)) {
            Map<String, Object> push = portalInventoryOmBridge.pushInventory(ds, catalog);
            r.putAll(push);
            Object svc = push.get("omService");
            Object fqnPrefix = push.get("fqnPrefix");
            if (svc != null || fqnPrefix != null) {
                LhOmCatalogClassifier.Spec classify = LhOmCatalogClassifier.resolve(ds, catalog);
                String omFqn = resolveAssetOmFqn(asset, ds, classify, push);
                if (StrUtil.isNotBlank(omFqn)) {
                    upsertOmPointer(asset, classify, omFqn);
                    asset.setOmFqn(omFqn);
                    asset.setLastSyncAt(new Date());
                    asset.setLastSyncStatus("ok");
                    this.updateById(asset);
                }
            }
            r.put("ok", Boolean.TRUE.equals(push.get("ok")) || (push.get("upserted") instanceof Number n && n.intValue() > 0)
                    || Boolean.TRUE.equals(push.get("skipped")));
            return r;
        }
        // JDBC/Hive 等：仅记录分类挂载点，完整 Schema Sync 由数据源登记桥接负责
        LhOmCatalogClassifier.Spec classify = LhOmCatalogClassifier.resolve(ds, catalog);
        String omFqn = classify.serviceName + "." + classify.databaseName;
        asset.setOmFqn(omFqn);
        asset.setLastSyncAt(new Date());
        asset.setLastSyncStatus("skipped");
        this.updateById(asset);
        r.put("ok", true);
        r.put("skipped", true);
        r.put("reason", "not_portal_om_type");
        r.put("omService", classify.serviceName);
        r.put("hint", "库表结构请依赖数据源 PostRegister / schemasync");
        return r;
    }

    private String resolveAssetOmFqn(GovAsset asset, LhDatasource ds,
                                     LhOmCatalogClassifier.Spec classify, Map<String, Object> push) {
        String objectName = null;
        List<GovAssetSourceLink> links = linkMapper.selectList(new QueryWrapper<GovAssetSourceLink>().lambda()
                .eq(GovAssetSourceLink::getAssetId, asset.getId())
                .eq(GovAssetSourceLink::getDsId, ds.getId())
                .eq(GovAssetSourceLink::getLinkRole, LINK_PRIMARY)
                .eq(GovAssetSourceLink::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        if (!links.isEmpty()) {
            objectName = links.get(0).getObjectName();
        }
        String family = StrUtil.blankToDefault(classify.omFamily, LhOmCatalogClassifier.FAMILY_DATABASE);
        if (LhOmCatalogClassifier.FAMILY_MESSAGING.equals(family)
                || LhOmCatalogClassifier.FAMILY_SEARCH.equals(family)
                || LhOmCatalogClassifier.FAMILY_STORAGE.equals(family)) {
            if (StrUtil.isNotBlank(objectName)) {
                return classify.serviceName + "." + objectName;
            }
            return classify.serviceName;
        }
        Object prefix = push.get("fqnPrefix");
        if (prefix != null && StrUtil.isNotBlank(objectName)) {
            return prefix + "." + sanitizeOmSegment(objectName);
        }
        if (prefix != null) {
            return String.valueOf(prefix);
        }
        return classify.serviceName + "." + classify.databaseName;
    }

    private void upsertOmPointer(GovAsset asset, LhOmCatalogClassifier.Spec classify, String omFqn) {
        CbOmAssetRef existing = null;
        if (StrUtil.isNotBlank(asset.getOmAssetId())) {
            existing = omAssetRefMapper.selectById(asset.getOmAssetId());
        }
        if (existing == null) {
            existing = omAssetRefMapper.selectOne(new QueryWrapper<CbOmAssetRef>().lambda()
                    .eq(CbOmAssetRef::getOmFqn, omFqn)
                    .last("LIMIT 1"));
        }
        Date now = new Date();
        if (existing == null) {
            CbOmAssetRef ref = new CbOmAssetRef();
            ref.setId(IdUtil.getSnowflakeNextIdStr());
            ref.setRevision(1);
            ref.setStatus("active");
            ref.setWs(asset.getWs());
            ref.setAssetId(asset.getId());
            ref.setOmEntityType(mapOmEntityType(asset.getAssetKind()));
            ref.setOmServiceType(classify.serviceType);
            ref.setOmFamily(classify.omFamily);
            ref.setOmFqn(omFqn);
            ref.setLastSyncAt(now);
            ref.setLastSyncStatus("ok");
            ref.setDriftFlag(0);
            ref.setSyncedGravRev(0L);
            ref.setDeleteFlag(NOT_DELETE);
            omAssetRefMapper.insert(ref);
            asset.setOmAssetId(ref.getId());
        } else {
            existing.setAssetId(asset.getId());
            existing.setOmFqn(omFqn);
            existing.setOmEntityType(mapOmEntityType(asset.getAssetKind()));
            existing.setOmServiceType(classify.serviceType);
            existing.setOmFamily(classify.omFamily);
            existing.setLastSyncAt(now);
            existing.setLastSyncStatus("ok");
            existing.setRevision(existing.getRevision() == null ? 1 : existing.getRevision() + 1);
            omAssetRefMapper.updateById(existing);
            asset.setOmAssetId(existing.getId());
        }
    }

    private static String mapOmEntityType(String assetKind) {
        String k = StrUtil.blankToDefault(assetKind, "table").toLowerCase(Locale.ROOT);
        return switch (k) {
            case "topic", "queue" -> "topic";
            case "index" -> "searchIndex";
            case "bucket", "path", "fileset" -> "container";
            default -> "table";
        };
    }

    private GovAsset requireAsset(String idOrCode) {
        if (StrUtil.isBlank(idOrCode)) {
            throw new CommonException("资产ID不能为空");
        }
        GovAsset byId = this.getById(idOrCode);
        if (byId != null && NOT_DELETE.equals(byId.getDeleteFlag())) {
            return byId;
        }
        GovAsset byCode = this.getOne(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getAssetCode, idOrCode)
                .eq(GovAsset::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        if (byCode == null) {
            throw new CommonException("资产不存在: {}", idOrCode);
        }
        return byCode;
    }

    /**
     * @return null=不按源过滤；empty=无匹配；非空=assetId 集合
     */
    private Set<String> resolveAssetIdsBySource(String dsId, String source) {
        if (StrUtil.isBlank(dsId) && StrUtil.isBlank(source)) {
            return null;
        }
        QueryWrapper<GovAssetSourceLink> lq = new QueryWrapper<>();
        lq.lambda().eq(GovAssetSourceLink::getDeleteFlag, NOT_DELETE);
        if (StrUtil.isNotBlank(dsId)) {
            lq.lambda().eq(GovAssetSourceLink::getDsId, dsId.trim());
        } else {
            List<LhDatasource> dsList = datasourceMapper.selectList(new QueryWrapper<LhDatasource>().lambda()
                    .and(w -> w.eq(LhDatasource::getName, source.trim())
                            .or().eq(LhDatasource::getDsCode, source.trim())
                            .or().eq(LhDatasource::getId, source.trim())));
            if (dsList.isEmpty()) {
                return Set.of();
            }
            lq.lambda().in(GovAssetSourceLink::getDsId,
                    dsList.stream().map(LhDatasource::getId).toList());
        }
        List<GovAssetSourceLink> links = linkMapper.selectList(lq);
        return links.stream().map(GovAssetSourceLink::getAssetId).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<String, List<GovAssetSourceLink>> loadPrimaryLinks(List<String> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            return Map.of();
        }
        List<GovAssetSourceLink> links = linkMapper.selectList(new QueryWrapper<GovAssetSourceLink>().lambda()
                .in(GovAssetSourceLink::getAssetId, assetIds)
                .eq(GovAssetSourceLink::getDeleteFlag, NOT_DELETE));
        return links.stream().collect(Collectors.groupingBy(GovAssetSourceLink::getAssetId));
    }

    private Map<String, LhDatasource> loadDatasources(List<String> dsIds) {
        if (dsIds == null || dsIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = dsIds.stream().filter(StrUtil::isNotBlank).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return datasourceMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(LhDatasource::getId, d -> d, (a, b) -> a));
    }

    private GovAssetVo toVo(GovAsset a, List<GovAssetSourceLink> links,
                            Map<String, LhDatasource> dsMap, boolean withSources) {
        GovAssetVo vo = new GovAssetVo();
        vo.setId(a.getId());
        vo.setAssetCode(a.getAssetCode());
        vo.setName(a.getName());
        vo.setCnName(a.getCnName());
        vo.setDescription(a.getDescription());
        vo.setAssetKind(a.getAssetKind());
        vo.setLayer(a.getLayer());
        vo.setLayerLabel(GovAssetLayerEnum.of(a.getLayer()).map(GovAssetLayerEnum::getLabel).orElse(a.getLayer()));
        vo.setDomainCode(a.getDomainCode());
        vo.setSensitivity(a.getSensitivity());
        vo.setStatus(a.getStatus());
        vo.setTechOwner(a.getTechOwner());
        vo.setBizOwner(a.getBizOwner());
        vo.setCreateUser(a.getCreateUser());
        vo.setEngine(a.getEngine());
        vo.setIsGold(a.getIsGold() != null && a.getIsGold() == 1);
        vo.setOmFqn(a.getOmFqn());
        vo.setGravAssetId(a.getGravAssetId());
        vo.setLastSyncAt(a.getLastSyncAt());
        vo.setLastSyncStatus(a.getLastSyncStatus());
        vo.setWs(a.getWs());
        vo.setVisibility(StrUtil.blankToDefault(a.getVisibility(), "private_ws"));
        vo.setShareStatus(StrUtil.blankToDefault(a.getShareStatus(), "none"));
        vo.setRevision(a.getRevision());
        vo.setUpdateTime(a.getUpdateTime());

        List<GovAssetSourceLink> safeLinks = links == null ? List.of() : links;
        GovAssetSourceLink primary = safeLinks.stream()
                .filter(l -> LINK_PRIMARY.equals(l.getLinkRole()))
                .findFirst()
                .orElse(safeLinks.isEmpty() ? null : safeLinks.get(0));
        if (primary != null) {
            vo.setObjectName(primary.getObjectName());
            vo.setPrimaryDsId(primary.getDsId());
            vo.setLinkStatus(primary.getStatus());
            LhDatasource ds = dsMap.get(primary.getDsId());
            if (ds != null) {
                vo.setPrimaryDsName(ds.getName());
                vo.setPrimaryDsType(dsTypeLabel(ds.getType()));
                vo.setPrimaryDsCode(ds.getDsCode());
            }
        }
        List<String> names = safeLinks.stream()
                .map(l -> {
                    LhDatasource ds = dsMap.get(l.getDsId());
                    if (ds == null) {
                        return l.getDsCode();
                    }
                    String type = dsTypeLabel(ds.getType());
                    return StrUtil.isBlank(type) ? ds.getName() : type + " · " + ds.getName();
                })
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        if (names.size() == 1) {
            vo.setSourceSummary(names.get(0));
        } else if (names.size() > 1) {
            vo.setSourceSummary(names.get(0) + " 等" + names.size() + "个源");
        }
        if (withSources) {
            vo.setSources(safeLinks.stream().map(l -> toSourceVo(l, dsMap.get(l.getDsId()))).toList());
        }
        return vo;
    }

    private static GovAssetSourceVo toSourceVo(GovAssetSourceLink link, LhDatasource ds) {
        GovAssetSourceVo v = new GovAssetSourceVo();
        v.setId(link.getId());
        v.setDsId(link.getDsId());
        v.setDsCode(link.getDsCode());
        v.setObjectName(link.getObjectName());
        v.setObjectKind(link.getObjectKind());
        v.setDsTableId(link.getDsTableId());
        v.setLinkRole(link.getLinkRole());
        v.setStatus(link.getStatus());
        if (ds != null) {
            v.setDsName(ds.getName());
            v.setDsType(ds.getType());
            v.setDsStatus(ds.getStatus());
            if (StrUtil.isBlank(v.getDsCode())) {
                v.setDsCode(ds.getDsCode());
            }
        }
        return v;
    }

    /**
     * 默认资产编码：layer_dsSeg_shortName。
     * dsSeg 优先 dsCode，其次源名，再次源 id 尾段，避免同表名跨源冲突。
     */
    private static String buildAssetCode(String layer, String domain, String objectName, LhDatasource ds) {
        String shortN = shortName(objectName);
        String dsSeg = compactSeg(ds != null ? ds.getDsCode() : null, 24);
        if (StrUtil.isBlank(dsSeg)) {
            dsSeg = compactSeg(ds != null ? ds.getName() : null, 24);
        }
        if (StrUtil.isBlank(dsSeg)) {
            String id = ds != null ? ds.getId() : null;
            dsSeg = StrUtil.isNotBlank(id) && id.length() > 8
                    ? compactSeg(id.substring(id.length() - 8), 10)
                    : compactSeg(id, 10);
        }
        if (StrUtil.isBlank(dsSeg)) {
            dsSeg = compactSeg(domain, 24);
        }
        if (StrUtil.isBlank(dsSeg)) {
            dsSeg = "src";
        }
        return sanitizeCode(layer + "_" + dsSeg + "_" + shortN);
    }

    private boolean assetCodeExists(String ws, String assetCode) {
        Long dupCode = this.count(new QueryWrapper<GovAsset>().lambda()
                .eq(GovAsset::getWs, ws)
                .eq(GovAsset::getAssetCode, assetCode)
                .eq(GovAsset::getDeleteFlag, NOT_DELETE));
        return dupCode != null && dupCode > 0;
    }

    /** 自动生成编码时追加 _2/_3… 直至唯一 */
    private String allocateUniqueAssetCode(String ws, String base) {
        String code = sanitizeCode(base);
        if (!assetCodeExists(ws, code)) {
            return code;
        }
        for (int i = 2; i <= 99; i++) {
            String suffix = "_" + i;
            int maxBase = Math.max(1, 128 - suffix.length());
            String candidate = sanitizeCode(StrUtil.sub(code, 0, maxBase) + suffix);
            if (!assetCodeExists(ws, candidate)) {
                return candidate;
            }
        }
        throw new CommonException("资产编码冲突过多，请手动指定: {}", code);
    }

    private static String shortName(String objectName) {
        String raw = StrUtil.blankToDefault(objectName, "object");
        String[] parts = raw.split("[./]");
        return parts[parts.length - 1];
    }

    private static String compactSeg(String raw, int max) {
        String s = StrUtil.blankToDefault(raw, "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .replaceAll("_+", "_");
        if (StrUtil.isBlank(s)) {
            return "";
        }
        if (s.length() > max) {
            s = s.substring(0, max).replaceAll("_+$", "");
        }
        return s;
    }

    private static String sanitizeCode(String code) {
        String s = StrUtil.blankToDefault(code, "asset").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "_")
                .replaceAll("_+", "_");
        if (s.startsWith("_")) {
            s = "a" + s;
        }
        return StrUtil.sub(s, 0, 128);
    }

    private static String dsTypeLabel(String typeCode) {
        if (StrUtil.isBlank(typeCode)) {
            return "";
        }
        return LhDatasourceTypeEnum.of(typeCode)
                .map(LhDatasourceTypeEnum::getLabel)
                .orElse(typeCode);
    }

    private static String sanitizeOmSegment(String name) {
        return StrUtil.blankToDefault(name, "obj")
                .replace('.', '_')
                .replace('/', '_')
                .replace('-', '_');
    }

    private static String inferEngine(String dsType, String assetKind) {
        if ("topic".equalsIgnoreCase(assetKind) || "kafka".equalsIgnoreCase(dsType)) {
            return "Kafka";
        }
        if ("index".equalsIgnoreCase(assetKind)) {
            return "Elasticsearch";
        }
        if ("bucket".equalsIgnoreCase(assetKind)) {
            return "S3";
        }
        if ("hive".equalsIgnoreCase(dsType) || "iceberg".equalsIgnoreCase(dsType)) {
            return "Iceberg";
        }
        return StrUtil.blankToDefault(dsType, "unknown");
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) {
            return null;
        }
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) {
                return v.trim();
            }
        }
        return null;
    }
}
