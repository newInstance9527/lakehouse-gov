package vip.xiaonuo.lh.modular.compliance.support;

import cn.hutool.core.util.StrUtil;

/**
 * 合规证据包对象路径约定（禁止标识符含 cursor）。
 *
 * <p>key：{@code {prefix}/{reqNo}/{sha256}.zip}</p>
 */
public final class GovDelEvidenceObjectKeys {

    public static final String DEFAULT_BUCKET = "lh-audit";
    public static final String DEFAULT_PREFIX = "compliance/evidence";
    public static final String CONTENT_TYPE_ZIP = "application/zip";

    private GovDelEvidenceObjectKeys() {
    }

    public static String sanitizeSegment(String raw) {
        String s = StrUtil.blankToDefault(raw, "unknown").trim();
        s = s.replaceAll("[^a-zA-Z0-9._-]+", "_");
        if (s.isEmpty() || ".".equals(s) || "..".equals(s) || s.contains("..")) {
            return "unknown";
        }
        return s.length() > 128 ? s.substring(0, 128) : s;
    }

    public static String objectKey(String prefix, String reqNo, String sha256) {
        String p = StrUtil.removeSuffix(StrUtil.blankToDefault(prefix, DEFAULT_PREFIX).trim(), "/");
        if (p.isEmpty()) {
            p = DEFAULT_PREFIX;
        }
        String sha = sanitizeSegment(StrUtil.blankToDefault(sha256, "pending").toLowerCase());
        return p + "/" + sanitizeSegment(reqNo) + "/" + sha + ".zip";
    }

    public static String uri(String bucket, String objectKey) {
        String b = StrUtil.blankToDefault(bucket, DEFAULT_BUCKET).trim();
        String k = StrUtil.removePrefix(StrUtil.blankToDefault(objectKey, ""), "/");
        return "s3a://" + b + "/" + k;
    }
}
