package vip.xiaonuo.lh.modular.etl.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

/**
 * ETL DAG 节点
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Getter
@Setter
@TableName("ig_etl_node")
@Schema(description = "ETL 节点")
public class IgEtlNode extends CommonEntity {

    @TableId
    private String id;
    private String dagId;
    private String nodeKey;
    private String nodeType;
    private String name;
    private String meta;
    private Integer posX;
    private Integer posY;
    private String confJson;
    private String resolvedEngine;
}
