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
package vip.xiaonuo.lh.modular.catalog.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

/**
 * 资产 ↔ 数据源对象绑定（gov_asset_source_link）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Getter
@Setter
@TableName("gov_asset_source_link")
@Schema(description = "资产源绑定")
public class GovAssetSourceLink extends CommonEntity {

    @TableId
    @Schema(description = "主键")
    private String id;

    @Schema(description = "乐观锁")
    private Integer revision;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "资产ID")
    private String assetId;

    @Schema(description = "数据源ID")
    private String dsId;

    @Schema(description = "冗余dsCode")
    private String dsCode;

    @Schema(description = "源端对象名")
    private String objectName;

    @Schema(description = "对象语义")
    private String objectKind;

    @Schema(description = "ig_ds_table.id")
    private String dsTableId;

    @Schema(description = "绑定角色 primary/upstream/reference")
    private String linkRole;
}
