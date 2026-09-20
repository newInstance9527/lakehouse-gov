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
package vip.xiaonuo.lh.modular.catalog.preview;

import lombok.Builder;
import lombok.Getter;
import vip.xiaonuo.lh.modular.catalog.entity.GovAsset;
import vip.xiaonuo.lh.modular.catalog.entity.GovAssetSourceLink;
import vip.xiaonuo.lh.modular.datasource.entity.LhDatasource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资产预览上下文（授权已在外层完成）。
 */
@Getter
@Builder
public class GovAssetPreviewContext {

    private final GovAsset asset;
    private final List<GovAssetSourceLink> links;
    private final GovAssetSourceLink primaryLink;
    private final LhDatasource primaryDs;
    private final String objectName;
    private final int limit;
    /** 调用方预填的 assetId/assetCode 等 */
    private final Map<String, Object> base;

    public Map<String, Object> newResult() {
        Map<String, Object> r = new LinkedHashMap<>();
        if (base != null) {
            r.putAll(base);
        }
        return r;
    }
}
