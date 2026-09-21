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

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 资产列表/详情 VO
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Getter
@Setter
@Schema(description = "资产VO")
public class GovAssetVo {

    @Schema(description = "主键")
    private String id;

    @Schema(description = "编码")
    private String assetCode;

    @Schema(description = "展示名")
    private String name;

    @Schema(description = "中文名")
    private String cnName;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "类型")
    private String assetKind;

    @Schema(description = "分层")
    private String layer;

    @Schema(description = "分层标签")
    private String layerLabel;

    @Schema(description = "业务域")
    private String domainCode;

    @Schema(description = "敏感级别")
    private String sensitivity;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "技术负责人（存 user_id）")
    private String techOwner;

    @Schema(description = "技术负责人显示名（sys_user.name）")
    private String techOwnerName;

    @Schema(description = "业务负责人（存 user_id）")
    private String bizOwner;

    @Schema(description = "业务负责人显示名（sys_user.name）")
    private String bizOwnerName;

    @Schema(description = "登记人（create_user，存 user_id）")
    private String createUser;

    @Schema(description = "登记人显示名（sys_user.name）")
    private String createUserName;

    @Schema(description = "引擎")
    private String engine;

    @Schema(description = "黄金")
    private Boolean isGold;

    @Schema(description = "OM FQN")
    private String omFqn;

    @Schema(description = "Gravitino 资产指针 id（cb_grav_asset_ref）")
    private String gravAssetId;

    @Schema(description = "最近同步")
    private Date lastSyncAt;

    @Schema(description = "同步状态")
    private String lastSyncStatus;

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "乐观锁")
    private Integer revision;

    @Schema(description = "更新时间")
    private Date updateTime;

    @Schema(description = "关联源摘要")
    private String sourceSummary;

    @Schema(description = "主源对象名")
    private String objectName;

    @Schema(description = "主源ID")
    private String primaryDsId;

    @Schema(description = "主源名称")
    private String primaryDsName;

    @Schema(description = "主源类型（MySQL / Kafka / …）")
    private String primaryDsType;

    @Schema(description = "主源编码")
    private String primaryDsCode;

    @Schema(description = "主源绑定状态 active/stale/detached")
    private String linkStatus;

    @Schema(description = "列表质量分（批量聚合；无规则为 null）")
    private java.math.BigDecimal qualityScore;

    @Schema(description = "关联源列表（详情）")
    private List<GovAssetSourceVo> sources;

    @Schema(description = "扩展：schema/quality/lineage 占位")
    private Map<String, Object> extras;
}
