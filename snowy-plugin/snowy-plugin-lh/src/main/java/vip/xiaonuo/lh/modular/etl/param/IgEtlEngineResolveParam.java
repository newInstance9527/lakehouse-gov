package vip.xiaonuo.lh.modular.etl.param;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IgEtlEngineResolveParam {
    private String nodeType;
    private String confJson;
    private Object conf;
}
