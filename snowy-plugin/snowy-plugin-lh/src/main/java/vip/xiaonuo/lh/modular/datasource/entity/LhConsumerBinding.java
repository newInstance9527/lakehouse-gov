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
package vip.xiaonuo.lh.modular.datasource.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

import java.util.Date;

/**
 * 数据源消费者绑定实体（投影：ig_consumer_binding）
 * <p>status=启用态；sync_state=投影同步态（synced/stale/revoked/error）。</p>
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Getter
@Setter
@TableName("ig_consumer_binding")
@Schema(description = "数据源消费者绑定")
public class LhConsumerBinding extends CommonEntity {

    /** 主键 */
    @TableId
    @Schema(description = "主键")
    private Long id;

    /** 乐观锁版本 */
    @Schema(description = "乐观锁版本")
    private Integer revision;

    /** 数据源 ID */
    @Schema(description = "数据源ID")
    private String dsId;

    /** 消费方类型 flink/ds/trino/superset/sqlrest */
    @Schema(description = "消费方类型")
    private String consumerType;

    /** 消费方实例 ID */
    @Schema(description = "消费方实例ID")
    private String consumerId;

    /** 外部名绑定键 kind:ws__code；全局唯一 */
    @Schema(description = "外部名extId")
    private String extId;

    /** 投影配置 JSON */
    @Schema(description = "投影配置JSON")
    private String projection;

    /** 启用状态 */
    @Schema(description = "启用状态")
    private String status;

    /** 投影同步状态 */
    @Schema(description = "投影同步状态")
    private String syncState;

    /** 最近同步时间 */
    @Schema(description = "最近同步时间")
    private Date lastSyncAt;

    /** 最近同步错误 */
    @Schema(description = "最近同步错误")
    private String lastError;
}
