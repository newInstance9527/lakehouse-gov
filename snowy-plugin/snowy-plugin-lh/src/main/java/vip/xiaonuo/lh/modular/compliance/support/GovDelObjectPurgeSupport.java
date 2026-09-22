package vip.xiaonuo.lh.modular.compliance.support;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 非结构化对象路径解析：{@code s3://bucket/prefix/{subject_id_hash}/} · {@code minio://...}
 */
public final class GovDelObjectPurgeSupport {

    private static final Pattern URI = Pattern.compile(
            "^(?:s3|minio|oss)://([^/]+)/?(.*)$", Pattern.CASE_INSENSITIVE);

    private GovDelObjectPurgeSupport() {
    }

    public record ObjectTarget(String bucket, String prefix) {
    }

    /**
     * 解析并替换 {@code {subject_id_hash}} / {@code {subjectIdHash}} 占位。
     */
    public static ObjectTarget resolve(String objectFqn, String subjectIdHash) {
        String raw = StrUtil.blankToDefault(objectFqn, "").trim();
        if (StrUtil.isBlank(raw)) {
            throw new IllegalArgumentException("object_fqn 为空");
        }
        String hash = StrUtil.blankToDefault(subjectIdHash, "").trim().toLowerCase(Locale.ROOT);
        String expanded = raw
                .replace("{subject_id_hash}", hash)
                .replace("{subjectIdHash}", hash)
                .replace("{SUBJECT_ID_HASH}", hash);
        // 无占位且无显式 hash 段时，追加 /{hash}/
        if (StrUtil.isNotBlank(hash) && !expanded.toLowerCase(Locale.ROOT).contains(hash)) {
            if (!expanded.endsWith("/")) {
                expanded = expanded + "/";
            }
            expanded = expanded + hash + "/";
        }
        Matcher m = URI.matcher(expanded);
        if (!m.matches()) {
            throw new IllegalArgumentException("object_fqn 须为 s3://bucket/prefix 或 minio://bucket/prefix");
        }
        String bucket = m.group(1).trim();
        String prefix = StrUtil.blankToDefault(m.group(2), "").replaceAll("^/+", "");
        if (StrUtil.isBlank(bucket)) {
            throw new IllegalArgumentException("bucket 为空");
        }
        return new ObjectTarget(bucket, prefix);
    }
}
