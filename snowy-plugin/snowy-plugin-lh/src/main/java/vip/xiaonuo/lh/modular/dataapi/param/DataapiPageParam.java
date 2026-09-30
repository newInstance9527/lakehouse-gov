package vip.xiaonuo.lh.modular.dataapi.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DataapiPageParam {
    @Schema(description = "关键词")
    private String q;
    private String keyword;
    private String state;
    private String domain;
    /** 按自定义标签过滤（tags_json 包含该字符串） */
    private String tag;
    private String ws;
    private String sortField;
    private String sortOrder;
}
