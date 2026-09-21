package vip.xiaonuo.lh.modular.compliance.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovDelRequestPageParam {

    @Schema(description = "工作空间")
    private String ws;

    @Schema(description = "状态过滤")
    private String status;

    @Schema(description = "请求类型过滤")
    private String reqType;

    @Schema(description = "关键字：请求号 / 掩码主体 / 来源单号")
    private String kw;

    private Long current;
    private Long size;
}
