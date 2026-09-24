package vip.xiaonuo.lh.modular.org.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "部门非系统人员")
public class GovOrgPersonVo {

    private String id;
    private String orgId;
    /** 部门名称（含下级汇总时便于区分） */
    private String orgName;
    private String name;
    private String phone;
    private String email;
    private String jobTitle;
    private String remark;
    private String status;
}
