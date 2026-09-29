package vip.xiaonuo.lh.modular.quality.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
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
    /** 可选：清空时须写回 NULL */
    @TableField(insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String assetId;
    /** 可选：空=整层门禁 */
    @TableField(insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String tableName;
    /** 可选：与 tableName 至少填其一 */
    @TableField(insertStrategy = FieldStrategy.ALWAYS, updateStrategy = FieldStrategy.ALWAYS)
    private String layer;
    private BigDecimal minScore;
    private Integer blockOnFail;
}
