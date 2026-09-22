package vip.xiaonuo.lh.modular.compliance.support;

import cn.hutool.core.util.HexUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.crypto.symmetric.AES;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * crypto-shredding 纯函数：DEK 路径约定、指纹、KEK 包裹（AES）。
 * <p>不直连 Vault / DB；编排在 Service。</p>
 */
public final class GovDelCryptoShredSupport {

    private static final SecureRandom RANDOM = new SecureRandom();

    private GovDelCryptoShredSupport() {
    }

    /**
     * Vault 路径：{@code lh/compliance/dek/{subjectIdHash}/{objectSlug}/{column}}
     */
    public static String dekVaultPath(String subjectIdHash, String objectFqn, String columnName) {
        String hash = StrUtil.blankToDefault(subjectIdHash, "").trim().toLowerCase(Locale.ROOT);
        String col = StrUtil.blankToDefault(columnName, "pii").trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]", "_");
        String slug = objectSlug(objectFqn);
        return "lh/compliance/dek/" + hash + "/" + slug + "/" + col;
    }

    public static String objectSlug(String objectFqn) {
        String raw = StrUtil.blankToDefault(objectFqn, "unknown").trim().toLowerCase(Locale.ROOT);
        String compact = raw.replaceAll("[^a-z0-9._-]", "_");
        if (compact.length() <= 96) {
            return compact;
        }
        return SecureUtil.sha256(compact).substring(0, 32);
    }

    /** 生成 32 字节 DEK */
    public static byte[] generateDek() {
        byte[] dek = new byte[32];
        RANDOM.nextBytes(dek);
        return dek;
    }

    /**
     * 用 KEK 包裹 DEK（AES/ECB/PKCS5，材料仅存 Vault）。
     * KEK 取前 16 字节（不足则 SHA-256 派生）。
     */
    public static String wrapDek(byte[] dek, String kekMaterial) {
        if (dek == null || dek.length == 0) {
            throw new IllegalArgumentException("DEK 为空");
        }
        AES aes = new AES(normalizeKek(kekMaterial));
        return aes.encryptHex(dek);
    }

    public static byte[] unwrapDek(String wrappedHex, String kekMaterial) {
        if (StrUtil.isBlank(wrappedHex)) {
            throw new IllegalArgumentException("wrapped DEK 为空");
        }
        AES aes = new AES(normalizeKek(kekMaterial));
        return aes.decrypt(wrappedHex);
    }

    /** 审计指纹：sha256(wrappedHex)，非密钥本身 */
    public static String fingerprint(String wrappedHex) {
        return SecureUtil.sha256(StrUtil.blankToDefault(wrappedHex, ""));
    }

    public static byte[] normalizeKek(String kekMaterial) {
        String raw = StrUtil.blankToDefault(kekMaterial, "");
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        if (bytes.length >= 16) {
            byte[] key = new byte[16];
            System.arraycopy(bytes, 0, key, 0, 16);
            return key;
        }
        // 短材料用 sha256 派生 16 字节
        byte[] digest = SecureUtil.sha256().digest(bytes);
        byte[] key = new byte[16];
        System.arraycopy(digest, 0, key, 0, 16);
        return key;
    }

    /** 解析 object_fqn 中的列：支持 {@code table#col} / {@code table.col}（末段当列） */
    public static String resolveColumn(String objectFqn, String explicitColumn) {
        if (StrUtil.isNotBlank(explicitColumn)) {
            return explicitColumn.trim();
        }
        String fqn = StrUtil.blankToDefault(objectFqn, "").trim();
        int hash = fqn.lastIndexOf('#');
        if (hash >= 0 && hash < fqn.length() - 1) {
            return fqn.substring(hash + 1).trim();
        }
        return "pii";
    }

    public static String stripColumnFromFqn(String objectFqn) {
        String fqn = StrUtil.blankToDefault(objectFqn, "").trim();
        int hash = fqn.lastIndexOf('#');
        if (hash > 0) {
            return fqn.substring(0, hash).trim();
        }
        return fqn;
    }

    /** 调试友好：hex 前 8 位 */
    public static String shortHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        return HexUtil.encodeHexStr(bytes).substring(0, Math.min(8, bytes.length * 2));
    }
}
