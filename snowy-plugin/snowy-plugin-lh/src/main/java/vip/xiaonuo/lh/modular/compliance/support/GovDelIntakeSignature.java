package vip.xiaonuo.lh.modular.compliance.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 外部 DSR webhook 签名：HMAC-SHA256(canonical)。
 * <p>
 * 规范头：{@code X-Lh-Intake-Signature: sha256=&lt;hex&gt;}（亦接受裸 hex / {@code sha256=} 前缀）。
 * canonical：{@code subjectType|subjectId|sourceSystem|sourceRef|reqType}
 * </p>
 */
public final class GovDelIntakeSignature {

    public static final String HEADER = "X-Lh-Intake-Signature";
    public static final String HEADER_TS = "X-Lh-Intake-Timestamp";

    /** 默认允许时钟偏移（秒） */
    public static final long DEFAULT_SKEW_SECONDS = 300L;

    private GovDelIntakeSignature() {
    }

    public static String canonical(String subjectType, String subjectId, String sourceSystem,
                                   String sourceRef, String reqType) {
        return String.join("|",
                StrUtil.blankToDefault(subjectType, "user").trim().toLowerCase(Locale.ROOT),
                StrUtil.blankToDefault(subjectId, "").trim(),
                StrUtil.blankToDefault(sourceSystem, "").trim(),
                StrUtil.blankToDefault(sourceRef, "").trim(),
                StrUtil.blankToDefault(reqType, "forget").trim().toLowerCase(Locale.ROOT));
    }

    public static String signHex(String secret, String canonical) {
        if (StrUtil.isBlank(secret)) {
            throw new IllegalArgumentException("webhook secret 为空");
        }
        return SecureUtil.hmacSha256(secret).digestHex(StrUtil.nullToEmpty(canonical));
    }

    public static boolean verify(String secret, String canonical, String signatureHeader) {
        if (StrUtil.isBlank(secret) || StrUtil.isBlank(signatureHeader)) {
            return false;
        }
        String expected = signHex(secret, canonical);
        String got = normalizeSignature(signatureHeader);
        if (StrUtil.isBlank(got) || expected.length() != got.length()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                got.getBytes(StandardCharsets.UTF_8));
    }

    public static String normalizeSignature(String header) {
        String raw = StrUtil.blankToDefault(header, "").trim();
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.startsWith("sha256=")) {
            return raw.substring(7).trim().toLowerCase(Locale.ROOT);
        }
        if (lower.startsWith("v1=")) {
            return raw.substring(3).trim().toLowerCase(Locale.ROOT);
        }
        return raw.toLowerCase(Locale.ROOT);
    }

    /**
     * 可选时间戳校验；header 为空则跳过（兼容老客户端）。
     *
     * @return true=通过或未提供；false=超窗或非法
     */
    public static boolean timestampOk(String timestampHeader, long nowEpochSec, long skewSeconds) {
        if (StrUtil.isBlank(timestampHeader)) {
            return true;
        }
        try {
            long ts = Long.parseLong(timestampHeader.trim());
            // 毫秒误传时收敛到秒
            if (ts > 10_000_000_000L) {
                ts = ts / 1000L;
            }
            return Math.abs(nowEpochSec - ts) <= Math.max(1L, skewSeconds);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
