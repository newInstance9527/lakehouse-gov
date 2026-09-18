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
 * 数据源表清单实体（ig_ds_table）
 * <p>登记源端表 / Topic / 路径 / 集合等对象，供 ETL 编排与资产目录引用。</p>
 *
 * @author lakehouse
 * @date 2026/3/18
 */
@Getter
@Setter
@TableName("ig_ds_table")
@Schema(description = "数据源表清单")
public class LhDsTable extends CommonEntity {

    /** 主键 */
    @TableId
    @Schema(description = "主键")
    private String id;

    /** 乐观锁版本 */
    @Schema(description = "乐观锁版本")
    private Integer revision;

    /** 数据源 ID */
    @Schema(description = "数据源ID")
    private String dsId;

    /** 源端对象名 */
    @Schema(description = "表名/Topic/路径")
    private String tableName;

    /** 中文名 */
    @Schema(description = "中文名")
    private String cnName;

    /** 业务注释（列名 comment 为保留字，映射 comment_txt） */
    @Schema(description = "业务注释")
    private String commentTxt;

    /** 编码 */
    @Schema(description = "编码")
    private String encoding;

    /** 引擎 */
    @Schema(description = "引擎")
    private String engine;

    /** 行数估算 */
    @Schema(description = "行数")
    private Long rowCount;

    /** 最近同步时间 */
    @Schema(description = "最近同步时间")
    private Date syncedAt;

    /** 状态 */
    @Schema(description = "状态")
    private String status;

    /** 备注 */
    @Schema(description = "备注")
    private String remark;
}
