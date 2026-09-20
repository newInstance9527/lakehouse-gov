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
 * 编辑资产门户字段
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Getter
@Setter
public class GovAssetEditParam {

    @Schema(description = "主键")
    @NotBlank(message = "id不能为空")
    private String id;

    @Schema(description = "展示名")
    private String name;

    @Schema(description = "中文名")
    private String cnName;

    @Schema(description = "描述草稿")
    private String description;

    @Schema(description = "分层")
    private String layer;

    @Schema(description = "业务域")
    private String domain;

    @Schema(description = "技术负责人")
    private String techOwner;

    @Schema(description = "业务负责人")
    private String bizOwner;

    @Schema(description = "敏感级别")
    private String sensitivity;

    @Schema(description = "引擎")
    private String engine;

    @Schema(description = "黄金标记 0/1")
    private Integer isGold;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "乐观锁；不传则服务端自增")
    private Integer revision;
}
