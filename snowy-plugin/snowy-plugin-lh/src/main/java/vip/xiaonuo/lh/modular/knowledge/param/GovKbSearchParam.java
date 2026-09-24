package vip.xiaonuo.lh.modular.knowledge.param;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class GovKbSearchParam {

    private String ws;
    /** workspace | platform；空且 includePlatform=false 时仅筛 ws */
    private String scope;
    /**
     * 为 true 时检索「当前 ws 的 workspace 条目 + 全局部署 platform 条目」，
     * 不跨其它工作空间（避免租户软泄漏）。
     */
    private Boolean includePlatform;
    private String query;
    private Integer topK;
    private List<String> cats;
    private Map<String, Object> filters;
}
