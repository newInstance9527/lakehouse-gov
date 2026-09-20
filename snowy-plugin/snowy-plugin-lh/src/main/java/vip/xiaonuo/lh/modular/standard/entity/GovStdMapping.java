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
package vip.xiaonuo.lh.modular.standard.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

/**
 * 源到标准映射（gov_std_mapping）
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Getter
@Setter
@TableName("gov_std_mapping")
@Schema(description = "源到标准映射")
public class GovStdMapping extends CommonEntity {

    @TableId
    @Schema(description = "主键")
    private String id;

    @Schema(description = "乐观锁")
    private Integer revision;

    @Schema(description = "状态 ok/warn/fail")
    private String status;

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "源对象")
    private String srcObject;

    @Schema(description = "源字段")
    private String srcField;

    @Schema(description = "标准字段名")
    private String stdFieldName;

    @Schema(description = "码值集ID")
    private String codeSetId;

    @Schema(description = "目标表")
    private String targetTable;

    @Schema(description = "映射规则")
    private String ruleText;

    @Schema(description = "数据源ID")
    private String dsId;

    @Schema(description = "资产ID")
    private String assetId;

    @Schema(description = "ETL作业ID")
    private String etlJobId;
}
