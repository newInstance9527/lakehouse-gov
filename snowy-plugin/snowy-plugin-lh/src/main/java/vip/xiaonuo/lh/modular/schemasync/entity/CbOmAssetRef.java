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
package vip.xiaonuo.lh.modular.schemasync.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

import java.util.Date;

/**
 * OpenMetadata 表指针（cb_om_asset_ref）
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Getter
@Setter
@TableName("cb_om_asset_ref")
@Schema(description = "OM表指针")
public class CbOmAssetRef extends CommonEntity {

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

    @Schema(description = "gov_asset.id")
    private String assetId;

    @Schema(description = "OM实体类型 table/topic/searchIndex/container")
    private String omEntityType;

    @Schema(description = "OM服务类型 Kafka/Mysql/...")
    private String omServiceType;

    @Schema(description = "OM服务族 DATABASE/MESSAGING/...")
    private String omFamily;

    @Schema(description = "grav资产ID，可空")
    private String gravAssetId;

    @Schema(description = "OM FQN")
    private String omFqn;

    @Schema(description = "OM表ID")
    private String omTableId;

    @Schema(description = "OM版本戳")
    private String omRevision;

    @Schema(description = "已同步grav_revision")
    private Long syncedGravRev;

    @Schema(description = "最近同步")
    private Date lastSyncAt;

    @Schema(description = "同步状态")
    private String lastSyncStatus;

    @Schema(description = "错误")
    private String lastError;

    @Schema(description = "漂移")
    private Integer driftFlag;
}
