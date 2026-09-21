package vip.xiaonuo.lh.modular.lifecycle.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * 立即执行日作业
 */
@Getter
@Setter
public class GovLcRunNowParam {

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "备注")
    private String remark;
}
