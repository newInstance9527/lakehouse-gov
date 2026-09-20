package vip.xiaonuo.lh.modular.quality.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

import java.math.BigDecimal;

@Getter
@Setter
@TableName("gov_dq_gate")
public class GovDqGate extends CommonEntity {
    @TableId
    private String id;
    private Integer revision;
    private String ws;
    private String assetId;
    private String tableName;
    private String layer;
    private BigDecimal minScore;
    private Integer blockOnFail;
}
