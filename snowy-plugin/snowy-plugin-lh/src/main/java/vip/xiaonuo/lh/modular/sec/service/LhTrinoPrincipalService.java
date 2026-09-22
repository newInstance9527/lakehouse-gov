package vip.xiaonuo.lh.modular.sec.service;

import vip.xiaonuo.lh.modular.sec.entity.LhTrinoPrincipal;
import vip.xiaonuo.lh.modular.sec.param.LhTrinoPrincipalBindParam;

import java.util.List;
import java.util.Map;

/**
 * 人查身份。不管表权限，不管作业账号。
 */
public interface LhTrinoPrincipalService {

    /** 当前登录用户的 Gravitino 主体；未映射则拒绝，不回落服务账号 */
    LhTrinoPrincipal requireCurrent();

    LhTrinoPrincipal requireByPortalUserId(String portalUserId);

    LhTrinoPrincipal findActive(String portalUserId);

    LhTrinoPrincipal bind(LhTrinoPrincipalBindParam param);

    List<LhTrinoPrincipal> listActive();

    /**
     * 给 Trino 系统访问控制合并用的 impersonation 片段。
     * 只列出可代执行的主体名，不含库表权限。
     */
    Map<String, Object> impersonationFragment();

    /**
     * 将当前主体白名单下发到 {@code lh.trino.impersonation-rules-path} 指向的 rules.json。
     * 仅超管；路径未配置则 skipped。
     */
    Map<String, Object> syncImpersonationRules();
}
