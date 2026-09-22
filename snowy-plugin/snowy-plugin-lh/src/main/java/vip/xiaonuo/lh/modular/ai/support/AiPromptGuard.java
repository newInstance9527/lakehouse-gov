package vip.xiaonuo.lh.modular.ai.support;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D5：Prompt 侧 PII / 密钥扫描与脱敏。
 * <p>密钥类命中 → 阻断下发；PII 类 → 脱敏后允许继续。</p>
 */
public final class AiPromptGuard {

    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?!\\d)");
    private static final Pattern EMAIL = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)(?:62|4|5)\\d{14,18}(?!\\d)");

    /** 厂商 API Key / Bearer / AWS 等 */
    private static final Pattern SECRET = Pattern.compile(
            "(?i)("
                    + "sk-[a-z0-9_-]{16,}"
                    + "|sk-ant-[a-z0-9_-]{16,}"
                    + "|sk-proj-[a-z0-9_-]{16,}"
                    + "|AKIA[0-9A-Z]{16}"
                    + "|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"
                    + "|Bearer\\s+[a-z0-9._\\-]{20,}"
                    + "|eyJ[a-zA-Z0-9_-]{10,}\\.eyJ[a-zA-Z0-9_-]{10,}\\.[a-zA-Z0-9_-]{10,}"
                    + "|(?:api[_-]?key|secret[_-]?key|access[_-]?token|password)\\s*[=:]\\s*['\"]?[^\\s'\"]{8,}"
                    + ")");

    private AiPromptGuard() {
    }

    public static final class ScanResult {
        private final String original;
        private final String redacted;
        private final boolean blocked;
        private final String blockReason;
        private final List<String> findings;

        private ScanResult(String original, String redacted, boolean blocked,
                           String blockReason, List<String> findings) {
            this.original = original;
            this.redacted = redacted;
            this.blocked = blocked;
            this.blockReason = blockReason;
            this.findings = findings;
        }

        public String getOriginal() {
            return original;
        }

        public String getRedacted() {
            return redacted;
        }

        public boolean isBlocked() {
            return blocked;
        }

        public String getBlockReason() {
            return blockReason;
        }

        public List<String> getFindings() {
            return findings;
        }

        public boolean isRedacted() {
            return StrUtil.isNotBlank(original) && !original.equals(redacted);
        }
    }

    /**
     * 扫描并准备下发文本：密钥阻断；PII 脱敏。
     */
    public static ScanResult prepareForLlm(String text) {
        if (StrUtil.isBlank(text)) {
            return new ScanResult(text, text, false, null, List.of());
        }
        Set<String> findings = new LinkedHashSet<>();
        Matcher secretMatcher = SECRET.matcher(text);
        if (secretMatcher.find()) {
            findings.add("secret");
            return new ScanResult(text, text, true,
                    "Prompt 含疑似密钥/凭证，禁止下发至模型。请移除 API Key、Token、私钥后再试。",
                    new ArrayList<>(findings));
        }

        String out = text;
        out = replaceAll(out, MOBILE, "phone", findings, "***PHONE***");
        out = replaceAll(out, ID_CARD, "id_card", findings, "***ID***");
        out = replaceAll(out, EMAIL, "email", findings, "***EMAIL***");
        out = replaceAll(out, BANK_CARD, "bank_card", findings, "***CARD***");
        return new ScanResult(text, out, false, null, new ArrayList<>(findings));
    }

    private static String replaceAll(String text, Pattern pattern, String label,
                                     Set<String> findings, String placeholder) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return text;
        }
        findings.add(label);
        StringBuffer sb = new StringBuffer();
        m.reset();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 测试辅助：是否像外发敏感正文 */
    static boolean looksLikeSecret(String text) {
        if (StrUtil.isBlank(text)) {
            return false;
        }
        return SECRET.matcher(text).find();
    }
}
