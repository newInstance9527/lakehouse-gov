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
package vip.xiaonuo.lh.modular.datasource.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.entity.GovAssetSourceLink;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetMapper;
import vip.xiaonuo.lh.modular.catalog.mapper.GovAssetSourceLinkMapper;
import vip.xiaonuo.lh.modular.datasource.result.LhDatasourceVo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 数据源 VO 关联资产：读 {@code gov_asset_source_link}（替代演示字段 asset_name SoT）。
 */
@Component
public class LhDatasourceLinkedAssetFiller {

    private static final String NOT_DELETE = "NOT_DELETE";
    private static final String LINK_PRIMARY = "primary";

    @Resource
    private GovAssetSourceLinkMapper linkMapper;
    @Resource
    private GovAssetMapper assetMapper;

    public void fill(LhDatasourceVo vo) {
        if (vo == null || StrUtil.isBlank(vo.getId())) {
            return;
        }
        fill(List.of(vo));
    }

    public void fill(Collection<LhDatasourceVo> vos) {
        if (vos == null || vos.isEmpty()) {
            return;
        }
        List<String> dsIds = vos.stream()
                .filter(Objects::nonNull)
                .map(LhDatasourceVo::getId)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        if (dsIds.isEmpty()) {
            return;
        }
        List<GovAssetSourceLink> links = linkMapper.selectList(new QueryWrapper<GovAssetSourceLink>().lambda()
                .in(GovAssetSourceLink::getDsId, dsIds)
                .eq(GovAssetSourceLink::getDeleteFlag, NOT_DELETE)
                .orderByAsc(GovAssetSourceLink::getLinkRole)
                .orderByAsc(GovAssetSourceLink::getObjectName));
        if (links.isEmpty()) {
            for (LhDatasourceVo vo : vos) {
                if (vo != null && vo.getLinkedAssets() == null) {
                    vo.setLinkedAssets(List.of());
                }
            }
            return;
        }
        List<String> assetIds = links.stream()
                .map(GovAssetSourceLink::getAssetId)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        Map<String, GovAsset> assetMap = assetIds.isEmpty() ? Map.of()
                : assetMapper.selectBatchIds(assetIds).stream()
                .filter(a -> a != null && NOT_DELETE.equals(a.getDeleteFlag()))
                .collect(Collectors.toMap(GovAsset::getId, a -> a, (a, b) -> a, LinkedHashMap::new));

        Map<String, List<Map<String, Object>>> byDs = new LinkedHashMap<>();
        for (GovAssetSourceLink link : links) {
            GovAsset asset = assetMap.get(link.getAssetId());
            if (asset == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("assetId", asset.getId());
            row.put("assetCode", asset.getAssetCode());
            row.put("name", asset.getName());
            row.put("cnName", asset.getCnName());
            row.put("objectName", link.getObjectName());
            row.put("linkRole", link.getLinkRole());
            row.put("linkStatus", link.getStatus());
            row.put("status", asset.getStatus());
            row.put("layer", asset.getLayer());
            byDs.computeIfAbsent(link.getDsId(), k -> new ArrayList<>()).add(row);
        }
        for (LhDatasourceVo vo : vos) {
            if (vo == null || StrUtil.isBlank(vo.getId())) {
                continue;
            }
            List<Map<String, Object>> list = byDs.getOrDefault(vo.getId(), List.of());
            vo.setLinkedAssets(list);
            if (StrUtil.isBlank(vo.getAsset()) && !list.isEmpty()) {
                Map<String, Object> primary = list.stream()
                        .filter(m -> LINK_PRIMARY.equalsIgnoreCase(String.valueOf(m.get("linkRole"))))
                        .findFirst()
                        .orElse(list.get(0));
                Object code = primary.get("assetCode");
                if (code != null) {
                    vo.setAsset(String.valueOf(code));
                }
            }
        }
    }
}
