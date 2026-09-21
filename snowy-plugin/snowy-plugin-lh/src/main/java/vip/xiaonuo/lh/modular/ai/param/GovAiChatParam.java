package vip.xiaonuo.lh.modular.ai.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GovAiChatParam {

    private String sessionId;
    private String ws;
    private String text;
    /** 前端芯片 scene / chipId */
    private String scene;
    private String modelOverride;
}
