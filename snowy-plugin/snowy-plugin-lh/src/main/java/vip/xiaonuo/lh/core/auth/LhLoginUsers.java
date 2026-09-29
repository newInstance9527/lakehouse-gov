package vip.xiaonuo.lh.core.auth;

import cn.hutool.core.collection.CollUtil;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.auth.core.util.StpLoginUserUtil;
import vip.xiaonuo.common.exception.CommonException;

import java.util.List;
import java.util.function.Supplier;

/**
 * 湖仓门户登录用户快捷方法。
 * <p>支持 {@link #runAs}：SSE/线程池无 Servlet 上下文时，在请求线程捕获登录用户后传入异步任务。</p>
 */
public final class LhLoginUsers {

    private static final ThreadLocal<SaBaseLoginUser> OVERRIDE = new ThreadLocal<>();

    private LhLoginUsers() {
    }

    /** 在异步线程中临时绑定登录用户（finally 自动清除）。 */
    public static void runAs(SaBaseLoginUser user, Runnable action) {
        if (action == null) {
            return;
        }
        SaBaseLoginUser prev = OVERRIDE.get();
        OVERRIDE.set(user);
        try {
            action.run();
        } finally {
            if (prev != null) {
                OVERRIDE.set(prev);
            } else {
                OVERRIDE.remove();
            }
        }
    }

    public static <T> T callAs(SaBaseLoginUser user, Supplier<T> action) {
        if (action == null) {
            return null;
        }
        SaBaseLoginUser prev = OVERRIDE.get();
        OVERRIDE.set(user);
        try {
            return action.get();
        } finally {
            if (prev != null) {
                OVERRIDE.set(prev);
            } else {
                OVERRIDE.remove();
            }
        }
    }

    /** 当前登录用户；无登录或上下文缺失时返回 null（不抛）。 */
    public static SaBaseLoginUser currentUserOrNull() {
        SaBaseLoginUser o = OVERRIDE.get();
        if (o != null && o.getId() != null) {
            return o;
        }
        try {
            SaBaseLoginUser u = StpLoginUserUtil.getLoginUser();
            if (u != null && u.getId() != null) {
                return u;
            }
        } catch (Exception ignored) {
            // SaTokenContext 未初始化等
        }
        return null;
    }

    public static SaBaseLoginUser requireUser() {
        SaBaseLoginUser u = currentUserOrNull();
        if (u == null || u.getId() == null) {
            throw new CommonException("未登录");
        }
        return u;
    }

    public static String requireUserId() {
        return requireUser().getId();
    }

    public static boolean isSuperAdmin() {
        return hasAnyRole("superAdmin");
    }

    /** 任一角色命中（精确 code） */
    public static boolean hasAnyRole(String... roleCodes) {
        if (roleCodes == null || roleCodes.length == 0) {
            return false;
        }
        try {
            SaBaseLoginUser u = currentUserOrNull();
            if (u == null) {
                return false;
            }
            List<String> roles = u.getRoleCodeList();
            if (CollUtil.isEmpty(roles)) {
                return false;
            }
            for (String code : roleCodes) {
                if (code != null && roles.contains(code)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 公用知识库写权限：超管 / 业务管理员 / 数据运维 / 元数据管理员。
     */
    public static boolean canWritePlatformKb() {
        return hasAnyRole("superAdmin", "bizAdmin", "dataOps", "metadataAdmin");
    }
}
