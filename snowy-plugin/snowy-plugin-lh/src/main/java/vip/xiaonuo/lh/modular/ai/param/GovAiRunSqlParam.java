package vip.xiaonuo.lh.modular.ai.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovAiRunSqlParam {

    private String sessionId;
    private String turnId;
    private String ws;
    private String sql;
    /** 二次确认标记 */
    private Boolean confirmed;
}
