package vip.xiaonuo.lh.modular.lineage.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

/**
 * 字段变更评估单
 *
 * @author lakehouse
 * @date 2026/9/23
 */
@Getter
@Setter
@TableName("gov_lineage_change_eval")
public class GovLineageChangeEval extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String ws;
    private String tableName;
    private String fieldName;
    private String toType;
    private Integer impactCount;
    private String impactJson;
    private String status;
    private Integer passed;
    private String ticketId;
    private String message;
}
