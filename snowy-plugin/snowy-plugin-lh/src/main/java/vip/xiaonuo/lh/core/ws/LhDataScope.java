package vip.xiaonuo.lh.core.ws;

import cn.hutool.core.util.StrUtil;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.common.exception.CommonException;
import vip.xiaonuo.lh.core.auth.LhLoginUsers;

import java.util.Locale;

/**
 * Portal list data scope: workspace default wall + scope=all privilege hard gate.
 */
public final class LhDataScope {

    public static final String SCOPE_WORKSPACE = "workspace";
    public static final String SCOPE_ALL = "all";
    public static final String WS_DEFAULT = "default";

    private LhDataScope() {
    }

    /** Cross-ws inspect: superAdmin / dataOps / bizAdmin */
    public static boolean canScopeAll() {
        return LhLoginUsers.hasAnyRole("superAdmin", "dataOps", "bizAdmin");
    }

    public static void requireScopeAll() {
        if (!canScopeAll()) {
            throw new CommonException("无权限查看全部工作空间数据（scope=all 仅限超管/运维/业务管理员）");
        }
    }

    /**
     * Normalize list scope: blank -> workspace; all requires privilege.
     * @return workspace | all
     */
    public static String normalizeListScope(String scope) {
        String s = StrUtil.blankToDefault(StrUtil.trim(scope), SCOPE_WORKSPACE).toLowerCase(Locale.ROOT);
        if (SCOPE_ALL.equals(s)) {
            requireScopeAll();
            return SCOPE_ALL;
        }
        return SCOPE_WORKSPACE;
    }

    /**
     * List ws: scope=all -> null (no filter); else explicit ws or default.
     */
    public static String resolveListWs(String ws, String scope) {
        String s = normalizeListScope(scope);
        if (SCOPE_ALL.equals(s)) {
            return null;
        }
        return StrUtil.blankToDefault(StrUtil.trim(ws), WS_DEFAULT);
    }

    /**
     * Blank ws means no filter: logged-in non-privileged -> 403; no login (jobs) allowed.
     */
    public static void assertBlankWsAllowed() {
        SaBaseLoginUser u = LhLoginUsers.currentUserOrNull();
        if (u == null) {
            return;
        }
        requireScopeAll();
    }

    /** Personal objects: force mine for non-privileged */
    public static boolean forceMineOnly() {
        return !canScopeAll();
    }
}
