package vip.xiaonuo.lh.core.auth;

import cn.hutool.core.collection.CollUtil;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.auth.core.util.StpLoginUserUtil;
import vip.xiaonuo.common.exception.CommonException;

import java.util.List;

/**
 * 湖仓门户登录用户快捷方法
 */
public final class LhLoginUsers {

    private LhLoginUsers() {
    }

    public static SaBaseLoginUser requireUser() {
        SaBaseLoginUser u = StpLoginUserUtil.getLoginUser();
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
            SaBaseLoginUser u = StpLoginUserUtil.getLoginUser();
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
