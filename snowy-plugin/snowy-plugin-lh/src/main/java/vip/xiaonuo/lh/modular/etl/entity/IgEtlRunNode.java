package vip.xiaonuo.lh.modular.etl.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * ETL 运行节点态
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Getter
@Setter
@TableName("ig_etl_run_node")
@Schema(description = "ETL 运行节点")
public class IgEtlRunNode {

    @TableId
    private String id;
    private String runId;
    private String nodeKey;
    private String nodeType;
    private String status;
    private String engine;
    private String logRef;
    private String message;
    private Date startedAt;
    private Date finishedAt;
    private Date createTime;
}
