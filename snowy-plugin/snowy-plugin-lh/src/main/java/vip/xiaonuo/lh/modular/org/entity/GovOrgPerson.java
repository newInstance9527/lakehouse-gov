package vip.xiaonuo.lh.modular.org.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import vip.xiaonuo.common.pojo.CommonEntity;

/**
 * 部门非系统人员 gov_org_person（无登录账号）
 */
@Getter
@Setter
@TableName("gov_org_person")
@Schema(description = "部门非系统人员")
public class GovOrgPerson extends CommonEntity {

    @TableId
    private String id;
    private Integer revision;
    private String status;
    private String orgId;
    private String name;
    private String phone;
    private String email;
    private String jobTitle;
    private String remark;
}
