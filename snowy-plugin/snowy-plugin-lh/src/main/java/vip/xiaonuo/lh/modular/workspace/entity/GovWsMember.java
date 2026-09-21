package vip.xiaonuo.lh.modular.workspace.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

import java.util.Date;

/**
 * 工作空间成员 gov_ws_member（门户协作角色，≠ 引擎 ACL）
 */
@Getter
@Setter
@TableName("gov_ws_member")
@Schema(description = "工作空间成员")
public class GovWsMember extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String ws;
    private String remark;
    private String wsCode;
    private String subjectType;
    private String subjectId;
    private String displayName;
    private String roleCode;
    private String scopeNote;
    private Date lastLogin;
}
