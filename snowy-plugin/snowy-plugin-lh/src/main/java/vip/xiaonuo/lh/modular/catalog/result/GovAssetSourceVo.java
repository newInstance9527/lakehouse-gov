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
package vip.xiaonuo.lh.modular.catalog.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * 资产关联数据源 VO
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Getter
@Setter
@Schema(description = "资产源绑定VO")
public class GovAssetSourceVo {

    @Schema(description = "绑定ID")
    private String id;

    @Schema(description = "数据源ID")
    private String dsId;

    @Schema(description = "dsCode")
    private String dsCode;

    @Schema(description = "数据源名称")
    private String dsName;

    @Schema(description = "数据源类型")
    private String dsType;

    @Schema(description = "数据源状态")
    private String dsStatus;

    @Schema(description = "源端对象名")
    private String objectName;

    @Schema(description = "对象语义")
    private String objectKind;

    @Schema(description = "ig_ds_table.id")
    private String dsTableId;

    @Schema(description = "绑定角色")
    private String linkRole;

    @Schema(description = "绑定状态")
    private String status;
}
