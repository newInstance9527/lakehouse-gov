package vip.xiaonuo.lh.modular.sec.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@TableName("sec_job_sa")
public class SecJobSa {

    @TableId
    private String id;
    private Integer revision;
    private String ws;
    private String saName;
    private String domain;
    private String jobBind;
    private String privilegeScope;
    private String vaultPath;
    private String status;
    private Date expireAt;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
    private Date updateTime;
    private String updateUser;
}
