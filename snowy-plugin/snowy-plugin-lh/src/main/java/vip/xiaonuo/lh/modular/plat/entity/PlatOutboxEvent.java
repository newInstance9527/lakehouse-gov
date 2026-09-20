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
package vip.xiaonuo.lh.modular.plat.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 平台发件箱（plat_outbox_event）——跨系统事件 / 写审计载荷
 *
 * @author lakehouse
 * @date 2026/3/19
 */
@Getter
@Setter
@TableName("plat_outbox_event")
@Schema(description = "平台发件箱事件")
public class PlatOutboxEvent {

    @TableId
    @Schema(description = "主键")
    private String id;

    @Schema(description = "幂等事件ID")
    private String eventId;

    @Schema(description = "事件类型")
    private String eventType;

    @Schema(description = "聚合类型")
    private String aggregateType;

    @Schema(description = "聚合ID")
    private String aggregateId;

    @Schema(description = "载荷 JSON")
    private String payload;

    @Schema(description = "头 JSON")
    private String headers;

    @Schema(description = "pending/sent/failed/dead")
    private String status;

    @Schema(description = "重试次数")
    private Integer retryCount;

    @Schema(description = "下次重试")
    private Date nextRetryAt;

    @Schema(description = "链路ID")
    private String traceId;

    @Schema(description = "发送时间")
    private Date sentAt;

    @Schema(description = "最近错误")
    private String lastError;

    @Schema(description = "创建时间")
    private Date createTime;

    @Schema(description = "创建用户")
    private String createUser;
}
