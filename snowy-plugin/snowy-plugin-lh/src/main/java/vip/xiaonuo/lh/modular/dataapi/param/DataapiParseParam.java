package vip.xiaonuo.lh.modular.dataapi.param;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DataapiParseParam {
    /** SQL 或 Groovy 文本，解析 #{param} */
    private String sql;
    private String engine = "SQL";
}
