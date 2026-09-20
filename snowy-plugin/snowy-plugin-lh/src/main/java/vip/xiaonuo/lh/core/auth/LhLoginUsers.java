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
        try {
            SaBaseLoginUser u = StpLoginUserUtil.getLoginUser();
            if (u == null) {
                return false;
            }
            List<String> roles = u.getRoleCodeList();
            return CollUtil.isNotEmpty(roles) && roles.contains("superAdmin");
        } catch (Exception e) {
            return false;
        }
    }
}
