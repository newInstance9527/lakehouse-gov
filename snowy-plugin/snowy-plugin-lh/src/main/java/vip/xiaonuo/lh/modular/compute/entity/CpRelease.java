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
    /** script_publish 申请单号 SCR-xxx */
    private String applyTicketNo;
    private Integer prNumber;
    private String prUrl;
    /** open / merged / closed */
    private String prState;
    /** 评审分支 review/{id} */
    private String reviewBranch;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
    private Date updateTime;
    private String updateUser;
}
