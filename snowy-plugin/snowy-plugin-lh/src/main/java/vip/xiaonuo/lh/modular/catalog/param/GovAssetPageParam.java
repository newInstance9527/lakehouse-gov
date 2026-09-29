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
package vip.xiaonuo.lh.modular.catalog.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * 资产分页查询
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Getter
@Setter
public class GovAssetPageParam {

    @Schema(description = "关键词 q")
    private String q;

    @Schema(description = "关键词 keyword，与 q 等价")
    private String keyword;

    @Schema(description = "分层")
    private String layer;

    @Schema(description = "业务域")
    private String domain;

    @Schema(description = "domain 别名 domainCode")
    private String domainCode;

    @Schema(description = "数据源ID")
    private String dsId;

    @Schema(description = "数据源名称/编码筛选")
    private String source;

    @Schema(description = "资产类型 kind")
    private String kind;

    @Schema(description = "assetKind 别名")
    private String assetKind;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "归属工作空间（scope=workspace 时使用）")
    private String ws;

    @Schema(description = "列表范围：workspace（默认）/ all")
    private String scope;

    @Schema(description = "排序字段")
    private String sortField;

    @Schema(description = "排序方式")
    private String sortOrder;
}
