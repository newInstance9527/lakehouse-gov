package vip.xiaonuo.lh.core.ws;

import cn.hutool.core.util.StrUtil;

/**
 * Workspace list filter: blank = no filter, but logged-in user must have scope=all privilege.
 * Write paths still use blankToDefault(ws, "default").
 */
public final class LhWsFilters {

    private LhWsFilters() {
    }

    /** List: trim blank -> null (no filter); privilege check when user present */
    public static String listWs(String ws) {
        String t = StrUtil.trim(ws);
        if (StrUtil.isBlank(t)) {
            LhDataScope.assertBlankWsAllowed();
            return null;
        }
        return t;
    }

    public static boolean hasWs(String ws) {
        return StrUtil.isNotBlank(StrUtil.trim(ws));
    }
}
