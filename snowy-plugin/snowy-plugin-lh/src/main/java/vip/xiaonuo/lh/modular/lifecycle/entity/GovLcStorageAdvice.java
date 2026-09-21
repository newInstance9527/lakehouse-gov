package vip.xiaonuo.lh.modular.lifecycle.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

import java.util.Date;

/**
 * 存储趋势治理建议（gov_lc_storage_advice）
 */
@Getter
@Setter
@TableName("gov_lc_storage_advice")
@Schema(description = "存储趋势治理建议")
public class GovLcStorageAdvice extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    /** 建议所属日期 */
    private Date dt;
    private String fqtn;
    private String kind;
    private Integer priority;
    private Long estReclaimBytes;
    private String confidence;
    private String reason;
    private String linkedRunId;
}
