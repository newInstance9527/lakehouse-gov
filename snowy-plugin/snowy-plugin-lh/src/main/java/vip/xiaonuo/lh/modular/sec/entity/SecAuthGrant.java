package vip.xiaonuo.lh.modular.sec.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@TableName("sec_auth_grant")
public class SecAuthGrant {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String ticketId;
    private String subjectType;
    private String subjectId;
    private String assetId;
    private String gravAssetId;
    private String privilege;
    private String columnMask;
    private String rowFilter;
    private String gravPolicyId;
    private Integer gravProjected;
    private Date effectiveAt;
    private Date expiresAt;
    private String deleteFlag;
    private Date createTime;
    private String createUser;
    private Date updateTime;
    private String updateUser;
}
