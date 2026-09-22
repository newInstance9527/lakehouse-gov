package vip.xiaonuo.lh.modular.lifecycle.support;

import cn.hutool.core.util.StrUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 生命周期 DS 推送回调鉴权：共享 token（头 {@code X-Lh-Lc-Callback-Token}）。
 */
public final class GovLcCallbackAuth {

    public static final String HEADER = "X-Lh-Lc-Callback-Token";

    private GovLcCallbackAuth() {
    }

    /** 常量时间比较；任一侧为空则失败。 */
    public static boolean tokenMatches(String configured, String presented) {
        if (StrUtil.isBlank(configured) || StrUtil.isBlank(presented)) {
            return false;
        }
        byte[] a = configured.trim().getBytes(StandardCharsets.UTF_8);
        byte[] b = presented.trim().getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) {
            return false;
        }
        return MessageDigest.isEqual(a, b);
    }
}
