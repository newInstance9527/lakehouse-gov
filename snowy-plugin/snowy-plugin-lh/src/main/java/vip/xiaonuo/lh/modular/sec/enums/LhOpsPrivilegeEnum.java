package vip.xiaonuo.lh.modular.sec.enums;

import cn.hutool.core.util.StrUtil;
import vip.xiaonuo.common.exception.CommonException;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 操作权限 privilege：EDIT / DELETE 分权；MANAGE 同时覆盖改与删。
 * 表级读用 SELECT（不在本枚举校验范围内，由 table_read 票种写入）。
 */
public enum LhOpsPrivilegeEnum {

    EDIT("EDIT"),
    DELETE("DELETE"),
    MANAGE("MANAGE");

    private final String value;

    LhOpsPrivilegeEnum(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static LhOpsPrivilegeEnum of(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        for (LhOpsPrivilegeEnum e : values()) {
            if (e.value.equals(v)) {
                return e;
            }
        }
        return null;
    }

    /** resource_manage 票种允许的 privilege */
    public static LhOpsPrivilegeEnum requireOps(String raw) {
        LhOpsPrivilegeEnum e = of(raw);
        if (e == null) {
            throw new CommonException("操作权限 privilege 须为 EDIT / DELETE / MANAGE，当前=" + raw);
        }
        return e;
    }

    /**
     * 持有的 privilege 是否满足所需能力。
     * MANAGE 覆盖 EDIT 与 DELETE；精确匹配 EDIT/DELETE 仅覆盖自身。
     */
    public static boolean satisfies(String heldPrivilege, LhOpsPrivilegeEnum needed) {
        if (needed == null || StrUtil.isBlank(heldPrivilege)) {
            return false;
        }
        LhOpsPrivilegeEnum held = of(heldPrivilege);
        if (held == null) {
            return false;
        }
        if (held == MANAGE) {
            return true;
        }
        return held == needed;
    }

    /** 查询时 IN 列表：查 EDIT 能力时含 EDIT+MANAGE */
    public static Set<String> matchingValues(LhOpsPrivilegeEnum needed) {
        if (needed == null) {
            return Set.of();
        }
        if (needed == MANAGE) {
            return Set.of(MANAGE.value);
        }
        return Set.of(needed.value, MANAGE.value);
    }

    public static String allowedHint() {
        return Arrays.stream(values()).map(LhOpsPrivilegeEnum::getValue).collect(Collectors.joining("/"));
    }
}
