package vip.xiaonuo.lh.modular.etl.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

/**
 * ETL DAG 边
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Getter
@Setter
@TableName("ig_etl_edge")
@Schema(description = "ETL 边")
public class IgEtlEdge extends CommonEntity {

    @TableId
    private String id;
    private String dagId;
    private String fromNodeKey;
    private String toNodeKey;
    private String label;
    private Integer sortNo;
}
