package vip.xiaonuo.lh.modular.ai.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovAiSessionCreateParam {

    private String ws;
    private String title;
    private String modelOverride;
}
