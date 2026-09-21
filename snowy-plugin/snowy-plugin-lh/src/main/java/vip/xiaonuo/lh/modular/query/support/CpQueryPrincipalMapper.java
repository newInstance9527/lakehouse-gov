package vip.xiaonuo.lh.modular.query.support;

import cn.hutool.core.util.StrUtil;
import vip.xiaonuo.auth.core.pojo.SaBaseLoginUser;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 门户用户 → Trino 执行主体映射（鉴权用 X-Trino-User；Basic 仍用服务账号）
 */
public final class CpQueryPrincipalMapper {

    private static final Pattern SAFE = Pattern.compile("[^A-Za-z0-9._@\\-]");

    private CpQueryPrincipalMapper() {
    }

    public static Principal resolve(SaBaseLoginUser login, String fallbackAccount) {
        Principal p = new Principal();
        if (login != null) {
            p.userId = login.getId();
            p.account = StrUtil.blankToDefault(login.getAccount(), fallbackAccount);
            p.displayName = StrUtil.blankToDefault(login.getName(),
                    StrUtil.blankToDefault(login.getNickname(), p.account));
        } else {
            p.account = StrUtil.blankToDefault(fallbackAccount, "anonymous");
            p.displayName = p.account;
            p.userId = "anonymous";
        }
        p.trinoUser = sanitize(p.account);
        if (StrUtil.isBlank(p.trinoUser)) {
            p.trinoUser = "anonymous";
        }
        return p;
    }

    /** 写入 Trino 的 session / 客户端标签 */
    public static Map<String, String> sessionProps(Principal principal, String ws, boolean elevated, long maxScanBytes) {
        Map<String, String> m = new LinkedHashMap<>();
        if (maxScanBytes > 0) {
            m.put(CpQueryScanGuard.QUERY_MAX_SCAN_PHYSICAL_BYTES, CpQueryScanGuard.toTrinoDataSize(maxScanBytes));
        }
        // 便于审计与队列识别（Trino 日志 / query event）
        m.put("query_max_execution_time", elevated ? "30m" : "10m");
        return m;
    }

    public static String clientTags(Principal principal, String ws) {
        String a = sanitize(principal == null ? "anon" : principal.trinoUser);
        String w = sanitize(StrUtil.blankToDefault(ws, "default"));
        return "adhoc,portal,user=" + a + ",ws=" + w;
    }

    private static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = SAFE.matcher(raw.trim()).replaceAll("_");
        if (s.length() > 64) {
            s = s.substring(0, 64);
        }
        return s.toLowerCase(Locale.ROOT);
    }

    public static class Principal {
        public String userId;
        public String account;
        public String displayName;
        /** X-Trino-User */
        public String trinoUser;
    }
}
