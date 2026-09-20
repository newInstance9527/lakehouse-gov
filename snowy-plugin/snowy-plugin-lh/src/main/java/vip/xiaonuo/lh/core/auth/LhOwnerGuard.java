package vip.xiaonuo.lh.core.auth;

import cn.hutool.core.util.StrUtil;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;
import vip.xiaonuo.common.exception.CommonException;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 资源拥有者判定（与资产预览授权对齐）：
 * 匹配 createUser / owner 类字段（id / account / name / nickname，支持「姓名(备注)」）。
 * 改删 / 预览均不做超管短路。
 */
public final class LhOwnerGuard {

    /** 前端可据此引导至申请中心 */
    public static final String NEED_APPLY_CODE = "NEED_OWNER_APPLY";

    public static final String MSG_NEED_APPLY =
            "非拥有者不可直接修改或删除，请前往申请中心提交权限申请（" + NEED_APPLY_CODE + "）";

    private LhOwnerGuard() {
    }

    public static Set<String> identitiesOf(SaBaseLoginUser user) {
        if (user == null) {
            return Set.of();
        }
        return Stream.of(user.getId(), user.getAccount(), user.getName(), user.getNickname())
                .filter(StrUtil::isNotBlank)
                .map(LhOwnerGuard::normIdentity)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
    }

    public static boolean matchesOwnerField(String ownerField, Set<String> identities) {
        if (StrUtil.isBlank(ownerField) || identities == null || identities.isEmpty()) {
            return false;
        }
        String raw = normIdentity(ownerField);
        if (identities.contains(raw)) {
            return true;
        }
        int paren = raw.indexOf('(');
        if (paren > 0) {
            String bare = raw.substring(0, paren).trim();
            return StrUtil.isNotBlank(bare) && identities.contains(bare);
        }
        return false;
    }

    /**
     * @param createUser 登记人/创建人（可空）
     * @param ownerFields techOwner / bizOwner / owner 等
     */
    public static boolean isOwner(SaBaseLoginUser user, String createUser, String... ownerFields) {
        Set<String> identities = identitiesOf(user);
        if (identities.isEmpty()) {
            return false;
        }
        if (StrUtil.isNotBlank(createUser) && identities.contains(normIdentity(createUser))) {
            return true;
        }
        if (ownerFields == null) {
            return false;
        }
        for (String f : ownerFields) {
            if (matchesOwnerField(f, identities)) {
                return true;
            }
        }
        return false;
    }

    /** 拥有者方可改删；不做超管短路（与数据预览一致） */
    public static void assertCanManage(String resourceLabel, String createUser, String... ownerFields) {
        SaBaseLoginUser user = LhLoginUsers.requireUser();
        if (isOwner(user, createUser, ownerFields)) {
            return;
        }
        String label = StrUtil.blankToDefault(resourceLabel, "资源");
        throw new CommonException(label + "：" + MSG_NEED_APPLY);
    }

    public static String normIdentity(String s) {
        return StrUtil.blankToDefault(s, "").trim().toLowerCase(Locale.ROOT);
    }
}
