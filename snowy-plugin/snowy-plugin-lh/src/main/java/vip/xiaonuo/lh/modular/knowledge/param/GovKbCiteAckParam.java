package vip.xiaonuo.lh.modular.knowledge.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class GovKbCiteAckParam {

    private String ws;
    private List<String> entryIds;
    private List<String> chunkIds;
}
