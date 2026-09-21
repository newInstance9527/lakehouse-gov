package vip.xiaonuo.lh.modular.compute.param;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CpScriptCreateParam {
    private String ws;
    private String folder;
    private String name;
    private String engine;
    private String env;
    private String sql;
}
