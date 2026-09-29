package vip.xiaonuo.lh.modular.dataapi.param;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DataapiIdParam {
    @NotBlank(message = "id 不能为空")
    private String id;
    private String ws;
    /** 发布审批单号（api_publish / API-xxx）；requirePublishTicket=true 时必填 */
    private String publishTicketNo;
    /** 版本回退：SQLREST commitId */
    private Long commitId;
    /** 版本回退：目标 version（可选，用于校验） */
    private Integer version;
}
