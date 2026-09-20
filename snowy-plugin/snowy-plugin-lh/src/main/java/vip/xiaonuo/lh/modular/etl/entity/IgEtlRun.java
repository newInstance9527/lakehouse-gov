package vip.xiaonuo.lh.modular.etl.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * ETL 运行实例
 *
 * @author lakehouse
 * @date 2026/9/19
 */
@Getter
@Setter
@TableName("ig_etl_run")
@Schema(description = "ETL 运行")
public class IgEtlRun {

    @TableId
    private String id;
    private String runId;
    private String dagId;
    private String ws;
    private String env;
    private String triggerType;
    private String status;
    private String dsRunId;
    private String olRunId;
    private String message;
    private Date startedAt;
    private Date finishedAt;
    private Date createTime;
    private String createUser;
}
