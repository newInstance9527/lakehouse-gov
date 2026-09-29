package vip.xiaonuo.lh.core.vault;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import vip.xiaonuo.common.exception.CommonException;

import java.security.SecureRandom;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地 Vault 动态密轮换：生成新 password/AKSK，旧值进 previous*，generation++。
 * <p>非 HashiCorp；消费侧重读 Vault / binding stale。</p>
 */
@Component
public class LhVaultDynamicRotate {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHA =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%^&*";
    public static final int DEFAULT_LEASE_TTL_DAYS = 30;

    @Resource
    private LhVaultClient vaultClient;

    /**
     * 对已有路径做动态轮换；PLACEHOLDER / 不存在则失败。
     *
     * @return 写入后的密文 Map（含 generation；调用方勿回传前端）
     */
    public Map<String, Object> rotateExisting(String vaultPath) {
        if (StrUtil.isBlank(vaultPath)) {
            throw new CommonException("vaultPath 不能为空");
        }
        if (!vaultClient.exists(vaultPath)) {
            throw new CommonException("凭证不存在或为 PLACEHOLDER: {}", vaultPath);
        }
        Map<String, Object> old = new LinkedHashMap<>(vaultClient.read(vaultPath));
        Map<String, Object> next = rotateMap(old);
        vaultClient.write(vaultPath, next);
        return next;
    }

    /** 注册 SA 等：生成初始动态密（generation=1）。 */
    public Map<String, Object> seedNew(String vaultPath) {
        if (StrUtil.isBlank(vaultPath)) {
            throw new CommonException("vaultPath 不能为空");
        }
        Map<String, Object> seed = new LinkedHashMap<>();
        seed.put("password", randomSecret(24));
        seed.put("generation", 1);
        seed.put("leaseTtlDays", DEFAULT_LEASE_TTL_DAYS);
        seed.put("rotatedAt", new Date().toString());
        seed.put("createdAt", new Date().toString());
        boolean wrote = vaultClient.writeIfAbsent(vaultPath, seed);
        if (!wrote) {
            return vaultClient.read(vaultPath);
        }
        return seed;
    }

    /** 纯函数：在现有 Map 上做字段轮换（可单测）。 */
    public static Map<String, Object> rotateMap(Map<String, Object> old) {
        Map<String, Object> next = new LinkedHashMap<>(old == null ? Map.of() : old);
        boolean touched = false;
        if (hasKey(next, "password") || (!hasKey(next, "secretKey") && !hasKey(next, "accessKey")
                && !hasKey(next, "token") && !hasKey(next, "apiKey"))) {
            Object cur = next.get("password");
            if (cur != null && StrUtil.isNotBlank(String.valueOf(cur))) {
                next.put("previousPassword", String.valueOf(cur));
            }
            next.put("password", randomSecret(24));
            touched = true;
        }
        if (hasKey(next, "secretKey") || hasKey(next, "accessKey")) {
            Object ak = next.get("accessKey");
            Object sk = next.get("secretKey");
            if (ak != null && StrUtil.isNotBlank(String.valueOf(ak))) {
                next.put("previousAccessKey", String.valueOf(ak));
            }
            if (sk != null && StrUtil.isNotBlank(String.valueOf(sk))) {
                next.put("previousSecretKey", String.valueOf(sk));
            }
            if (hasKey(next, "accessKey") || StrUtil.isBlank(str(next.get("accessKey")))) {
                next.put("accessKey", "AK" + IdUtil.fastSimpleUUID().substring(0, 16).toUpperCase());
            }
            next.put("secretKey", randomSecret(32));
            touched = true;
        }
        if (hasKey(next, "token")) {
            Object cur = next.get("token");
            if (cur != null && StrUtil.isNotBlank(String.valueOf(cur))) {
                next.put("previousToken", String.valueOf(cur));
            }
            next.put("token", randomSecret(40));
            touched = true;
        }
        if (hasKey(next, "apiKey")) {
            Object cur = next.get("apiKey");
            if (cur != null && StrUtil.isNotBlank(String.valueOf(cur))) {
                next.put("previousApiKey", String.valueOf(cur));
            }
            next.put("apiKey", randomSecret(40));
            touched = true;
        }
        if (!touched) {
            next.put("previousPassword", next.get("password"));
            next.put("password", randomSecret(24));
        }
        int gen = 1;
        Object g = next.get("generation");
        if (g instanceof Number) {
            gen = ((Number) g).intValue() + 1;
        } else if (g != null && StrUtil.isNotBlank(String.valueOf(g))) {
            try {
                gen = Integer.parseInt(String.valueOf(g).trim()) + 1;
            } catch (NumberFormatException ignored) {
                gen = 2;
            }
        }
        next.put("generation", gen);
        next.put("leaseTtlDays", DEFAULT_LEASE_TTL_DAYS);
        next.put("rotatedAt", new Date().toString());
        return next;
    }

    public static int generationOf(Map<String, Object> secret) {
        if (secret == null) {
            return 0;
        }
        Object g = secret.get("generation");
        if (g instanceof Number) {
            return ((Number) g).intValue();
        }
        if (g != null) {
            try {
                return Integer.parseInt(String.valueOf(g).trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    static String randomSecret(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHA.charAt(RANDOM.nextInt(ALPHA.length())));
        }
        return sb.toString();
    }

    private static boolean hasKey(Map<String, Object> m, String key) {
        return m != null && m.containsKey(key);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
