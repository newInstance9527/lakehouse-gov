package vip.xiaonuo.lh.modular.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

/**
 * 业务术语类型化边（gov_kb_term_edge）
 */
@Getter
@Setter
@TableName("gov_kb_term_edge")
@Schema(description = "业务术语边")
public class GovKbTermEdge extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    /** active | broken */
    private String status;
    private String ws;
    private String remark;
    private String fromEntryId;
    /** synonym|broader|related|implements_metric|maps_column|governed_by_std */
    private String edgeType;
    /** term|metric|asset|standard|label */
    private String toKind;
    private String toId;
    private String toLabel;
}
