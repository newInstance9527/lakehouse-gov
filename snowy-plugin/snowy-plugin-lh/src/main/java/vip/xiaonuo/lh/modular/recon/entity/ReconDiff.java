package vip.xiaonuo.lh.modular.recon.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 对账差异下钻（recon_diff · §33.2）
 */
@Getter
@Setter
@TableName("recon_diff")
@Schema(description = "对账差异")
public class ReconDiff {

    @TableId
    private String id;
    private String ws;
    private String ruleId;
    private String metricCode;
    private String lakeTable;
    private String partitionKey;
    private String diffType;
    private String pkValue;
    private String detailJson;
    private Date checkedAt;
    private Date createTime;
    private String createUser;
}
