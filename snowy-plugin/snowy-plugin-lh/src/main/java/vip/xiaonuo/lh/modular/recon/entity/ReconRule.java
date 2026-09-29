package vip.xiaonuo.lh.modular.recon.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 对账规则（recon_rule · §33.1）
 */
@Getter
@Setter
@TableName("recon_rule")
@Schema(description = "对账规则")
public class ReconRule extends CommonEntity {

    @TableId
    private String id;
    private String ws;
    private String ruleCode;
    private String ruleName;
    private String ruleType;
    private String lakeTable;
    private String ckDatabase;
    private String ckTable;
    private String metricCode;
    private BigDecimal threshold;
    private Integer enabled;
    private String cron;
    private String extraJson;
    private String lastStatus;
    private Date lastChecked;
}
