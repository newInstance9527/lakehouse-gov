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
package vip.xiaonuo.lh.modular.catalog.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.entity.GovAssetSourceLink;
import vip.xiaonuo.lh.modular.catalog.enums.GovAssetStatusEnum;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetSourceLinkMapper;
import vip.xiaonuo.lh.modular.datasource.entity.LhDsTable;
import vip.xiaonuo.lh.modular.datasource.mapper.LhDsTableMapper;
import vip.xiaonuo.lh.modular.plat.service.PlatOutboxService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 源对象消失 / 清单删除 → link stale + 资产 degraded
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Slf4j
@Component
public class GovAssetSourceReconcile {

    public static final String LINK_STALE = "stale";
    public static final String LINK_ACTIVE = "active";
    public static final String LINK_PRIMARY = "primary";
    private static final String NOT_DELETE = "NOT_DELETE";

    @Resource
    private GovAssetSourceLinkMapper linkMapper;
    @Resource
    private GovAssetMapper assetMapper;
    @Resource
    private LhDsTableMapper dsTableMapper;
    @Resource
    private PlatOutboxService platOutboxService;

    /**
     * 清单同步删除前：按 (dsId, objectName) 批量标记 stale。
     *
     * @return 摘要
     */
    public Map<String, Object> markMissingObjects(String dsId, Collection<String> missingObjectNames) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("dsId", dsId);
        if (StrUtil.isBlank(dsId) || missingObjectNames == null || missingObjectNames.isEmpty()) {
            r.put("staleLinks", 0);
            r.put("degradedAssets", 0);
            return r;
        }
        int staleLinks = 0;
        int degradedAssets = 0;
        List<String> assetIds = new ArrayList<>();
        for (String objectName : missingObjectNames) {
            if (StrUtil.isBlank(objectName)) {
                continue;
            }
            try {
                Map<String, Object> one = markOne(dsId, objectName.trim(), "inventory_removed");
                if (Boolean.TRUE.equals(one.get("linkUpdated"))) {
                    staleLinks++;
                }
                if (Boolean.TRUE.equals(one.get("assetDegraded"))) {
                    degradedAssets++;
                }
                if (one.get("assetId") != null) {
                    assetIds.add(String.valueOf(one.get("assetId")));
                }
            } catch (Exception e) {
                log.warn("source reconcile soft-fail ds={} obj={}: {}", dsId, objectName, e.getMessage());
            }
        }
        r.put("staleLinks", staleLinks);
        r.put("degradedAssets", degradedAssets);
        r.put("assetIds", assetIds);
        return r;
    }

    /**
     * 清单对象改名（如裸表名 → schema.table）：回写同源 link.object_name。
     * soft-fail 由调用方吞；本方法不抛业务异常。
     */
    public void renameObjectName(String dsId, String oldName, String newName) {
        if (StrUtil.isBlank(dsId) || StrUtil.isBlank(oldName) || StrUtil.isBlank(newName) || oldName.equals(newName)) {
            return;
        }
        List<GovAssetSourceLink> links = linkMapper.selectList(new QueryWrapper<GovAssetSourceLink>().lambda()
                .eq(GovAssetSourceLink::getDsId, dsId)
                .eq(GovAssetSourceLink::getObjectName, oldName)
                .eq(GovAssetSourceLink::getDeleteFlag, NOT_DELETE));
        if (links == null || links.isEmpty()) {
            return;
        }
        Date now = new Date();
        for (GovAssetSourceLink link : links) {
            link.setObjectName(newName);
            link.setRevision(link.getRevision() == null ? 1 : link.getRevision() + 1);
            link.setRemark("inventory rename " + oldName + " -> " + newName);
            linkMapper.updateById(link);
            if (LINK_PRIMARY.equals(link.getLinkRole()) && StrUtil.isNotBlank(link.getAssetId())) {
                GovAsset asset = assetMapper.selectById(link.getAssetId());
                if (asset != null) {
                    asset.setLastSyncAt(now);
                    asset.setRevision(asset.getRevision() == null ? 1 : asset.getRevision() + 1);
                    assetMapper.updateById(asset);
                }
            }
        }
        log.info("renamed source links dsId={} {} -> {} count={}", dsId, oldName, newName, links.size());
    }

    /**
     * 资产 refresh：primary 对象不在 ig_ds_table → stale/degraded。
     */
    public Map<String, Object> reconcileAsset(GovAsset asset) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (asset == null) {
            r.put("skipped", true);
            return r;
        }
        GovAssetSourceLink primary = linkMapper.selectOne(new QueryWrapper<GovAssetSourceLink>().lambda()
                .eq(GovAssetSourceLink::getAssetId, asset.getId())
                .eq(GovAssetSourceLink::getLinkRole, LINK_PRIMARY)
                .eq(GovAssetSourceLink::getDeleteFlag, NOT_DELETE)
                .last("LIMIT 1"));
        if (primary == null) {
            r.put("skipped", true);
            r.put("reason", "no_primary_link");
            return r;
        }
        Long cnt = dsTableMapper.selectCount(new QueryWrapper<LhDsTable>().lambda()
                .eq(LhDsTable::getDsId, primary.getDsId())
                .eq(LhDsTable::getTableName, primary.getObjectName()));
        if (cnt != null && cnt > 0) {
            // 对象仍在：若曾 stale 可恢复 active（不自动恢复资产 status，避免覆盖人工 degraded）
            if (LINK_STALE.equalsIgnoreCase(primary.getStatus())) {
                primary.setStatus(LINK_ACTIVE);
                primary.setRevision(primary.getRevision() == null ? 1 : primary.getRevision() + 1);
                primary.setRemark("inventory restored");
                linkMapper.updateById(primary);
                r.put("linkRestored", true);
            }
            r.put("ok", true);
            r.put("present", true);
            return r;
        }
        return markOne(primary.getDsId(), primary.getObjectName(), "refresh_missing");
    }

    public Map<String, Object> markOne(String dsId, String objectName, String reason) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("dsId", dsId);
        r.put("objectName", objectName);
        r.put("reason", reason);
        List<GovAssetSourceLink> links = linkMapper.selectList(new QueryWrapper<GovAssetSourceLink>().lambda()
                .eq(GovAssetSourceLink::getDsId, dsId)
                .eq(GovAssetSourceLink::getObjectName, objectName)
                .eq(GovAssetSourceLink::getDeleteFlag, NOT_DELETE));
        boolean linkUpdated = false;
        boolean assetDegraded = false;
        String assetId = null;
        for (GovAssetSourceLink link : links) {
            if (!LINK_STALE.equalsIgnoreCase(link.getStatus())) {
                link.setStatus(LINK_STALE);
                link.setRevision(link.getRevision() == null ? 1 : link.getRevision() + 1);
                link.setRemark(StrUtil.blankToDefault(reason, "source missing"));
                linkMapper.updateById(link);
                linkUpdated = true;
            }
            if (LINK_PRIMARY.equals(link.getLinkRole())) {
                assetId = link.getAssetId();
                GovAsset asset = assetMapper.selectById(link.getAssetId());
                if (asset != null && !GovAssetStatusEnum.ARCHIVED.getValue().equalsIgnoreCase(asset.getStatus())
                        && !GovAssetStatusEnum.DEGRADED.getValue().equalsIgnoreCase(asset.getStatus())) {
                    asset.setStatus(GovAssetStatusEnum.DEGRADED.getValue());
                    asset.setRevision(asset.getRevision() == null ? 1 : asset.getRevision() + 1);
                    asset.setLastSyncAt(new Date());
                    asset.setLastSyncStatus("stale");
                    assetMapper.updateById(asset);
                    assetDegraded = true;
                }
            }
        }
        r.put("linkUpdated", linkUpdated);
        r.put("assetDegraded", assetDegraded);
        r.put("assetId", assetId);
        if (linkUpdated || assetDegraded) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("dsId", dsId);
            payload.put("objectName", objectName);
            payload.put("reason", reason);
            payload.put("assetId", assetId);
            platOutboxService.appendSoft(
                    "catalog.asset.source.stale",
                    "gov_asset",
                    StrUtil.blankToDefault(assetId, dsId + ":" + objectName),
                    payload,
                    Map.of("source", "GovAssetSourceReconcile"));
        }
        return r;
    }
}
