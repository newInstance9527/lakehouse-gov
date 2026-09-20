package vip.xiaonuo.lh.modular.etl.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

/**
 * ETL DAG 任务头
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Getter
@Setter
@TableName("ig_etl_dag")
@Schema(description = "ETL DAG")
public class IgEtlDag extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String ws;
    private String dagCode;
    private String name;
    private String description;
    private String cron;
    private String owner;
    private String status;
    private String ver;
    private String env;
    private String defaultEngine;
    private String sla;
    private String dsWorkflowCode;
    private String gitRef;
}
