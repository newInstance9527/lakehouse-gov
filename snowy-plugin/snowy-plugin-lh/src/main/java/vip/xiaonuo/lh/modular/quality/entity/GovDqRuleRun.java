package vip.xiaonuo.lh.modular.quality.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Date;

@Getter
@Setter
@TableName("gov_dq_rule_run")
public class GovDqRuleRun {
    @TableId
    private String id;
    private String ws;
    private String ruleId;
    private Integer pass;
    private Long okRows;
    private Long failRows;
    private BigDecimal okPct;
    private Integer blocked;
    private String jobRunId;
    private String message;
    private Date ranAt;
    private Date createTime;
    private String createUser;
}
