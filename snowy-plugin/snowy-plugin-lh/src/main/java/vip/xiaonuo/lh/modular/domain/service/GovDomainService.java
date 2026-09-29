package vip.xiaonuo.lh.modular.domain.service;

import vip.xiaonuo.lh.modular.domain.param.GovDomainUpsertParam;

import java.util.List;
import java.util.Map;

/**
 * 业务数据域 SoT
 */
public interface GovDomainService {

    List<Map<String, Object>> list(String ws, String status, String q);

    /** 启用中域选项，按 sortNo 排序 */
    List<Map<String, Object>> options();

    Map<String, Object> detail(String code);

    Map<String, Object> create(GovDomainUpsertParam param);

    Map<String, Object> update(String code, GovDomainUpsertParam param);

    Map<String, Object> enable(String code);

    Map<String, Object> disable(String code);

    void softDelete(String code);

    Map<String, Object> usage(String code);

    /**
     * 规范化域码：trim、小写；product→goods；内置中文别名；再按 domain_code/name/extra_json.aliases 查表。
     * @return 规范码，无法解析时 null
     */
    String resolveCode(String raw);

    /** resolve 且必须 active，否则抛 CommonException */
    String requireActive(String code);

    /** 展示名，找不到则回落为 code */
    String labelOf(String code);
}
