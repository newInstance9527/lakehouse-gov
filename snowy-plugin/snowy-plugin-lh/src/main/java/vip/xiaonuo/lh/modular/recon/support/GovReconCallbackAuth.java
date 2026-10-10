package vip.xiaonuo.lh.modular.recon.support;

import cn.hutool.core.util.StrUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * rewrite_ck / 黄金事件 DS 回调鉴权：头 {@code X-Lh-Recon-Callback-Token}。
 */
public final class GovReconCallbackAuth {

    public static final String HEADER = "X-Lh-Recon-Callback-Token";

    private GovReconCallbackAuth() {
    }

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
