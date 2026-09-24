package vip.xiaonuo.lh.modular.apply.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ApplyTicketPageParam {

    @Schema(description = "当前页")
    private Integer current;

    @Schema(description = "每页条数")
    private Integer size;

    @Schema(description = "状态")
    private String status;

    @Schema(description = "类型 table_read / lake_export；空=全部")
    private String ticketType;

    @Schema(description = "工作空间；空=不过滤（全局）")
    private String ws;
}
