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
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 注册资产
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Getter
@Setter
public class GovAssetAddParam {

    @Schema(description = "数据源ID")
    @NotBlank(message = "dsId不能为空")
    private String dsId;

    @Schema(description = "源端对象名")
    @NotBlank(message = "objectName不能为空")
    private String objectName;

    @Schema(description = "分层")
    @NotBlank(message = "layer不能为空")
    private String layer;

    @Schema(description = "业务域")
    @NotBlank(message = "domain不能为空")
    private String domain;

    @Schema(description = "资产编码；空则按规则生成")
    private String assetCode;

    @Schema(description = "展示名")
    private String name;

    @Schema(description = "中文名")
    private String cnName;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "技术负责人")
    private String techOwner;

    @Schema(description = "业务负责人")
    private String bizOwner;

    @Schema(description = "敏感级别")
    private String sensitivity;

    @Schema(description = "资产类型；空则按源推断")
    private String assetKind;

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "引擎展示")
    private String engine;

    @Schema(description = "备注")
    private String remark;
}
