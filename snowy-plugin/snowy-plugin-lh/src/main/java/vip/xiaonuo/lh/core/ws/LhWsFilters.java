package vip.xiaonuo.lh.core.ws;

import cn.hutool.core.util.StrUtil;

/**
 * 工作空间列表过滤：空 / 空白 = 全局（不过滤）；显式传才筛归属。
 * 写路径仍用 blankToDefault(ws, "default") 绑默认归属。
 */
public final class LhWsFilters {

    private LhWsFilters() {
    }

    /** 列表用：trim 后空则 null（表示不过滤） */
    public static String listWs(String ws) {
        String t = StrUtil.trim(ws);
        return StrUtil.isBlank(t) ? null : t;
    }

    public static boolean hasWs(String ws) {
        return StrUtil.isNotBlank(listWs(ws));
    }
}
