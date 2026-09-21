package vip.xiaonuo.lh.modular.compliance.result;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@Schema(description = "主体索引")
public class GovDelSubjectMapVo {

    private String id;
    private String ws;
    private String status;
    private String subjectType;
    private String carrier;
    private String carrierLabel;
    private String objectFqn;
    private String idColumn;
    private String joinPath;
    private String deleteMode;
    private String scopeTpl;
    private String owner;
    private String sensitivity;
    private Date verifiedAt;
    private String remark;
}
