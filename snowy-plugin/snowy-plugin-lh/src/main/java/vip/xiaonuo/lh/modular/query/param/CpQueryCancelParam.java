package vip.xiaonuo.lh.modular.query.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

/**
 * 取消查询
 */
@Getter
@Setter
public class CpQueryCancelParam {

    @Schema(description = "门户 queryId")
    private String queryId;

    @Schema(description = "Trino 原生 query id")
    private String trinoQueryId;
}
