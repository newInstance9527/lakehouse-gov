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
 * 数据源实体（SoT：ig_datasource）
 * <p>凭证仅存 vault_path，明文禁止落本表；连接脱敏信息存 conn_masked(JSON)。</p>
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Getter
@Setter
@TableName("ig_datasource")
@Schema(description = "数据源")
public class LhDatasource extends CommonEntity {

    /** 主键（雪花） */
    @TableId
    @Schema(description = "主键")
    private String id;

    /** 乐观锁版本 */
    @Schema(description = "乐观锁版本")
    private Integer revision;

    /** 工作空间 */
    @Schema(description = "工作空间")
    private String ws;

    /** 对外稳定编码 */
    @Schema(description = "对外稳定编码")
    private String dsCode;

    /** 数据源名称 */
    @Schema(description = "数据源名称")
    private String name;

    /** 类型编码 */
    @Schema(description = "类型(mysql/pg/kafka/...)")
    private String type;

    /** 分类编码 */
    @Schema(description = "分类(rdb/dw/mq/...)")
    private String category;

    /** 脱敏连接信息 JSON */
    @Schema(description = "脱敏连接信息JSON")
    private String connMasked;

    /** 连接主机 */
    @Schema(description = "连接主机")
    private String endpointHost;

    /** 端口 */
    @Schema(description = "端口")
    private String endpointPort;

    /** 库名/路径/namespace */
    @Schema(description = "库名/路径/namespace")
    private String databaseName;

    /** 接入方式 */
    @Schema(description = "接入方式")
    private String accessMode;

    /** 表清单摘要串 */
    @Schema(description = "表/Topic/路径清单摘要")
    private String schemaSummary;

    /** 延迟文案 */
    @Schema(description = "延迟/接入文案")
    private String lagDesc;

    /** 健康分 */
    @Schema(description = "健康分")
    private Integer healthScore;

    /** 关联资产名 */
    @Schema(description = "关联湖内资产名")
    private String assetName;

    /** 配置版本 */
    @Schema(description = "配置版本")
    private String ver;

    /** Vault 路径 */
    @Schema(description = "密钥Vault路径")
    private String vaultPath;

    /** 状态 online/warn/paused/revoked */
    @Schema(description = "状态")
    private String status;

    /** 用途 JSON 数组 */
    @Schema(description = "用途JSON")
    private String purposes;

    /** 负责人 */
    @Schema(description = "负责人")
    private String owner;

    /** 密级/层级 */
    @Schema(description = "密级/层级")
    private String level;

    /** 最近连通成功时间 */
    @Schema(description = "最近连通成功时间")
    private Date lastOkAt;

    /** 内容哈希 */
    @Schema(description = "内容哈希")
    private String contentHash;

    /** 备注 */
    @Schema(description = "备注")
    private String remark;
}
