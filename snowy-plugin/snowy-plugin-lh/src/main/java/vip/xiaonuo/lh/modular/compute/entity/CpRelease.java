package vip.xiaonuo.lh.modular.compute.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@TableName("cp_release")
public class CpRelease {

    @TableId
    private String id;
    private String ws;
    private String scriptId;
    private String pkg;
    private String scriptName;
    private String scriptPath;
    private String engine;
    private String env;
    private String gitSha;
    private String gitTag;
    private String status;
    private String resultLabel;
    private String gatesJson;
    private String dsWorkflowCode;
    private String rolledToTag;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
    private Date updateTime;
    private String updateUser;
}
