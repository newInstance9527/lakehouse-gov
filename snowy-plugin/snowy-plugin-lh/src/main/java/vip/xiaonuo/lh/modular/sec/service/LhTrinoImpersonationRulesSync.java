package vip.xiaonuo.lh.modular.sec.service;

import vip.xiaonuo.lh.modular.sec.entity.LhTrinoPrincipal;

import java.util.List;
import java.util.Map;

/**
 * 把门户主体白名单下发到 Trino {@code rules.json} 的 impersonation 段。
 */
public interface LhTrinoImpersonationRulesSync {

    /**
     * 按当前 active 人类主体重写配置路径上的 rules.json。
     * 路径未配置时返回 skipped；写失败返回 degraded（不抛）。
     */
    Map<String, Object> sync();

    /** 由已加载的主体列表构建 impersonation 规则（snake_case） */
    List<Map<String, Object>> buildImpersonationRules(List<LhTrinoPrincipal> rows);
}
