package vip.xiaonuo.lh.modular.compute.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@TableName("cp_script_run")
public class CpScriptRun {

    @TableId
    private String id;
    private String runId;
    private String scriptId;
    private String ws;
    private String engine;
    private String env;
    private String status;
    private String message;
    private String logUri;
    private String dsWorkflowCode;
    private String dsInstanceId;
    private String gitSha;
    private Integer rowCount;
    private Long durMs;
    /** 抽样结果：columns、rows、log、source */
    private String resultJson;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
    private Date updateTime;
    private String updateUser;
}
