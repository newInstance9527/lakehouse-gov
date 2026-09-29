package vip.xiaonuo.lh.modular.quality.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

@Getter
@Setter
@TableName("gov_dq_rule")
public class GovDqRule extends CommonEntity {
    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String ruleCode;
    private String ruleType;
    private String ruleLevel;
    private String scope;
    private String tableName;
    private String assetId;
    private String fieldName;
    private String layer;
    private String exprText;
    private String severity;
    private Integer enabled;
    private String omTestFqn;
    /** 绑定 gov_std_code.code_set_id（枚举/码值） */
    private String stdCodeSetId;
}
