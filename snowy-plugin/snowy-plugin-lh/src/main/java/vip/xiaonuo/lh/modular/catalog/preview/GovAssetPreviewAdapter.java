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

import java.util.Map;

/**
 * 资产数据预览适配器：授权后按源类型探查，互不替代分析路径（Trino→Grav）。
 */
public interface GovAssetPreviewAdapter {

    /** 越小越优先 */
    int order();

    boolean supports(GovAssetPreviewContext ctx);

    /**
     * @return 预览结果；{@code ok=true} 视为命中结束；{@code ok=false} 且 {@code tryNext=true} 时尝试下一适配器
     */
    Map<String, Object> preview(GovAssetPreviewContext ctx);

    static Map<String, Object> markTryNext(Map<String, Object> r) {
        if (r != null) {
            r.put("tryNext", true);
        }
        return r;
    }

    static boolean shouldTryNext(Map<String, Object> r) {
        return r == null || Boolean.TRUE.equals(r.get("tryNext"))
                || (!Boolean.TRUE.equals(r.get("ok")) && Boolean.TRUE.equals(r.get("degraded"))
                && Boolean.TRUE.equals(r.get("tryNext")));
    }
}
