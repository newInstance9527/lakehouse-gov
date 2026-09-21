package vip.xiaonuo.lh.modular.workspace.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@Schema(description = "工作空间成员 VO")
public class GovWsMemberVo {

    private String id;
    private String wsCode;
    private String subjectType;
    private String subjectId;
    private String displayName;
    private String roleCode;
    private String scopeNote;
    private Date lastLogin;
    private String status;
}
